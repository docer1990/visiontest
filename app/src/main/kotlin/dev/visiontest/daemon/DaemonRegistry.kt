package dev.visiontest.daemon

import java.io.Closeable
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Metadata is advisory. It must never authorize killing a process named by its PID. */
data class DaemonRegistration(val pid: Long, val identity: DaemonIdentity)

/** No CLI framework or native component dependencies: status can use this independently. */
class DaemonRegistry(val directory: Path = defaultDirectory()) {
    val socketPath: Path = directory.resolve("s")
    val metadataPath: Path = directory.resolve("ready.json")
    val lockPath: Path = directory.resolve("lock")
    private val owner = directory.fileSystem.userPrincipalLookupService
        .lookupPrincipalByName(System.getProperty("user.name"))

    fun acquire(): Lease? {
        prepareDirectory()
        validateIfPresent(lockPath)
        val channel = FileChannel.open(lockPath, setOf(CREATE, WRITE, NOFOLLOW_LINKS), PRIVATE_FILE)
        var transferred = false
        return try {
            validateIfPresent(lockPath)
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) null else {
                // A live owner may be securing a freshly bound socket before atomic publication.
                // Only the lock holder inspects paths for stale registration cleanup.
                listOf(metadataPath, socketPath).forEach { validateIfPresent(it, allowStaleSocket = true) }
                Lease(channel, lock).also { transferred = true }
            }
        } finally {
            if (!transferred) channel.close()
        }
    }

    fun readMetadata(): DaemonRegistration? {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return null
        validate(directory, isDirectory = true)
        return if (Files.exists(metadataPath, NOFOLLOW_LINKS)) {
            validate(metadataPath, isDirectory = false)
            Files.newInputStream(metadataPath, NOFOLLOW_LINKS).use { input ->
                val bytes = input.readNBytes(MAX_METADATA_BYTES + 1)
                if (bytes.size > MAX_METADATA_BYTES) throw IOException("Daemon metadata exceeds size limit")
                parseMetadata(bytes)
            }
        } else null
    }

    private fun parseMetadata(bytes: ByteArray): DaemonRegistration {
        val value = try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject } catch (
            error: kotlinx.serialization.SerializationException,
        ) { throw IOException("Invalid daemon metadata", error) }
            ?: throw IOException("Daemon metadata must be an object")
        return registration(value)
    }

    private fun registration(value: JsonObject): DaemonRegistration {
        val pidValue = value["pid"] as? JsonPrimitive
        val pid = pidValue?.takeUnless { it.isString }?.longOrNull
        if (pid == null || pid <= 0) throw IOException("Invalid daemon metadata PID")
        return DaemonRegistration(pid, DaemonProtocol.identity(value))
    }

    private fun prepareDirectory() {
        try {
            Files.createDirectory(directory, PRIVATE_DIRECTORY)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // Existing paths must pass validation; never chmod a possibly unrelated directory.
        }
        validate(directory, isDirectory = true)
        if (socketPath.toAbsolutePath().toString().toByteArray().size > MAX_SOCKET_PATH_BYTES) {
            throw IOException("Daemon socket path is too long; set VISIONTEST_DAEMON_DIR to a short private directory")
        }
    }

    private fun validateIfPresent(path: Path, allowStaleSocket: Boolean = false) {
        if (Files.exists(path, NOFOLLOW_LINKS)) validate(path, isDirectory = false, allowStaleSocket)
    }

    private fun validate(path: Path, isDirectory: Boolean, allowStaleSocket: Boolean = false) {
        val attrs = Files.readAttributes(path, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
        val expected = if (isDirectory) DIRECTORY_PERMISSIONS else FILE_PERMISSIONS
        val wrongType = when {
            isDirectory -> !attrs.isDirectory
            path == socketPath -> !isUnixSocket(path)
            else -> !attrs.isRegularFile
        }
        val unsafeOwner = attrs.isSymbolicLink || attrs.owner() != owner
        val staleSocket = allowStaleSocket && path == socketPath
        val wrongMode = attrs.permissions() != expected && !staleSocket
        if (unsafeOwner || wrongMode || wrongType) {
            val mode = if (isDirectory) "0700" else "0600"
            throw IOException("Unsafe daemon path $path: requires current owner and $mode")
        }
    }

    private fun secureBoundSocket() {
        if (!Files.exists(socketPath, NOFOLLOW_LINKS)) {
            throw IOException("Bind daemon socket before publishing readiness")
        }
        val attrs = Files.readAttributes(socketPath, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
        val wrongOwner = attrs.isSymbolicLink || attrs.owner() != owner
        if (wrongOwner || !isUnixSocket(socketPath)) throw IOException("Unsafe daemon socket path")
        // Only the newly bound socket is chmodded, after NOFOLLOW ownership/type checks.
        // JDK NOFOLLOW chmod attempts to open the socket and fails on macOS.
        Files.setPosixFilePermissions(socketPath, FILE_PERMISSIONS)
        validate(socketPath, isDirectory = false)
    }

    private fun isUnixSocket(path: Path): Boolean {
        val mode = Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Int
        return mode and FILE_TYPE_MASK == SOCKET_FILE_TYPE
    }

    /** The lease owns cleanup as well as publication; cleanup always happens before releasing the lock. */
    inner class Lease internal constructor(private val channel: FileChannel, private val lock: FileLock) : Closeable {
        private var closed = false
        private var published = false

        fun removeStaleRegistration() {
            checkHeld()
            validate(directory, isDirectory = true)
            listOf(metadataPath, socketPath).forEach { path ->
                validateIfPresent(path, allowStaleSocket = true)
                Files.deleteIfExists(path)
            }
        }

        /** Call only after binding the socket. The path is secured before readiness becomes visible. */
        fun publishReady(registration: DaemonRegistration) {
            checkHeld()
            if (registration.pid <= 0) throw IOException("Invalid daemon PID")
            secureBoundSocket()
            val hello = DaemonProtocol.encode(DaemonMessage.Hello(registration.identity))
            val metadata = JsonObject(hello - "kind" + ("pid" to JsonPrimitive(registration.pid)))
            validateIfPresent(metadataPath)
            val temporary = Files.createTempFile(directory, "ready-", ".tmp", PRIVATE_FILE)
            try {
                Files.writeString(temporary, metadata.toString(), WRITE, NOFOLLOW_LINKS)
                Files.move(temporary, metadataPath, ATOMIC_MOVE, REPLACE_EXISTING)
                published = true
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        override fun close() {
            if (closed) return
            try {
                if (published) removeStaleRegistration() else cleanUnpublishedSocket()
            } finally {
                closed = true
                try { lock.release() } finally { channel.close() }
            }
        }

        private fun cleanUnpublishedSocket() {
            if (!Files.exists(socketPath, NOFOLLOW_LINKS)) return
            val attrs = Files.readAttributes(socketPath, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
            val owned = !attrs.isSymbolicLink && attrs.owner() == owner
            if (owned && isUnixSocket(socketPath)) {
                validate(directory, isDirectory = true)
                Files.delete(socketPath)
            }
        }

        private fun checkHeld() {
            if (closed || !lock.isValid) throw IOException("Daemon registration lock is not held")
        }
    }

    companion object {
        private const val FILE_TYPE_MASK = 0xF000
        private const val SOCKET_FILE_TYPE = 0xC000
        private const val USER_HASH_BYTES = 8
        private const val MAX_METADATA_BYTES = 4096
        private const val MAX_SOCKET_PATH_BYTES = 100
        private val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        private val FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
        private val PRIVATE_DIRECTORY = PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS)
        private val PRIVATE_FILE = PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS)

        fun defaultDirectory(environment: Map<String, String> = System.getenv()): Path {
            environment["VISIONTEST_DAEMON_DIR"]?.let {
                if (it.isBlank() || !Path.of(it).isAbsolute) throw IOException("VISIONTEST_DAEMON_DIR must be absolute")
                return Path.of(it)
            }
            val user = System.getProperty("user.name").toByteArray(Charsets.UTF_8)
            val suffix = MessageDigest.getInstance("SHA-256").digest(user).take(USER_HASH_BYTES)
                .joinToString("") { "%02x".format(it) }
            return Path.of("/tmp", "visiontest-$suffix")
        }
    }
}

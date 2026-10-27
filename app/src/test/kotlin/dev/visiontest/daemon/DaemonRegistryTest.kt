package dev.visiontest.daemon

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DaemonRegistryTest {
    @TempDir lateinit var temp: Path
    private val identity = DaemonIdentity(1, "1", "a".repeat(64))
    private fun registry() = DaemonRegistry(temp.resolve("runtime"))

    @Test fun `private registration is published only explicitly and lock excludes competitors`() {
        val registry = registry()
        registry.acquire()!!.use { lease ->
            assertNull(registry.readMetadata())
            assertNull(registry.acquire())
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(registry.directory)))
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(registry.socketPath))
                lease.publishReady(DaemonRegistration(123, identity))
            }
            assertEquals(DaemonRegistration(123, identity), registry.readMetadata())
            listOf(registry.metadataPath, registry.socketPath, registry.lockPath).forEach {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(it)))
            }
        }
        assertNull(registry.readMetadata())
        assertFalse(Files.exists(registry.socketPath, NOFOLLOW_LINKS))
        registry.acquire()!!.close()
    }

    @Test fun `only lock holder removes stale paths`() {
        val registry = registry()
        registry.acquire()!!.use { }
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(registry.socketPath))
        }
        Files.setPosixFilePermissions(registry.socketPath, PosixFilePermissions.fromString("rw-------"))
        registry.acquire()!!.use { lease ->
            assertTrue(Files.exists(registry.socketPath))
            lease.removeStaleRegistration()
            assertFalse(Files.exists(registry.socketPath))
        }
    }

    @Test fun `unsafe directory and symlinks are rejected`() {
        val registry = registry()
        Files.createDirectory(registry.directory)
        Files.setPosixFilePermissions(registry.directory, PosixFilePermissions.fromString("rwxr-xr-x"))
        assertFailsWith<IOException> { registry.acquire() }
        Files.delete(registry.directory)
        Files.createSymbolicLink(registry.directory, temp)
        assertFailsWith<IOException> { registry.acquire() }
    }

    @Test fun `symlink metadata and permissive lock are rejected`() {
        val registry = registry()
        registry.acquire()!!.close()
        Files.createSymbolicLink(registry.metadataPath, temp.resolve("elsewhere"))
        assertFailsWith<IOException> { registry.readMetadata() }
        assertFailsWith<IOException> { registry.acquire() }
        Files.delete(registry.metadataPath)
        Files.setPosixFilePermissions(registry.lockPath, PosixFilePermissions.fromString("rw-r--r--"))
        assertFailsWith<IOException> { registry.acquire() }
    }

    @Test fun `readiness requires bound path and malformed metadata fails`() {
        val registry = registry()
        registry.acquire()!!.use { lease ->
            assertFailsWith<IOException> { lease.publishReady(DaemonRegistration(123, identity)) }
            Files.writeString(registry.metadataPath, "{}")
            Files.setPosixFilePermissions(registry.metadataPath, PosixFilePermissions.fromString("rw-------"))
            assertFailsWith<IOException> { registry.readMetadata() }
        }
    }
    @Test fun `regular file cannot be published as a bound socket`() {
        val registry = registry()
        registry.acquire()!!.use { lease ->
            Files.createFile(registry.socketPath)
            assertFailsWith<IOException> { lease.publishReady(DaemonRegistration(123, identity)) }
        }
    }

    @Test fun `metadata must be a regular file`() {
        val registry = registry()
        registry.acquire()!!.use {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
                socket.bind(UnixDomainSocketAddress.of(registry.metadataPath))
                Files.setPosixFilePermissions(registry.metadataPath, PosixFilePermissions.fromString("rw-------"))
                assertFailsWith<IOException> { registry.readMetadata() }
            }
            Files.delete(registry.metadataPath)
        }
    }

    @Test fun `released lease cannot delete new instance registration`() {
        val registry = registry()
        val old = registry.acquire()!!
        old.close()
        registry.acquire()!!.use {
            Files.createFile(registry.socketPath)
            assertFailsWith<IOException> { old.removeStaleRegistration() }
            old.close()
            assertTrue(Files.exists(registry.socketPath))
        }
    }

    @Test fun `runtime override must be absolute and isolates registration`() {
        val custom = temp.resolve("isolated")
        assertEquals(custom, DaemonRegistry.defaultDirectory(mapOf("VISIONTEST_DAEMON_DIR" to custom.toString())))
        assertFailsWith<IOException> { DaemonRegistry.defaultDirectory(mapOf("VISIONTEST_DAEMON_DIR" to "relative")) }
        assertEquals(DaemonRegistry.defaultDirectory(emptyMap()), DaemonRegistry.defaultDirectory(emptyMap()))
    }

    @Test fun `contender waits while lock holder secures freshly bound socket`() {
        val registry = registry()
        registry.acquire()!!.use { lease ->
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(registry.socketPath))
                Files.setPosixFilePermissions(registry.socketPath, PosixFilePermissions.fromString("rw-r--r--"))
                assertNull(registry.acquire())
                lease.publishReady(DaemonRegistration(123, identity))
            }
        }
    }

}

package dev.visiontest.daemon

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DaemonLauncherTest {
    @Test fun `production command uses explicit current Java classpath and hidden first argument`() {
        assertEquals(listOf(
            "/java/bin/java", "-cp", "a path/classes", "dev.visiontest.MainKt", "--internal-daemon-host",
        ),
            DaemonLauncher.command(Path.of("/java"), "a path/classes"))
    }
    @Test fun `concurrent startup waits converge on one lease owner`() {
        val root = java.nio.file.Files.createTempDirectory(Path.of("/tmp"), "vtl-")
        val registry = DaemonRegistry(root.resolve("run"))
        val source = root.resolve("LauncherFixture.java")
        java.nio.file.Files.writeString(source, """
            import java.nio.file.*;
            import java.nio.channels.*;
            import java.net.*;
            import java.nio.file.attribute.*;
            public class LauncherFixture {
              public static void main(String[] args) throws Exception {
                Path dir = Path.of(System.getenv("VISIONTEST_DAEMON_DIR"));
                try (FileChannel file = FileChannel.open(dir.resolve("lock"), StandardOpenOption.WRITE);
                     FileLock lock = file.tryLock()) {
                  if (lock == null) return;
                  try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                    Path socket = dir.resolve("s");
                    server.bind(UnixDomainSocketAddress.of(socket));
                    Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
                    Path meta = Files.createTempFile(dir, "ready", ".tmp");
                    Files.writeString(meta, "{\"pid\":" + ProcessHandle.current().pid() + ",\"protocol\":1,\"appVersion\":\"test\",\"build\":\"" + "a".repeat(64) + "\"}");
                    Files.setPosixFilePermissions(meta, PosixFilePermissions.fromString("rw-------"));
                    Files.move(meta, dir.resolve("ready.json"), StandardCopyOption.ATOMIC_MOVE);
                    long deadline = System.nanoTime() + 10_000_000_000L;
                    while (!Files.exists(dir.resolve("done")) && System.nanoTime() < deadline) Thread.sleep(10);
                    Files.deleteIfExists(dir.resolve("ready.json"));
                    Files.deleteIfExists(socket);
                  }
                }
              }
            }
        """.trimIndent())
        val command = listOf(Path.of(System.getProperty("java.home"), "bin/java").toString(), source.toString())
        val launcher = DaemonLauncher(registry, command, readinessTimeoutMillis = 10_000)
        val first = java.util.concurrent.CompletableFuture.supplyAsync { launcher.ensureRunning() }
        val second = java.util.concurrent.CompletableFuture.supplyAsync { launcher.ensureRunning() }
        try {
            assertEquals(first.get(12, java.util.concurrent.TimeUnit.SECONDS).pid,
                second.get(12, java.util.concurrent.TimeUnit.SECONDS).pid)
        } finally {
            java.nio.file.Files.writeString(registry.directory.resolve("done"), "")
            val deadline = System.nanoTime() + 3_000_000_000L
            while (java.nio.file.Files.exists(registry.metadataPath) && System.nanoTime() < deadline) Thread.sleep(10)
            root.toFile().deleteRecursively()
        }
    }
}

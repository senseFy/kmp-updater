package saien.updater.jvm

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import saien.updater.Checkpoint

class CheckpointProcessTest {
    @Test
    fun independentJvmProcessesSerializeTrustCheckpointTransactions() = runBlocking {
        val directory = Files.createTempDirectory("updater-process-test")
        val processes = mutableListOf<Process>()
        try {
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            repeat(4) { index ->
                processes +=
                    ProcessBuilder(
                            java,
                            "-cp",
                            System.getProperty("test.runtimeClasspath"),
                            "saien.updater.jvm.CheckpointWorker",
                            directory.toString(),
                        )
                        .redirectErrorStream(true)
                        .redirectOutput(directory.resolve("worker-$index.log").toFile())
                        .start()
            }
            processes.forEach {
                assertTrue(it.waitFor(30, TimeUnit.SECONDS), "Worker timed out")
                assertEquals(0, it.exitValue(), "Worker failed; inspect its log")
            }
            val final =
                FileCheckpointStore(directory.resolve("store")).transaction("sample/stable") {
                    checkNotNull(it)
                }
            assertEquals(80, final.sequence)
        } finally {
            processes
                .filter { it.isAlive }
                .forEach { it.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
            directory.toFile().deleteRecursively()
        }
    }
}

object CheckpointWorker {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val store = FileCheckpointStore(Path.of(args.single()).resolve("store"))
        repeat(20) {
            store.transaction("sample/stable") { previous ->
                Checkpoint((previous?.sequence ?: 0) + 1, "a".repeat(64), 1000)
            }
        }
    }
}

package saien.updater.jvm

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import saien.updater.PlatformInstaller
import saien.updater.PreparedInstallation
import saien.updater.UpdateErrorCode
import saien.updater.UpdateException
import saien.updater.VerifiedArtifact

/**
 * Directly distributed, signed/notarized macOS applications. The native helper must be embedded in
 * and signed with the application. No elevation, sandbox or PKG support.
 */
public class MacOsInstaller(
    application: Path,
    helper: Path,
    private val appId: String,
    private val teamId: String,
    private val installedSequence: Long,
    requestDirectory: Path,
) : PlatformInstaller {
    private val application = application.toRealPath()
    private val helper = helper.toRealPath()
    private val requests = privateDirectory(requestDirectory)
    override val artifactKinds: Set<String> = setOf("dmg")

    init {
        require(System.getProperty("os.name").startsWith("Mac"))
        require(this.application.fileName.toString().endsWith(".app"))
        require(
            this.helper.startsWith(this.application.resolve("Contents")) &&
                Files.isExecutable(this.helper)
        )
        require(appId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
        require(teamId.matches(Regex("[A-Z0-9]{10}")) && installedSequence >= 0)
    }

    override suspend fun prepare(artifact: VerifiedArtifact): PreparedInstallation {
        if (artifact.applicationId != appId || artifact.offer.artifact.kind != "dmg")
            unsupported("Artifact does not belong to this application/installer")
        val content =
            artifact.content as? JvmDownloadedArtifact ?: unsupported("Expected a JVM artifact")
        val id = UUID.randomUUID().toString()
        val directory = application.parent.resolve(".kmp-update-$appId-$id")
        var completed = false
        try {
            // Let helper preparation resolve ownership even if the caller cancels midway.
            withContext(NonCancellable + Dispatchers.IO) {
                content.verify()
                val request =
                    InstallRequest(
                        id,
                        application.toString(),
                        content.path.toString(),
                        content.size,
                        content.sha256,
                        appId,
                        teamId,
                        installedSequence,
                        artifact.offer.release.sequence,
                        ProcessHandle.current().pid(),
                    )
                val file = Files.createTempFile(requests, "request-", ".json")
                try {
                    Files.writeString(file, Json.encodeToString(request))
                    runHelper("prepare", file, 900)
                    completed = true
                } finally {
                    Files.deleteIfExists(file)
                }
            }
            currentCoroutineContext().ensureActive()
            return MacPreparedInstallation(directory, id, content)
        } catch (error: Exception) {
            if (completed) {
                try {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runHelper("discard", directory, 120)
                    }
                } catch (cleanup: Exception) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
    }

    /** Inspect a retained transaction after restart or a helper failure. */
    public suspend fun installationStatus(transactionId: String): String =
        withContext(Dispatchers.IO) {
            require(UUID.fromString(transactionId).toString() == transactionId.lowercase())
            runHelper(
                    "status",
                    application.parent.resolve(".kmp-update-$appId-$transactionId"),
                    120,
                )
                .trim()
        }

    /**
     * Call after the new application has started successfully to release the retained old bundle.
     */
    public suspend fun confirmInstallation(transactionId: String) {
        withContext(Dispatchers.IO) {
            require(UUID.fromString(transactionId).toString() == transactionId.lowercase())
            runHelper(
                "confirm",
                application.parent.resolve(".kmp-update-$appId-$transactionId"),
                120,
            )
        }
    }

    private inner class MacPreparedInstallation(
        private val directory: Path,
        private val id: String,
        private val content: JvmDownloadedArtifact,
    ) : PreparedInstallation {
        override suspend fun commit(): String =
            withContext(NonCancellable + Dispatchers.IO) {
                val accepted = directory.resolve("accepted")
                if (Files.exists(accepted))
                    throw UpdateException(
                        UpdateErrorCode.INSTALLATION,
                        "Transaction was already accepted; inspect status or discard before retrying",
                    )
                val log = directory.resolve("helper.log")
                val process =
                    ProcessBuilder(helper.toString(), "install", directory.toString())
                        .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start()
                var transferred = false
                try {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
                    while (System.nanoTime() < deadline) {
                        if (Files.exists(accepted)) {
                            if (Files.size(accepted) > 128 || Files.readString(accepted) != id)
                                throw UpdateException(
                                    UpdateErrorCode.INSTALLATION,
                                    "Invalid installer acknowledgement",
                                )
                            transferred = true
                            // The helper owns the staged app; a cache cleanup error must not undo
                            // acceptance.
                            try {
                                content.discard()
                            } catch (_: java.io.IOException) {
                                // A later cache cleanup may remove this unreferenced archive.
                            }
                            return@withContext id
                        }
                        if (!process.isAlive)
                            throw UpdateException(
                                UpdateErrorCode.INSTALLATION,
                                "Installer rejected handoff; see $log",
                            )
                        delay(50)
                    }
                    throw UpdateException(
                        UpdateErrorCode.INSTALLATION,
                        "Installer acknowledgement timed out",
                    )
                } finally {
                    if (!transferred) stop(process)
                }
            }

        override suspend fun discard() {
            withContext(Dispatchers.IO) {
                if (Files.exists(directory)) runHelper("discard", directory, 120)
            }
        }
    }

    private fun runHelper(command: String, path: Path, timeoutSeconds: Long): String {
        val output = Files.createTempFile(requests, "helper-", ".log")
        try {
            val process =
                ProcessBuilder(helper.toString(), command, path.toString())
                    .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start()
            try {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
                    throw UpdateException(
                        UpdateErrorCode.INSTALLATION,
                        "Native helper timed out; retain staging files for inspection",
                    )
                val text =
                    Files.newBufferedReader(output).use { reader ->
                        val buffer = CharArray(8192)
                        val count = reader.read(buffer)
                        if (count < 0) "" else String(buffer, 0, count)
                    }
                if (process.exitValue() != 0)
                    throw UpdateException(
                        UpdateErrorCode.INSTALLATION,
                        "Native helper failed: $text",
                    )
                return text
            } finally {
                if (process.isAlive) stop(process)
            }
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun stop(process: Process) {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun unsupported(message: String): Nothing =
        throw UpdateException(UpdateErrorCode.UNSUPPORTED_INSTALLATION, message)
}

@Serializable
private data class InstallRequest(
    val transactionId: String,
    val target: String,
    val archive: String,
    val size: Long,
    val sha256: String,
    val appId: String,
    val teamId: String,
    val installedSequence: Long,
    val releaseSequence: Long,
    val hostPid: Long,
)

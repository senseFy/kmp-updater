package saien.updater.lab

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import saien.updater.UpdateConfiguration
import saien.updater.UpdateException
import saien.updater.UpdateState
import saien.updater.Updater
import saien.updater.jvm.FileCheckpointStore
import saien.updater.jvm.HttpsUpdateTransport
import saien.updater.jvm.JcaCryptography
import saien.updater.jvm.MacOsInstaller

/** Explicit desktop wiring. All update decisions and operations go through the actual SDK. */
class LabController(private val application: Path?) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(LabState())
    val state = mutableState.asStateFlow()
    private val root = application?.parent
    private val properties = Properties()
    private var release = 0L
    private var transport: HttpsUpdateTransport? = null
    private lateinit var updater: Updater
    private lateinit var installer: MacOsInstaller
    private var onExit: () -> Unit = {}

    fun start(exit: () -> Unit) {
        onExit = exit
        if (application == null || root == null) return
        action {
            check(
                Files.readString(root.resolve(".updater-lab-fixture")) ==
                    "kmp-updater-acceptance-v1\n"
            )
            release =
                Files.readString(application.resolve("Contents/Resources/build.txt"))
                    .trim()
                    .toLong()
            Files.newInputStream(root.resolve("lab.properties")).use(properties::load)
            // Trust is confined to this fixture JVM; no system trust settings are modified.
            System.setProperty("javax.net.ssl.trustStore", properties.getProperty("trustStore"))
            System.setProperty("javax.net.ssl.trustStorePassword", "changeit")
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")
            val data = Files.createDirectories(root.resolve("state"))
            val sentinel = data.resolve("user-data.txt")
            if (release == 1L && !Files.exists(sentinel))
                Files.writeString(sentinel, properties.getProperty("sentinel"))
            check(Files.readString(sentinel) == properties.getProperty("sentinel")) {
                "User data changed"
            }
            record("boot", "pid=${ProcessHandle.current().pid()}")
            val network = HttpsUpdateTransport(data.resolve("downloads"), setOf("localhost"))
            transport = network
            installer =
                MacOsInstaller(
                    application,
                    application.resolve("Contents/Helpers/kmp-updater-fixture-helper"),
                    "saien.updater.lab",
                    "FIXTURE000",
                    release,
                    data.resolve("requests"),
                )
            updater =
                Updater(
                    UpdateConfiguration(
                        "saien.updater.lab",
                        "stable",
                        release,
                        "macos-aarch64",
                        System.getProperty("os.version"),
                        properties.getProperty("feed"),
                    ),
                    network,
                    JcaCryptography(mapOf("fixture" to properties.getProperty("publicKey"))),
                    FileCheckpointStore(data.resolve("checkpoints")),
                    network,
                    installer,
                )
            scope.launch {
                updater.state.collect { update ->
                    mutableState.update { current ->
                        current.copy(
                            release = release,
                            status = describe(update),
                            progress =
                                (update as? UpdateState.Downloading)?.let {
                                    it.bytes.toFloat() / it.offer.artifact.size
                                },
                            canCheck =
                                update is UpdateState.Idle ||
                                    update is UpdateState.UpToDate ||
                                    update is UpdateState.NoCompatibleUpdate,
                            canDownload = update is UpdateState.Available,
                            canInstall = update is UpdateState.Ready,
                        )
                    }
                }
            }
            if (release == 2L) {
                val pending = data.resolve("pending-transaction")
                val id = Files.readString(pending).trim()
                check(installer.installationStatus(id) == "installed")
                record("receipt", "installed")
                installer.confirmInstallation(id)
                Files.delete(pending)
                record("confirmed")
                record("data-preserved")
                // Let the initial StateFlow collection settle before displaying the result.
                delay(100)
                mutableState.update {
                    it.copy(status = "Update complete. Your data is unchanged.", canCheck = false)
                }
                result("success")
            } else if (properties.getProperty("automate") == "true") {
                delay(600)
                checkUpdate()
                downloadUpdate()
                installUpdate()
            }
        }
    }

    fun check() = action { checkUpdate() }

    fun download() = action { downloadUpdate() }

    fun install() = action { installUpdate() }

    private suspend fun checkUpdate() {
        check(updater.check() is UpdateState.Available) { "Expected release 2" }
        record("checked")
    }

    private suspend fun downloadUpdate() {
        updater.download()
        record("prepared")
    }

    private suspend fun installUpdate() {
        val accepted = updater.install()
        Files.writeString(root!!.resolve("state/pending-transaction"), accepted.transactionId)
        record("accepted", accepted.transactionId)
        if (properties.getProperty("vetoExit") == "true") {
            delay(3000)
            check(installer.installationStatus(accepted.transactionId) == "original-retained")
            record("exit-vetoed", "original-retained")
            result("original-retained")
        } else {
            record("normal-exit")
            withContext(Dispatchers.Main) { onExit() }
        }
    }

    private fun action(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val code = (error as? UpdateException)?.code?.name ?: "LAB_FAILURE"
                record("failure", "$code: ${error.message}")
                if (::updater.isInitialized) {
                    try {
                        updater.discard()
                    } catch (cleanup: Exception) {
                        record("cleanup-retained", cleanup.message.orEmpty())
                    }
                }
                mutableState.update {
                    it.copy(
                        status = "Update stopped: $code",
                        canCheck = false,
                        canDownload = false,
                        canInstall = false,
                    )
                }
                result("failure:$code")
            }
        }
    }

    @Synchronized
    private fun record(event: String, detail: String = "") {
        val message = "release=$release $event $detail".trim()
        root?.let { Files.writeString(it.resolve("app-events.log"), "$message\n", CREATE, APPEND) }
        mutableState.update {
            it.copy(release = release, events = (it.events + message).takeLast(6))
        }
    }

    private fun result(outcome: String) {
        root?.let { Files.writeString(it.resolve("result.txt"), "$release\n$outcome\n") }
    }

    override fun close() {
        scope.cancel()
        transport?.close()
    }
}

private fun describe(state: UpdateState): String =
    when (state) {
        UpdateState.Idle -> "Ready to check for updates."
        UpdateState.Checking -> "Checking the signed release feed…"
        UpdateState.UpToDate -> "You are up to date."
        UpdateState.NoCompatibleUpdate -> "No compatible update is available."
        is UpdateState.Available -> "Release ${state.offer.release.sequence} is available."
        is UpdateState.Downloading -> "Downloading and verifying the update…"
        is UpdateState.Preparing -> "Preparing the application…"
        is UpdateState.Ready -> "Ready to install. The app will close and reopen."
        is UpdateState.HandingOff -> "Handing the update to the installer…"
        is UpdateState.AwaitingExit -> "Installer accepted. Waiting for this app to close."
        is UpdateState.Failed -> "Update stopped: ${state.code}"
    }

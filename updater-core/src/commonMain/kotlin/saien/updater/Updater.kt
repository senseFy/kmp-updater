package saien.updater

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** One updater per application/channel. UI observes state and invokes commands explicitly. */
public class Updater(
    private val configuration: UpdateConfiguration,
    private val transport: ManifestTransport,
    private val cryptography: UpdateCryptography,
    private val checkpoints: CheckpointStore,
    private val downloader: ArtifactDownloader,
    private val installer: PlatformInstaller,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    public val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var offer: UpdateOffer? = null
    private var downloaded: DownloadedArtifact? = null
    private var prepared: PreparedInstallation? = null
    private var handedOff = false
    private var trustedTime = 0L

    /** Authenticate and select a compatible release. A manual check never downloads or installs. */
    public suspend fun check(): UpdateState =
        command(UpdateErrorCode.NETWORK) {
            requireNoHandoff()
            if (prepared != null || downloaded != null)
                fail(
                    UpdateErrorCode.INVALID_STATE,
                    "Discard owned update resources before checking again",
                )
            offer = null
            mutableState.value = UpdateState.Checking
            val bytes =
                transport.fetch(configuration.manifestUrl, ManifestCodec.MAXIMUM_ENVELOPE_BYTES)
            val (manifest, digest) = ManifestCodec.verify(bytes, cryptography)
            if (
                manifest.appId != configuration.appId || manifest.channel != configuration.channel
            ) {
                fail(
                    UpdateErrorCode.WRONG_SCOPE,
                    "Manifest belongs to a different application or channel",
                )
            }
            val now = clock.now().epochSeconds
            val checkpoint =
                checkpoints.transaction(configuration.scope) { previous ->
                    if (previous != null && now < previous.observedAt - 300)
                        fail(UpdateErrorCode.CLOCK_ROLLBACK, "Clock moved behind the checkpoint")
                    val effectiveNow = maxOf(now, previous?.observedAt ?: now)
                    if (manifest.issuedAt > effectiveNow + 300)
                        fail(UpdateErrorCode.INVALID_MANIFEST, "Manifest was issued in the future")
                    if (manifest.expiresAt <= effectiveNow)
                        fail(UpdateErrorCode.EXPIRED_MANIFEST, "Manifest expired")
                    if (previous != null && manifest.sequence < previous.sequence)
                        fail(UpdateErrorCode.MANIFEST_ROLLBACK, "Manifest sequence decreased")
                    if (
                        previous != null &&
                            manifest.sequence == previous.sequence &&
                            digest != previous.payloadDigest
                    ) {
                        fail(
                            UpdateErrorCode.MANIFEST_CONFLICT,
                            "The same manifest sequence has different content",
                        )
                    }
                    Checkpoint(manifest.sequence, digest, effectiveNow)
                }
            trustedTime = checkpoint.observedAt
            val newer = manifest.releases.filter { it.sequence > configuration.installedSequence }
            offer =
                newer
                    .sortedByDescending { it.sequence }
                    .firstNotNullOfOrNull { release ->
                        if (release.minimumInstalledSequence > configuration.installedSequence)
                            return@firstNotNullOfOrNull null
                        val candidates =
                            release.artifacts.filter {
                                it.target == configuration.target &&
                                    it.kind in installer.artifactKinds &&
                                    osAtLeast(configuration.osVersion, it.minimumOsVersion)
                            }
                        if (candidates.size > 1)
                            fail(
                                UpdateErrorCode.INVALID_MANIFEST,
                                "Multiple supported artifacts match this target",
                            )
                        candidates.singleOrNull()?.let {
                            UpdateOffer(release, it, manifest.expiresAt)
                        }
                    }
            mutableState.value =
                offer?.let(UpdateState::Available)
                    ?: if (newer.isEmpty()) UpdateState.UpToDate else UpdateState.NoCompatibleUpdate
            mutableState.value
        }

    /** Downloads, verifies and prepares. The running application is not replaced here. */
    public suspend fun download(): UpdateState.Ready =
        command(UpdateErrorCode.INSTALLATION) {
            requireNoHandoff()
            if (prepared != null || downloaded != null)
                fail(
                    UpdateErrorCode.INVALID_STATE,
                    "Discard owned update resources before downloading again",
                )
            val selected = offer ?: fail(UpdateErrorCode.INVALID_STATE, "Check for an update first")
            requireFresh(selected)
            mutableState.value = UpdateState.Downloading(selected, 0)
            try {
                val content =
                    downloader.download(selected.artifact) { bytes ->
                        mutableState.value =
                            UpdateState.Downloading(
                                selected,
                                bytes.coerceIn(0, selected.artifact.size),
                            )
                    }
                downloaded = content
                if (
                    content.size != selected.artifact.size ||
                        content.sha256 != selected.artifact.sha256
                ) {
                    fail(
                        UpdateErrorCode.ARTIFACT_INTEGRITY,
                        "Downloaded artifact does not match signed metadata",
                    )
                }
                requireFresh(selected)
                mutableState.value = UpdateState.Preparing(selected)
                prepared =
                    installer.prepare(VerifiedArtifact(configuration.appId, selected, content))
                UpdateState.Ready(selected).also { mutableState.value = it }
            } catch (error: Exception) {
                cleanup(error)
                throw error
            }
        }

    /** Returns an accepted handoff, after which the host can request normal application exit. */
    public suspend fun install(): UpdateState.AwaitingExit =
        command(UpdateErrorCode.INSTALLATION) {
            requireNoHandoff()
            val selected = offer ?: fail(UpdateErrorCode.INVALID_STATE, "No update selected")
            val installation =
                prepared ?: fail(UpdateErrorCode.INVALID_STATE, "Download and prepare first")
            requireFresh(selected)
            mutableState.value = UpdateState.HandingOff(selected)
            // Ownership transfer must resolve before cancellation can release the caller.
            withContext(NonCancellable) {
                val transactionId = installation.commit()
                handedOff = true
                UpdateState.AwaitingExit(transactionId).also { mutableState.value = it }
            }
        }

    /** Release pre-installation resources. It is an error to discard an accepted handoff. */
    public suspend fun discard(): Unit =
        command(UpdateErrorCode.STORAGE) {
            requireNoHandoff()
            cleanup()
            offer = null
            mutableState.value = UpdateState.Idle
        }

    private fun requireFresh(selected: UpdateOffer) {
        val now = clock.now().epochSeconds
        if (now < trustedTime - 300)
            fail(UpdateErrorCode.CLOCK_ROLLBACK, "Clock moved behind the verified offer")
        if (maxOf(now, trustedTime) >= selected.expiresAt)
            fail(
                UpdateErrorCode.EXPIRED_MANIFEST,
                "Check again before using an expired update offer",
            )
    }

    private fun requireNoHandoff() {
        if (handedOff) fail(UpdateErrorCode.INVALID_STATE, "An installer already owns this update")
    }

    private suspend fun cleanup(original: Exception? = null) {
        withContext(NonCancellable) {
            var failure: Exception? = original
            try {
                prepared?.discard()
                prepared = null
            } catch (error: Exception) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            try {
                downloaded?.discard()
                downloaded = null
            } catch (error: Exception) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            if (original == null) failure?.let { throw it }
        }
    }

    private suspend fun <T> command(fallback: UpdateErrorCode, block: suspend () -> T): T {
        if (!mutex.tryLock()) fail(UpdateErrorCode.BUSY, "Another update command is active")
        try {
            return block()
        } catch (error: CancellationException) {
            if (!handedOff)
                mutableState.value =
                    prepared?.let { UpdateState.Ready(checkNotNull(offer)) }
                        ?: offer?.let(UpdateState::Available)
                        ?: UpdateState.Idle
            throw error
        } catch (error: UpdateException) {
            if (!handedOff && error.code != UpdateErrorCode.INVALID_STATE)
                mutableState.value = UpdateState.Failed(error.code)
            throw error
        } catch (error: Exception) {
            if (!handedOff) mutableState.value = UpdateState.Failed(fallback)
            throw UpdateException(fallback, "Update operation failed", error)
        } finally {
            mutex.unlock()
        }
    }
}

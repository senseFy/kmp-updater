package saien.updater

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Workflow acceptance uses deterministic service doubles. Real Ed25519 is tested in updater-jvm.
 */
class StateMachineAcceptanceTest {
    @Test
    fun constructionAndCheckingNeverDownloadOrInstallImplicitly() = runTest {
        val rig = Rig()
        assertEquals(emptyList(), rig.events)
        assertIs<UpdateState.Available>(rig.updater.check())
        assertEquals(listOf("fetch", "checkpoint"), rig.events)
    }

    @Test
    fun selectsHighestEligibleReleaseRegardlessOfFeedOrderOrMarketingVersion() = runTest {
        val rig = Rig()
        rig.releases =
            listOf(
                rig.release.copy(
                    sequence = 10,
                    version = "100",
                    artifacts = listOf(rig.artifact.copy(target = "windows-x64")),
                ),
                rig.release.copy(sequence = 9, minimumInstalledSequence = 5),
                rig.release.copy(
                    sequence = 8,
                    artifacts = listOf(rig.artifact.copy(minimumOsVersion = "99")),
                ),
                rig.release.copy(sequence = 3, version = "999"),
                rig.release.copy(sequence = 4, version = "0.0.1"),
            )
        assertEquals(4, assertIs<UpdateState.Available>(rig.updater.check()).offer.release.sequence)
    }

    @Test
    fun distinguishesNoNewReleaseFromNoCompatibleArtifact() = runTest {
        val rig = Rig()
        rig.releases = listOf(rig.release.copy(sequence = 1))
        assertIs<UpdateState.UpToDate>(rig.updater.check())
        rig.releases = listOf(rig.release.copy(artifacts = listOf(rig.artifact.copy(kind = "msi"))))
        assertIs<UpdateState.NoCompatibleUpdate>(rig.updater.check())
        assertError(UpdateErrorCode.INVALID_STATE) { rig.updater.download() }
        assertTrue(rig.events.none { it == "download" || it == "commit" })
    }

    @Test
    fun invalidCommandsHaveNoEffects() = runTest {
        val rig = Rig()
        assertError(UpdateErrorCode.INVALID_STATE) { rig.updater.download() }
        assertError(UpdateErrorCode.INVALID_STATE) { rig.updater.install() }
        assertEquals(UpdateState.Idle, rig.updater.state.value)
        assertEquals(emptyList(), rig.events)
    }

    @Test
    fun simultaneousCommandsCannotMutateAnActiveCheck() = runTest {
        val rig = Rig()
        val resume = CompletableDeferred<Unit>()
        rig.fetchHook = { resume.await() }
        val check = launch(start = CoroutineStart.UNDISPATCHED) { rig.updater.check() }
        assertEquals(UpdateState.Checking, rig.updater.state.value)
        assertError(UpdateErrorCode.BUSY) { rig.updater.check() }
        assertError(UpdateErrorCode.BUSY) { rig.updater.download() }
        assertError(UpdateErrorCode.BUSY) { rig.updater.discard() }
        assertEquals(UpdateState.Checking, rig.updater.state.value)
        resume.complete(Unit)
        check.join()
        assertIs<UpdateState.Available>(rig.updater.state.value)
    }

    @Test
    fun failedOrCancelledRefreshCannotReuseTheOldOffer() = runTest {
        val rig = Rig()
        rig.updater.check()
        rig.fetchHook = { throw UpdateException(UpdateErrorCode.NETWORK, "offline") }
        assertError(UpdateErrorCode.NETWORK) { rig.updater.check() }
        assertError(UpdateErrorCode.INVALID_STATE) { rig.updater.download() }
        rig.fetchHook = { CompletableDeferred<Unit>().await() }
        val check = launch(start = CoroutineStart.UNDISPATCHED) { rig.updater.check() }
        check.cancelAndJoin()
        assertEquals(UpdateState.Idle, rig.updater.state.value)
        rig.fetchHook = {}
        assertIs<UpdateState.Available>(rig.updater.check())
    }

    @Test
    fun checkpointWriteFailurePreventsOfferingOrDownloadingAnUpdate() = runTest {
        val rig = Rig()
        rig.checkpointHook = { throw UpdateException(UpdateErrorCode.STORAGE, "disk full") }
        assertError(UpdateErrorCode.STORAGE) { rig.updater.check() }
        assertError(UpdateErrorCode.INVALID_STATE) { rig.updater.download() }
        assertEquals(listOf("fetch", "checkpoint"), rig.events)
        rig.checkpointHook = {}
        assertIs<UpdateState.Available>(rig.updater.check())
    }

    @Test
    fun preparationFailureCleansTheArtifactAndCanBeRetried() = runTest {
        val rig = Rig()
        rig.updater.check()
        rig.prepareHook = {
            throw UpdateException(UpdateErrorCode.UNSUPPORTED_INSTALLATION, "read only")
        }
        assertError(UpdateErrorCode.UNSUPPORTED_INSTALLATION) { rig.updater.download() }
        assertEquals(1, rig.artifacts.single().discards)
        assertTrue("commit" !in rig.events)
        rig.prepareHook = {}
        assertIs<UpdateState.Ready>(rig.updater.download())
    }

    @Test
    fun cancellationDuringPreparationCleansInputAndReleasesTheCommandLock() = runTest {
        val rig = Rig()
        rig.updater.check()
        rig.prepareHook = { CompletableDeferred<Unit>().await() }
        val download = launch(start = CoroutineStart.UNDISPATCHED) { rig.updater.download() }
        assertIs<UpdateState.Preparing>(rig.updater.state.value)
        download.cancelAndJoin()
        assertEquals(1, rig.artifacts.single().discards)
        assertIs<UpdateState.Available>(rig.updater.state.value)
        rig.prepareHook = {}
        assertIs<UpdateState.Ready>(rig.updater.download())
    }

    @Test
    fun signatureBoundArtifactMismatchNeverReachesTheInstaller() = runTest {
        val rig = Rig()
        rig.downloadDigest = "0".repeat(64)
        rig.updater.check()
        assertError(UpdateErrorCode.ARTIFACT_INTEGRITY) { rig.updater.download() }
        assertEquals(1, rig.artifacts.single().discards)
        assertTrue("prepare" !in rig.events)
    }

    @Test
    fun expiryDuringTransferPreventsPreparationAndReleasesTheDownload() = runTest {
        val rig = Rig()
        rig.updater.check()
        rig.downloadHook = { rig.now = 2000 }
        assertError(UpdateErrorCode.EXPIRED_MANIFEST) { rig.updater.download() }
        assertEquals(1, rig.artifacts.single().discards)
        assertTrue("prepare" !in rig.events)
    }

    @Test
    fun preparedUpdateMustStillBeFreshAndMayBeDiscardedWhenExpired() = runTest {
        val rig = Rig()
        rig.updater.check()
        rig.updater.download()
        rig.now = 2000
        assertError(UpdateErrorCode.EXPIRED_MANIFEST) { rig.updater.install() }
        rig.updater.discard()
        assertEquals(listOf("discard-prepared", "discard-artifact"), rig.events.takeLast(2))
        assertEquals(UpdateState.Idle, rig.updater.state.value)
        assertTrue("commit" !in rig.events)
    }

    @Test
    fun duplicateDownloadCannotReplaceAnExistingPreparation() = runTest {
        val rig = Rig()
        rig.updater.check()
        rig.updater.download()
        assertError(UpdateErrorCode.INVALID_STATE) { rig.updater.download() }
        assertError(UpdateErrorCode.INVALID_STATE) { rig.updater.check() }
        assertIs<UpdateState.Ready>(rig.updater.state.value)
        assertEquals(1, rig.artifacts.size)
    }

    @Test
    fun failedHandoffRetainsPreparationForAnExplicitRetry() = runTest {
        val rig = Rig()
        rig.updater.check()
        rig.updater.download()
        rig.commitHook = {
            throw UpdateException(UpdateErrorCode.INSTALLATION, "helper unavailable")
        }
        assertError(UpdateErrorCode.INSTALLATION) { rig.updater.install() }
        assertEquals(0, rig.artifacts.single().discards)
        rig.commitHook = {}
        assertEquals(UpdateState.AwaitingExit("accepted"), rig.updater.install())
    }

    @Test
    fun cancellationCannotUndoAnAcceptedHandoffOrDeleteInstallerInputs() = runTest {
        val rig = Rig()
        rig.updater.check()
        rig.updater.download()
        val resume = CompletableDeferred<Unit>()
        rig.commitHook = { resume.await() }
        val installation = launch(start = CoroutineStart.UNDISPATCHED) { rig.updater.install() }
        assertIs<UpdateState.HandingOff>(rig.updater.state.value)
        installation.cancel()
        resume.complete(Unit)
        installation.join()
        assertEquals(UpdateState.AwaitingExit("accepted"), rig.updater.state.value)
        assertError(UpdateErrorCode.INVALID_STATE) { rig.updater.discard() }
        assertError(UpdateErrorCode.INVALID_STATE) { rig.updater.check() }
        assertError(UpdateErrorCode.INVALID_STATE) { rig.updater.install() }
        assertEquals(0, rig.artifacts.single().discards)
        assertTrue("discard-prepared" !in rig.events)
    }

    @Test
    fun cleanupFailureDoesNotLoseTheResourceOrPermitItToBeOverwritten() = runTest {
        val rig = Rig()
        rig.updater.check()
        rig.prepareHook = { throw UpdateException(UpdateErrorCode.INSTALLATION, "prepare failed") }
        rig.discardArtifactHook = { throw UpdateException(UpdateErrorCode.STORAGE, "file busy") }
        val failure = assertFailsWith<UpdateException> { rig.updater.download() }
        assertEquals(1, failure.suppressedExceptions.size)
        assertError(UpdateErrorCode.INVALID_STATE) { rig.updater.check() }
        assertError(UpdateErrorCode.INVALID_STATE) { rig.updater.download() }
        rig.discardArtifactHook = {}
        rig.updater.discard()
        assertEquals(1, rig.artifacts.single().discards)
        assertEquals(UpdateState.Idle, rig.updater.state.value)
    }

    private suspend fun assertError(code: UpdateErrorCode, operation: suspend () -> Unit) {
        assertEquals(code, assertFailsWith<UpdateException> { operation() }.code)
    }

    private class Rig {
        val events = mutableListOf<String>()
        val artifacts = mutableListOf<Lease>()
        val artifact =
            Artifact("macos-aarch64", "dmg", "https://updates.example/app.dmg", 100, "a".repeat(64))
        val release = Release(2, "2", listOf(artifact))
        var releases = listOf(release)
        var now = 1500L
        var fetchHook: suspend () -> Unit = {}
        var checkpointHook: () -> Unit = {}
        var downloadHook: suspend () -> Unit = {}
        var prepareHook: suspend () -> Unit = {}
        var commitHook: suspend () -> Unit = {}
        var discardArtifactHook: () -> Unit = {}
        var downloadDigest = artifact.sha256
        private var payload = byteArrayOf()
        private var sequence = 0L
        val updater =
            Updater(
                UpdateConfiguration(
                    "sample.app",
                    "stable",
                    1,
                    artifact.target,
                    "26",
                    "https://updates.example/feed",
                ),
                ManifestTransport { _, _ ->
                    events += "fetch"
                    fetchHook()
                    payload =
                        ManifestCodec.encodePayload(
                            ReleaseManifest(
                                appId = "sample.app",
                                channel = "stable",
                                sequence = ++sequence,
                                issuedAt = 1000,
                                expiresAt = 2000,
                                releases = releases,
                            )
                        )
                    ManifestCodec.encodeEnvelope(
                        payload,
                        listOf(ManifestSignature("fixture", Base64.encode(ByteArray(64)))),
                    )
                },
                object : UpdateCryptography {
                    override fun verify(
                        keyId: String,
                        message: ByteArray,
                        signature: ByteArray,
                    ): Boolean =
                        keyId == "fixture" &&
                            message.contentEquals(ManifestCodec.signingBytes(payload)) &&
                            signature.contentEquals(ByteArray(64))

                    override fun sha256(bytes: ByteArray): String = "b".repeat(64)
                },
                object : CheckpointStore {
                    var value: Checkpoint? = null

                    override suspend fun transaction(
                        scope: String,
                        transform: (Checkpoint?) -> Checkpoint,
                    ): Checkpoint {
                        events += "checkpoint"
                        checkpointHook()
                        return transform(value).also { value = it }
                    }
                },
                object : ArtifactDownloader {
                    override suspend fun download(
                        artifact: Artifact,
                        progress: (Long) -> Unit,
                    ): DownloadedArtifact {
                        events += "download"
                        downloadHook()
                        return Lease(downloadDigest).also { artifacts += it }
                    }
                },
                object : PlatformInstaller {
                    override val artifactKinds = setOf("dmg")

                    override suspend fun prepare(artifact: VerifiedArtifact): PreparedInstallation {
                        events += "prepare"
                        prepareHook()
                        return object : PreparedInstallation {
                            override suspend fun commit(): String {
                                events += "commit"
                                commitHook()
                                return "accepted"
                            }

                            override suspend fun discard() {
                                events += "discard-prepared"
                            }
                        }
                    }
                },
                object : Clock {
                    override fun now(): Instant = Instant.fromEpochSeconds(now)
                },
            )

        inner class Lease(override val sha256: String) : DownloadedArtifact {
            override val size = 100L
            var discards = 0

            override suspend fun discard() {
                discardArtifactHook()
                discards++
                events += "discard-artifact"
            }
        }
    }
}

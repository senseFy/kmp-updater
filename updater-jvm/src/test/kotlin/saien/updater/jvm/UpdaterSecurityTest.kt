package saien.updater.jvm

import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import saien.updater.Artifact
import saien.updater.ArtifactDownloader
import saien.updater.Checkpoint
import saien.updater.CheckpointStore
import saien.updater.DownloadedArtifact
import saien.updater.ManifestCodec
import saien.updater.ManifestSignature
import saien.updater.ManifestTransport
import saien.updater.PlatformInstaller
import saien.updater.PreparedInstallation
import saien.updater.Release
import saien.updater.ReleaseManifest
import saien.updater.UpdateConfiguration
import saien.updater.UpdateErrorCode
import saien.updater.UpdateException
import saien.updater.UpdateState
import saien.updater.Updater
import saien.updater.VerifiedArtifact

class UpdaterSecurityTest {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val crypto =
        JcaCryptography(mapOf("primary" to Base64.getEncoder().encodeToString(pair.public.encoded)))
    private val bytes = "a complete release archive".toByteArray()
    private val artifact =
        Artifact(
            "macos-aarch64",
            "dmg",
            "https://updates.example/app.dmg",
            bytes.size.toLong(),
            crypto.sha256(bytes),
            "13.0",
        )
    private val manifest =
        ReleaseManifest(
            appId = "sample.app",
            channel = "stable",
            sequence = 5,
            issuedAt = 1000,
            expiresAt = 2000,
            releases = listOf(Release(2, "1.0", listOf(artifact))),
        )
    private val config =
        UpdateConfiguration(
            "sample.app",
            "stable",
            1,
            "macos-aarch64",
            "26.0",
            "https://updates.example/feed.json",
        )
    private var now = 1500L
    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochSeconds(now)
        }

    @Test
    fun signedUpdateReachesAcceptedHandoffWithoutClaimingInstallationSucceeded() = runTest {
        val installer = RecordingInstaller()
        val updater = updater(installer = installer)
        assertIs<UpdateState.Available>(updater.check())
        assertIs<UpdateState.Ready>(updater.download())
        assertEquals(0, installer.commits)
        assertEquals(UpdateState.AwaitingExit("transaction"), updater.install())
        assertEquals(1, installer.commits)
        assertError(UpdateErrorCode.INVALID_STATE) { updater.install() }
        assertError(UpdateErrorCode.INVALID_STATE) { updater.discard() }
        assertIs<UpdateState.AwaitingExit>(updater.state.value)
    }

    @Test
    fun alteredPayloadAndUnknownKeyCannotReachDownloader() = runTest {
        val payload = ManifestCodec.encodePayload(manifest)
        val signatures = listOf(signature(payload))
        val altered = payload.decodeToString().replace("sample.app", "attack.app").toByteArray()
        assertError(UpdateErrorCode.UNTRUSTED_SIGNATURE) {
            updater(feed = ManifestCodec.encodeEnvelope(altered, signatures)).check()
        }
        val unknown = listOf(signatures.single().copy(keyId = "unknown"))
        assertError(UpdateErrorCode.UNTRUSTED_SIGNATURE) {
            updater(feed = ManifestCodec.encodeEnvelope(payload, unknown)).check()
        }
    }

    @Test
    fun scopeExpiryFutureDateAndIncompatibleTargetAreHandledExplicitly() = runTest {
        assertError(UpdateErrorCode.WRONG_SCOPE) {
            updater(feed = sign(manifest.copy(channel = "beta"))).check()
        }
        assertError(UpdateErrorCode.EXPIRED_MANIFEST) {
            updater(feed = sign(manifest.copy(expiresAt = 1499))).check()
        }
        assertError(UpdateErrorCode.INVALID_MANIFEST) {
            updater(feed = sign(manifest.copy(issuedAt = 1900))).check()
        }
        val incompatible =
            manifest.copy(
                releases =
                    listOf(
                        manifest.releases
                            .single()
                            .copy(artifacts = listOf(artifact.copy(target = "windows-x64")))
                    )
            )
        assertIs<UpdateState.NoCompatibleUpdate>(updater(feed = sign(incompatible)).check())
        assertIs<UpdateState.UpToDate>(
            updater(configuration = config.copy(installedSequence = 2)).check()
        )
    }

    @Test
    fun monotonicCheckpointSurvivesNewUpdaterInstancesAndRejectsEquivocation() = runTest {
        val directory = Files.createTempDirectory("updater-checkpoint-test")
        try {
            updater(checkpoints = FileCheckpointStore(directory)).check()
            assertError(UpdateErrorCode.MANIFEST_ROLLBACK) {
                updater(
                        feed = sign(manifest.copy(sequence = 4)),
                        checkpoints = FileCheckpointStore(directory),
                    )
                    .check()
            }
            assertError(UpdateErrorCode.MANIFEST_CONFLICT) {
                updater(
                        feed = sign(manifest.copy(expiresAt = 2001)),
                        checkpoints = FileCheckpointStore(directory),
                    )
                    .check()
            }
            assertIs<UpdateState.Available>(
                updater(checkpoints = FileCheckpointStore(directory)).check()
            )
            now = 1100
            assertError(UpdateErrorCode.CLOCK_ROLLBACK) {
                updater(checkpoints = FileCheckpointStore(directory)).check()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun corruptArtifactCannotReachInstallationAndIsRemoved() = runTest {
        val content = MemoryArtifact(bytes.size.toLong(), "0".repeat(64))
        val installer = RecordingInstaller()
        val updater = updater(content = content, installer = installer)
        updater.check()
        assertError(UpdateErrorCode.ARTIFACT_INTEGRITY) { updater.download() }
        assertTrue(content.discarded)
        assertEquals(0, installer.preparations)
    }

    @Test
    fun cancellationReleasesPreparationInputAndAllowsRetry() = runTest {
        val entered = CompletableDeferred<Unit>()
        val wait = CompletableDeferred<Unit>()
        val content = MemoryArtifact(bytes.size.toLong(), crypto.sha256(bytes))
        val installer =
            object : PlatformInstaller {
                override val artifactKinds = setOf("dmg")

                override suspend fun prepare(artifact: VerifiedArtifact): PreparedInstallation {
                    entered.complete(Unit)
                    wait.await()
                    error("unreachable")
                }
            }
        val updater = updater(content = content, installer = installer)
        updater.check()
        val operation = launch { updater.download() }
        entered.await()
        assertError(UpdateErrorCode.BUSY) { updater.check() }
        operation.cancelAndJoin()
        assertTrue(content.discarded)
        assertIs<UpdateState.Available>(updater.state.value)
    }

    @Test
    fun expiryAndClockRollbackAreRecheckedBeforeInstallation() = runTest {
        val updater = updater()
        updater.check()
        updater.download()
        now = 2000
        assertError(UpdateErrorCode.EXPIRED_MANIFEST) { updater.install() }
        now = 1000
        assertError(UpdateErrorCode.CLOCK_ROLLBACK) { updater.install() }
    }

    @Test
    fun concurrentPersistentTransactionsDoNotLoseTheLatestCheckpoint() = runTest {
        val directory = Files.createTempDirectory("updater-store-test")
        try {
            val stores = List(8) { FileCheckpointStore(directory) }
            stores
                .map { store ->
                    async {
                        repeat(10) {
                            store.transaction("app/stable") {
                                Checkpoint((it?.sequence ?: 0) + 1, "a".repeat(64), 1000)
                            }
                        }
                    }
                }
                .forEach { it.await() }
            val final = stores.first().transaction("app/stable") { checkNotNull(it) }
            assertEquals(80, final.sequence)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun updater(
        feed: ByteArray = sign(manifest),
        checkpoints: CheckpointStore = MemoryCheckpoints(),
        installer: PlatformInstaller = RecordingInstaller(),
        content: MemoryArtifact = MemoryArtifact(bytes.size.toLong(), crypto.sha256(bytes)),
        configuration: UpdateConfiguration = config,
    ): Updater =
        Updater(
            configuration,
            ManifestTransport { _, _ -> feed },
            crypto,
            checkpoints,
            object : ArtifactDownloader {
                override suspend fun download(
                    artifact: Artifact,
                    progress: (Long) -> Unit,
                ): DownloadedArtifact = content
            },
            installer,
            clock,
        )

    private fun sign(value: ReleaseManifest): ByteArray {
        val payload = ManifestCodec.encodePayload(value)
        return ManifestCodec.encodeEnvelope(payload, listOf(signature(payload)))
    }

    private fun signature(payload: ByteArray): ManifestSignature {
        val bytes =
            Signature.getInstance("Ed25519").run {
                initSign(pair.private)
                update(ManifestCodec.signingBytes(payload))
                sign()
            }
        return ManifestSignature("primary", Base64.getEncoder().encodeToString(bytes))
    }

    private suspend fun assertError(code: UpdateErrorCode, block: suspend () -> Unit) {
        assertEquals(code, assertFailsWith<UpdateException> { block() }.code)
    }

    private class MemoryCheckpoints : CheckpointStore {
        private var value: Checkpoint? = null

        override suspend fun transaction(
            scope: String,
            transform: (Checkpoint?) -> Checkpoint,
        ): Checkpoint = transform(value).also { value = it }
    }

    private class MemoryArtifact(override val size: Long, override val sha256: String) :
        DownloadedArtifact {
        var discarded = false

        override suspend fun discard() {
            discarded = true
        }
    }

    private class RecordingInstaller : PlatformInstaller {
        var preparations = 0
        var commits = 0
        override val artifactKinds = setOf("dmg")

        override suspend fun prepare(artifact: VerifiedArtifact): PreparedInstallation {
            preparations++
            return object : PreparedInstallation {
                override suspend fun commit(): String {
                    commits++
                    return "transaction"
                }

                override suspend fun discard() = Unit
            }
        }
    }
}

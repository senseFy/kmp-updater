package consumer

import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking
import saien.updater.Artifact
import saien.updater.ArtifactDownloader
import saien.updater.DownloadedArtifact
import saien.updater.ManifestCodec
import saien.updater.ManifestSignature
import saien.updater.ManifestTransport
import saien.updater.PlatformInstaller
import saien.updater.PreparedInstallation
import saien.updater.UpdateConfiguration
import saien.updater.UpdateState
import saien.updater.Updater
import saien.updater.VerifiedArtifact
import saien.updater.jvm.FileCheckpointStore
import saien.updater.jvm.JcaCryptography

class PublishedApiTest {
    @Test
    fun externalClientVerifiesSignedFeedAndPersistsCheckpoint(): Unit = runBlocking {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val payload = releasePayload(Clock.System.now().epochSeconds)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keys.private)
        signer.update(ManifestCodec.signingBytes(payload))
        val envelope =
            ManifestCodec.encodeEnvelope(
                payload,
                listOf(
                    ManifestSignature("preview", Base64.getEncoder().encodeToString(signer.sign()))
                ),
            )
        val directory = Files.createTempDirectory("published-updater-checkpoint-")
        try {
            val updater =
                Updater(
                    configuration =
                        UpdateConfiguration(
                            "consumer.app",
                            "stable",
                            1,
                            "macos-aarch64",
                            "26.0",
                            "https://updates.example/feed.json",
                        ),
                    transport = ManifestTransport { _, _ -> envelope },
                    cryptography =
                        JcaCryptography(
                            mapOf(
                                "preview" to Base64.getEncoder().encodeToString(keys.public.encoded)
                            )
                        ),
                    checkpoints = FileCheckpointStore(directory),
                    downloader =
                        object : ArtifactDownloader {
                            override suspend fun download(
                                artifact: Artifact,
                                progress: (Long) -> Unit,
                            ): DownloadedArtifact = error("Checking must not download")
                        },
                    installer =
                        object : PlatformInstaller {
                            override val artifactKinds = setOf("dmg")

                            override suspend fun prepare(
                                artifact: VerifiedArtifact
                            ): PreparedInstallation = error("Checking must not install")
                        },
                )
            updater.check()
            val available = assertIs<UpdateState.Available>(updater.state.value)
            assertEquals(2, available.offer.release.sequence)
            // Reopen from disk through another SDK instance, not the original in-memory store.
            FileCheckpointStore(directory).transaction("consumer.app/stable") { checkpoint ->
                requireNotNull(checkpoint)
                assertEquals(1, checkpoint.sequence)
                checkpoint
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

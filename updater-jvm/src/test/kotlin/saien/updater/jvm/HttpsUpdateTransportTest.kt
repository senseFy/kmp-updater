package saien.updater.jvm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import saien.updater.Artifact
import saien.updater.UpdateErrorCode
import saien.updater.UpdateException

class HttpsUpdateTransportTest {
    private val bytes = ByteArray(140_000) { (it % 251).toByte() }
    private val artifact =
        Artifact(
            "macos-aarch64",
            "dmg",
            "https://updates.example/app.dmg",
            bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
        )

    @Test
    fun streamedDownloadMatchesSignedBytesAndDetectsLaterCacheMutation() = runTest {
        fixture { transport, _ ->
            val downloaded = transport.download(artifact) {} as JvmDownloadedArtifact
            assertContentEquals(bytes, Files.readAllBytes(downloaded.path))
            downloaded.verify()
            Files.write(downloaded.path, ByteArray(bytes.size))
            assertEquals(
                UpdateErrorCode.ARTIFACT_INTEGRITY,
                assertFailsWith<UpdateException> { downloaded.verify() }.code,
            )
            downloaded.discard()
        }
    }

    @Test
    fun oversizedChunkedResponseIsStoppedAndPartialFileRemoved() = runTest {
        fixture { transport, directory ->
            assertEquals(
                UpdateErrorCode.ARTIFACT_INTEGRITY,
                assertFailsWith<UpdateException> { transport.download(artifact.copy(size = 10)) {} }
                    .code,
            )
            Files.list(directory).use { assertEquals(0, it.count()) }
        }
    }

    @Test
    fun cancellationRemovesPartialDownload() = runTest {
        fixture { transport, directory ->
            assertFailsWith<CancellationException> {
                transport.download(artifact) { throw CancellationException("test cancellation") }
            }
            Files.list(directory).use { assertEquals(0, it.count()) }
        }
    }

    @Test
    fun shortDownloadAndDigestMismatchAreRejected() = runTest {
        fixture { transport, directory ->
            assertFailsWith<UpdateException> {
                transport.download(artifact.copy(size = bytes.size + 1L)) {}
            }
            assertFailsWith<UpdateException> {
                transport.download(artifact.copy(sha256 = "0".repeat(64))) {}
            }
            Files.list(directory).use { assertEquals(0, it.count()) }
        }
    }

    @Test
    fun manifestLimitIsEnforcedDuringReceiving() = runTest {
        fixture { transport, _ ->
            assertFailsWith<UpdateException> { transport.fetch(artifact.url, 256) }
        }
    }

    @Test
    fun redirectAndUnexpectedHostsAreRejected() = runTest {
        fixture(status = HttpStatusCode.Found) { transport, _ ->
            assertEquals(
                UpdateErrorCode.NETWORK,
                assertFailsWith<UpdateException> { transport.fetch(artifact.url, 256) }.code,
            )
            assertEquals(
                UpdateErrorCode.NETWORK,
                assertFailsWith<UpdateException> {
                        transport.fetch("https://attacker.example/feed", 256)
                    }
                    .code,
            )
        }
    }

    private suspend fun fixture(
        status: HttpStatusCode = HttpStatusCode.OK,
        block: suspend (HttpsUpdateTransport, java.nio.file.Path) -> Unit,
    ) {
        val directory = Files.createTempDirectory("updater-network-test")
        val engine = MockEngine {
            respond(
                ByteReadChannel(bytes),
                status,
                headersOf("Location", "https://attacker.example/"),
            )
        }
        val client =
            HttpClient(engine) {
                followRedirects = false
                install(HttpTimeout)
            }
        try {
            HttpsUpdateTransport(directory, setOf("updates.example"), client).use {
                block(it, directory)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

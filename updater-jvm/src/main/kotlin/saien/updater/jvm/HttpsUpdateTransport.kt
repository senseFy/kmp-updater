package saien.updater.jvm

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import saien.updater.Artifact
import saien.updater.ArtifactDownloader
import saien.updater.DownloadedArtifact
import saien.updater.ManifestTransport
import saien.updater.UpdateErrorCode
import saien.updater.UpdateException

/** HTTPS-only, bounded streaming, explicit host allowlist, no redirects or implicit retries. */
public class HttpsUpdateTransport
internal constructor(
    cacheDirectory: Path,
    private val allowedHosts: Set<String>,
    private val client: HttpClient,
) : ManifestTransport, ArtifactDownloader, AutoCloseable {
    public constructor(
        cacheDirectory: Path,
        allowedHosts: Set<String>,
    ) : this(
        cacheDirectory,
        allowedHosts,
        HttpClient(OkHttp) {
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
                requestTimeoutMillis = 30 * 60_000
            }
        },
    )

    private val root = privateDirectory(cacheDirectory)

    init {
        require(
            allowedHosts.isNotEmpty() &&
                allowedHosts.all { it == it.lowercase() && it.isNotBlank() }
        )
    }

    override suspend fun fetch(url: String, maximumBytes: Int): ByteArray {
        require(maximumBytes in 1..262_144)
        val output = ByteArrayOutputStream()
        receive(url, maximumBytes.toLong(), requestTimeoutMillis = 60_000) { bytes, count ->
            output.write(bytes, 0, count)
        }
        return output.toByteArray()
    }

    override suspend fun download(
        artifact: Artifact,
        progress: (Long) -> Unit,
    ): DownloadedArtifact {
        var acquired: JvmDownloadedArtifact? = null
        try {
            return withContext(Dispatchers.IO) {
                downloadToFile(artifact, progress).also { acquired = it }
            }
        } catch (error: Exception) {
            // withContext may cancel delivery after the IO block successfully acquired a file.
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    acquired?.discard()
                } catch (cleanup: Exception) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
    }

    private suspend fun downloadToFile(
        artifact: Artifact,
        progress: (Long) -> Unit,
    ): JvmDownloadedArtifact {
        require(artifact.size in 1..32L * 1024 * 1024 * 1024)
        if (Files.getFileStore(root).usableSpace < artifact.size)
            throw UpdateException(UpdateErrorCode.STORAGE, "Insufficient download space")
        val path = Files.createTempFile(root, "artifact-", ".partial")
        var success = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            Files.newOutputStream(path).use { output ->
                receive(artifact.url, artifact.size) { bytes, count ->
                    try {
                        output.write(bytes, 0, count)
                    } catch (error: java.io.IOException) {
                        throw UpdateException(
                            UpdateErrorCode.STORAGE,
                            "Cannot write downloaded artifact",
                            error,
                        )
                    }
                    digest.update(bytes, 0, count)
                    received += count
                    progress(received)
                }
            }
            val sha256 = digest.digest().toHex()
            if (received != artifact.size || sha256 != artifact.sha256) {
                throw UpdateException(
                    UpdateErrorCode.ARTIFACT_INTEGRITY,
                    "Artifact length or SHA-256 differs from signed metadata",
                )
            }
            return JvmDownloadedArtifact(path, received, sha256).also { success = true }
        } finally {
            if (!success) Files.deleteIfExists(path)
        }
    }

    private suspend fun receive(
        url: String,
        limit: Long,
        requestTimeoutMillis: Long = 30 * 60_000,
        consume: (ByteArray, Int) -> Unit,
    ) {
        validateUrl(url)
        try {
            client
                .prepareGet(url) { timeout { this.requestTimeoutMillis = requestTimeoutMillis } }
                .execute { response ->
                    if (response.status.value != 200)
                        throw UpdateException(
                            UpdateErrorCode.NETWORK,
                            "Expected HTTP 200, received ${response.status.value}",
                        )
                    val declared = response.headers["Content-Length"]?.toLongOrNull()
                    if (declared != null && (declared < 0 || declared > limit))
                        throw UpdateException(
                            UpdateErrorCode.ARTIFACT_INTEGRITY,
                            "Response exceeds the declared limit",
                        )
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val count = channel.readAvailable(buffer)
                        if (count == -1) break
                        received += count
                        if (received > limit)
                            throw UpdateException(
                                UpdateErrorCode.ARTIFACT_INTEGRITY,
                                "Response exceeded the byte limit",
                            )
                        consume(buffer, count)
                    }
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: UpdateException) {
            throw error
        } catch (error: java.io.IOException) {
            throw UpdateException(UpdateErrorCode.NETWORK, "Update transfer failed", error)
        }
    }

    private fun validateUrl(url: String) {
        val uri =
            try {
                URI(url)
            } catch (error: java.net.URISyntaxException) {
                throw UpdateException(UpdateErrorCode.NETWORK, "Invalid update URL", error)
            }
        if (
            uri.scheme != "https" ||
                uri.host?.lowercase() !in allowedHosts ||
                uri.userInfo != null ||
                uri.fragment != null
        ) {
            throw UpdateException(
                UpdateErrorCode.NETWORK,
                "Update URL is outside the configured HTTPS hosts",
            )
        }
    }

    override fun close(): Unit = client.close()
}

public class JvmDownloadedArtifact
internal constructor(
    public val path: Path,
    override val size: Long,
    override val sha256: String,
) : DownloadedArtifact {
    /** Recheck before extraction/use; cached paths are not proof of verification. */
    public fun verify() {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) != size) mismatch()
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                if (total > size) mismatch()
                digest.update(buffer, 0, count)
            }
            if (total != size || digest.digest().toHex() != sha256) mismatch()
        }
    }

    override suspend fun discard() {
        withContext(Dispatchers.IO) { Files.deleteIfExists(path) }
    }

    private fun mismatch(): Nothing =
        throw UpdateException(
            UpdateErrorCode.ARTIFACT_INTEGRITY,
            "Cached artifact changed after verification",
        )
}

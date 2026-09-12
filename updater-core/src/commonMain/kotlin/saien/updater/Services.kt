package saien.updater

/** Services are trusted implementations. Never implement verification by returning true. */
public interface UpdateCryptography {
    public fun verify(keyId: String, message: ByteArray, signature: ByteArray): Boolean

    public fun sha256(bytes: ByteArray): String
}

public fun interface ManifestTransport {
    /** Enforce this bound while receiving, before allocating the entire response. */
    public suspend fun fetch(url: String, maximumBytes: Int): ByteArray
}

public data class Checkpoint(
    val sequence: Long,
    val payloadDigest: String,
    val observedAt: Long,
)

public interface CheckpointStore {
    /** Serialize across instances/processes; persist atomically before returning. */
    public suspend fun transaction(
        scope: String,
        transform: (Checkpoint?) -> Checkpoint,
    ): Checkpoint
}

/** Platform-owned opaque artifact. The high-level API never accepts caller-supplied paths. */
public interface DownloadedArtifact {
    public val size: Long
    public val sha256: String

    public suspend fun discard()
}

public interface ArtifactDownloader {
    /** Stream into private temporary storage. On failure/cancellation, remove partial output. */
    public suspend fun download(artifact: Artifact, progress: (Long) -> Unit): DownloadedArtifact
}

public class VerifiedArtifact
internal constructor(
    public val applicationId: String,
    public val offer: UpdateOffer,
    public val content: DownloadedArtifact,
)

public interface PlatformInstaller {
    public val artifactKinds: Set<String>

    /** Isolate and validate incoming content without replacing the running application. */
    public suspend fun prepare(artifact: VerifiedArtifact): PreparedInstallation
}

public interface PreparedInstallation {
    /**
     * Revalidate and hand off to an independent installer. Return only after acceptance. On failure
     * no installer may remain committed to replacing the app. This operation must have a bounded
     * timeout and tolerate caller cancellation by completing or aborting the handoff before
     * returning.
     */
    public suspend fun commit(): String

    /** Only called before handoff. Idempotent; do not delete a running installer's inputs. */
    public suspend fun discard()
}

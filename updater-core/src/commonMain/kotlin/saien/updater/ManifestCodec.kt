package saien.updater

import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable public data class ManifestSignature(val keyId: String, val signature: String)

@Serializable
private data class ManifestEnvelope(
    val payload: String,
    val signatures: List<ManifestSignature>,
)

/**
 * Sign exact payload bytes with domain separation; never parse and reserialize for verification.
 */
public object ManifestCodec {
    public const val MAXIMUM_ENVELOPE_BYTES: Int = 262_144
    public const val MAXIMUM_PAYLOAD_BYTES: Int = 190_000
    private val json = Json { encodeDefaults = true }
    private val domain = "kmp-updater:manifest:v1\n".encodeToByteArray()

    public fun signingBytes(payload: ByteArray): ByteArray = domain + payload

    public fun encodePayload(manifest: ReleaseManifest): ByteArray {
        validate(manifest)
        return json.encodeToString(manifest).encodeToByteArray().also {
            require(it.size <= MAXIMUM_PAYLOAD_BYTES)
        }
    }

    public fun encodeEnvelope(payload: ByteArray, signatures: List<ManifestSignature>): ByteArray {
        require(payload.size <= MAXIMUM_PAYLOAD_BYTES && signatures.size in 1..8)
        return json
            .encodeToString(ManifestEnvelope(Base64.encode(payload), signatures))
            .encodeToByteArray()
            .also {
                require(it.size <= MAXIMUM_ENVELOPE_BYTES)
            }
    }

    public fun decodePayload(payload: ByteArray): ReleaseManifest = parse {
        if (payload.size > MAXIMUM_PAYLOAD_BYTES) invalid("Payload is too large")
        json.decodeFromString<ReleaseManifest>(decodeUtf8(payload)).also(::validate)
    }

    internal fun verify(
        envelopeBytes: ByteArray,
        crypto: UpdateCryptography,
    ): Pair<ReleaseManifest, String> = parse {
        if (envelopeBytes.size > MAXIMUM_ENVELOPE_BYTES) invalid("Envelope is too large")
        val envelope = json.decodeFromString<ManifestEnvelope>(decodeUtf8(envelopeBytes))
        if (envelope.signatures.size !in 1..8) invalid("Invalid signature count")
        if (envelope.signatures.map { it.keyId }.distinct().size != envelope.signatures.size)
            invalid("Duplicate signing key")
        val payload = Base64.decode(envelope.payload)
        if (payload.size > MAXIMUM_PAYLOAD_BYTES) invalid("Payload is too large")
        val message = signingBytes(payload)
        val signatures =
            envelope.signatures.map {
                if (!validIdentifier(it.keyId)) invalid("Invalid key identifier")
                val signature = Base64.decode(it.signature)
                if (signature.size != 64) invalid("Invalid Ed25519 signature size")
                it.keyId to signature
            }
        val trusted = signatures.any { (keyId, signature) ->
            crypto.verify(keyId, message, signature)
        }
        if (!trusted)
            fail(UpdateErrorCode.UNTRUSTED_SIGNATURE, "No pinned key verified the manifest")
        decodePayload(payload) to crypto.sha256(payload)
    }

    private fun validate(manifest: ReleaseManifest) {
        if (manifest.schema != 1) invalid("Unsupported schema")
        if (!validIdentifier(manifest.appId) || !validIdentifier(manifest.channel))
            invalid("Invalid scope")
        if (
            manifest.sequence <= 0 ||
                manifest.issuedAt < 0 ||
                manifest.expiresAt <= manifest.issuedAt
        )
            invalid("Invalid lifetime or sequence")
        if (manifest.releases.size > 100) invalid("Too many releases")
        if (manifest.releases.map { it.sequence }.distinct().size != manifest.releases.size)
            invalid("Duplicate release sequence")
        manifest.releases.forEach { release ->
            if (
                release.sequence <= 0 ||
                    release.minimumInstalledSequence < 0 ||
                    release.minimumInstalledSequence >= release.sequence
            )
                invalid("Invalid release order")
            if (release.version.isBlank() || release.version.length > 128)
                invalid("Invalid display version")
            if (release.artifacts.size !in 1..32) invalid("Invalid artifact count")
            if (
                release.artifacts.map { it.target to it.kind }.distinct().size !=
                    release.artifacts.size
            )
                invalid("Ambiguous artifact")
            if (
                release.notes.size > 32 ||
                    release.notes.any { !validIdentifier(it.key) || it.value.length > 16_384 }
            )
                invalid("Invalid release notes")
            release.artifacts.forEach { artifact ->
                if (!validIdentifier(artifact.target) || !validIdentifier(artifact.kind))
                    invalid("Invalid artifact target")
                if (!isHttpsUrl(artifact.url)) invalid("Artifact URL must use HTTPS")
                if (artifact.size <= 0 || artifact.size > 32L * 1024 * 1024 * 1024)
                    invalid("Invalid artifact size")
                if (!artifact.sha256.matches(Regex("[a-f0-9]{64}"))) invalid("Invalid SHA-256")
                if (parseOsVersion(artifact.minimumOsVersion) == null)
                    invalid("Invalid minimum OS version")
            }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val text = bytes.decodeToString()
        if (!text.encodeToByteArray().contentEquals(bytes)) invalid("Invalid UTF-8")
        return text
    }

    private inline fun <T> parse(block: () -> T): T =
        try {
            block()
        } catch (error: SerializationException) {
            throw UpdateException(UpdateErrorCode.INVALID_MANIFEST, "Invalid manifest JSON", error)
        } catch (error: IllegalArgumentException) {
            throw UpdateException(
                UpdateErrorCode.INVALID_MANIFEST,
                "Invalid manifest encoding",
                error,
            )
        }

    private fun invalid(message: String): Nothing = fail(UpdateErrorCode.INVALID_MANIFEST, message)
}

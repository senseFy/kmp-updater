package saien.updater.publisher

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import saien.updater.Artifact
import saien.updater.ManifestCodec
import saien.updater.ManifestSignature
import saien.updater.ReleaseManifest

public object ManifestSigner {
    public fun sign(manifest: ReleaseManifest, keys: Map<String, PrivateKey>): ByteArray {
        require(keys.isNotEmpty() && keys.size <= 8)
        val payload = ManifestCodec.encodePayload(manifest)
        val message = ManifestCodec.signingBytes(payload)
        val signatures = keys.map { (id, key) ->
            require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
            val signature =
                Signature.getInstance("Ed25519").run {
                    initSign(key)
                    update(message)
                    sign()
                }
            ManifestSignature(id, Base64.getEncoder().encodeToString(signature))
        }
        return ManifestCodec.encodeEnvelope(payload, signatures)
    }
}

/** Offline only. No network, key discovery, uploads, or shell execution. */
public fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "artifact" -> {
            require(args.size == 5) { "artifact <file> <https-url> <target> <kind>" }
            val file = Path.of(args[1])
            require(Files.isRegularFile(file))
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    size += count
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            println(Json.encodeToString(Artifact(args[3], args[4], args[2], size, hash)))
        }
        "keygen" -> {
            require(args.size == 2) { "keygen <new-private-key-file>" }
            val destination = Path.of(args[1]).toAbsolutePath()
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val attributes =
                if (Files.getFileStore(destination.parent).supportsFileAttributeView("posix")) {
                    arrayOf(
                        PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")
                        )
                    )
                } else {
                    emptyArray()
                }
            Files.newByteChannel(destination, setOf(CREATE_NEW, WRITE), *attributes).use { output ->
                val buffer =
                    java.nio.ByteBuffer.wrap(Base64.getEncoder().encode(pair.private.encoded))
                while (buffer.hasRemaining()) output.write(buffer)
            }
            println(Base64.getEncoder().encodeToString(pair.public.encoded))
        }
        "sign" -> {
            require(args.size >= 5 && args.size % 2 == 1) {
                "sign <payload.json> <new-output.json> <key-id> <private-key-file> [<key-id> <private-key-file> ...]"
            }
            val source = Path.of(args[1])
            require(Files.size(source) <= ManifestCodec.MAXIMUM_PAYLOAD_BYTES)
            val manifest = ManifestCodec.decodePayload(Files.readAllBytes(source))
            val keys = linkedMapOf<String, PrivateKey>()
            args.drop(3).chunked(2).forEach { (id, file) ->
                require(id !in keys) { "Duplicate signing key identifier" }
                val path = Path.of(file)
                require(Files.size(path) <= 4096)
                val bytes = Base64.getDecoder().decode(Files.readString(path).trim())
                keys[id] =
                    KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(bytes))
            }
            Files.write(Path.of(args[2]), ManifestSigner.sign(manifest, keys), CREATE_NEW, WRITE)
            println("Signed manifest written to ${args[2]}")
        }
        else ->
            error(
                "Commands: artifact, keygen, sign. Existing key/output files are never overwritten."
            )
    }
}

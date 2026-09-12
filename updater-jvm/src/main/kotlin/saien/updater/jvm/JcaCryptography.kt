package saien.updater.jvm

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import saien.updater.UpdateCryptography

/** Pinned Ed25519 public keys in base64 X.509 SubjectPublicKeyInfo encoding. */
public class JcaCryptography(keys: Map<String, String>) : UpdateCryptography {
    private val publicKeys: Map<String, PublicKey> = keys.mapValues { (_, encoded) ->
        KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
    }

    init {
        require(publicKeys.isNotEmpty()) { "At least one pinned key is required" }
    }

    override fun verify(keyId: String, message: ByteArray, signature: ByteArray): Boolean {
        val key = publicKeys[keyId] ?: return false
        if (signature.size != 64) return false
        return try {
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        } catch (_: java.security.SignatureException) {
            false
        }
    }

    override fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

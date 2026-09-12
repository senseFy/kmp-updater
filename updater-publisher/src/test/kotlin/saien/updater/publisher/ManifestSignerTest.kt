package saien.updater.publisher

import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import saien.updater.ManifestCodec
import saien.updater.ReleaseManifest

class ManifestSignerTest {
    @Test
    fun rotatedKeysSignTheSameExactPayload() {
        val keys =
            (1..2).associate {
                "key-$it" to KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            }
        val manifest =
            ReleaseManifest(
                appId = "sample.app",
                channel = "stable",
                sequence = 1,
                issuedAt = 100,
                expiresAt = 200,
                releases = emptyList(),
            )
        val signed = ManifestSigner.sign(manifest, keys.mapValues { it.value.private })
        val envelope = Json.parseToJsonElement(signed.decodeToString()).jsonObject
        val payload = Base64.getDecoder().decode(envelope.getValue("payload").jsonPrimitive.content)
        assertEquals(manifest, ManifestCodec.decodePayload(payload))
        val signatures = envelope.getValue("signatures").jsonArray
        assertEquals(2, signatures.size)
        signatures.forEach { item ->
            val signature = item.jsonObject
            val id = signature.getValue("keyId").jsonPrimitive.content
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(keys.getValue(id).public)
            verifier.update(ManifestCodec.signingBytes(payload))
            assertTrue(
                verifier.verify(
                    Base64.getDecoder()
                        .decode(signature.getValue("signature").jsonPrimitive.content)
                )
            )
        }
    }

    @Test
    fun keyGenerationNeverOverwritesAnExistingKey() {
        val directory = Files.createTempDirectory("updater-publisher-test")
        try {
            val key = directory.resolve("release.private-key")
            Files.writeString(key, "retain this key")
            assertFailsWith<java.nio.file.FileAlreadyExistsException> {
                main(arrayOf("keygen", key.toString()))
            }
            assertEquals("retain this key", Files.readString(key))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

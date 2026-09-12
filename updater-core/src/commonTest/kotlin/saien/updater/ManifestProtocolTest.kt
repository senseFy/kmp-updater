package saien.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManifestProtocolTest {
    private val artifact =
        Artifact("macos-aarch64", "dmg", "https://updates.example/app.dmg", 100, "a".repeat(64))
    private val manifest =
        ReleaseManifest(
            appId = "sample.app",
            channel = "stable",
            sequence = 1,
            issuedAt = 100,
            expiresAt = 200,
            releases = listOf(Release(2, "v2-beta", listOf(artifact))),
        )

    @Test
    fun multilingualNotesAndDisplayVersionRoundTripWithoutAffectingReleaseOrder() {
        val release =
            manifest.releases
                .single()
                .copy(notes = mapOf("en" to "A new release", "zh-CN" to "修复更新流程"))
        val value = manifest.copy(releases = listOf(release))
        assertEquals(value, ManifestCodec.decodePayload(ManifestCodec.encodePayload(value)))
    }

    @Test
    fun unknownSchemaAndMalformedUtf8AreRejected() {
        assertFailsWith<UpdateException> { ManifestCodec.encodePayload(manifest.copy(schema = 2)) }
        assertFailsWith<UpdateException> {
            ManifestCodec.decodePayload(byteArrayOf(0xc3.toByte(), 0x28))
        }
    }

    @Test
    fun ambiguousArtifactsAndInsecureUrlsAreRejected() {
        assertFailsWith<UpdateException> {
            ManifestCodec.encodePayload(
                manifest.copy(releases = listOf(Release(2, "2", listOf(artifact, artifact))))
            )
        }
        listOf(
                "http://updates.example/app.dmg",
                "https://user:pass@updates.example/app.dmg",
                "https://updates.example/app.dmg#fragment",
            )
            .forEach { url ->
                assertFailsWith<UpdateException> {
                    ManifestCodec.encodePayload(
                        manifest.copy(
                            releases = listOf(Release(2, "2", listOf(artifact.copy(url = url))))
                        )
                    )
                }
            }
    }

    @Test
    fun invalidLengthsDigestsAndReleaseOrdersAreRejected() {
        listOf(
                artifact.copy(size = 0),
                artifact.copy(sha256 = "bad"),
                artifact.copy(minimumOsVersion = "13.beta"),
            )
            .forEach { item ->
                assertFailsWith<UpdateException> {
                    ManifestCodec.encodePayload(
                        manifest.copy(releases = listOf(Release(2, "2", listOf(item))))
                    )
                }
            }
        assertFailsWith<UpdateException> {
            ManifestCodec.encodePayload(
                manifest.copy(
                    releases =
                        listOf(Release(2, "2", listOf(artifact), minimumInstalledSequence = 2))
                )
            )
        }
    }

    @Test
    fun osVersionsUseNumericComponentsAndZeroPadding() {
        assertTrue(osAtLeast("13", "13.0.0"))
        assertTrue(osAtLeast("13.10", "13.9"))
        assertFalse(osAtLeast("13.9", "13.10"))
        assertFalse(osAtLeast("13", "14"))
    }
}

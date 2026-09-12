package consumer

import saien.updater.Artifact
import saien.updater.ManifestCodec
import saien.updater.Release
import saien.updater.ReleaseManifest

fun releasePayload(now: Long): ByteArray =
    ManifestCodec.encodePayload(
        ReleaseManifest(
            appId = "consumer.app",
            channel = "stable",
            sequence = 1,
            issuedAt = now - 10,
            expiresAt = now + 3600,
            releases =
                listOf(
                    Release(
                        sequence = 2,
                        version = "2.0",
                        artifacts =
                            listOf(
                                Artifact(
                                    "macos-aarch64",
                                    "dmg",
                                    "https://updates.example/app.dmg",
                                    100,
                                    "a".repeat(64),
                                )
                            ),
                    )
                ),
        )
    )

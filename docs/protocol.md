# Manifest protocol v1

The runtime accepts one bounded JSON envelope. Its payload is base64-encoded UTF-8 JSON. Signatures authenticate `UTF8("kmp-updater:manifest:v1\n") || payloadBytes` using Ed25519. Base64 uses the standard alphabet. The digest is lowercase SHA-256 hex.

```json
{
  "payload": "BASE64_OF_PAYLOAD_BYTES",
  "signatures": [
    { "keyId": "release-2026", "signature": "BASE64_ED25519_SIGNATURE" }
  ]
}
```

The envelope limit is 262,144 bytes, enforced while receiving. The decoded payload limit is 190,000 bytes. There must be 1–8 signatures with distinct key IDs. At least one must verify against the application's pinned keys. The JVM public-key encoding is base64 X.509 SubjectPublicKeyInfo; private signing keys use base64 PKCS#8. Private keys never belong in the client or update hosting.

Example decoded payload:

```json
{
  "schema": 1,
  "appId": "com.example.app",
  "channel": "stable",
  "sequence": 21,
  "issuedAt": 1789171200,
  "expiresAt": 1791763200,
  "releases": [
    {
      "sequence": 12,
      "version": "0.2.11",
      "minimumInstalledSequence": 0,
      "notes": { "en": "Improved startup.", "zh-CN": "优化启动体验。" },
      "artifacts": [
        {
          "target": "macos-aarch64",
          "kind": "dmg",
          "url": "https://updates.example/app-12-arm64.dmg",
          "size": 123456,
          "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "minimumOsVersion": "13.0"
        }
      ]
    }
  ]
}
```

The URL, size and digest above are illustrative. Generate real artifact fields with the publisher CLI. Refresh timestamps when authoring a release.

| Field | Contract |
| --- | --- |
| `schema` | Exactly 1; incompatible schemas fail closed |
| `appId`, `channel` | Must match the local updater configuration exactly |
| Manifest `sequence` | Positive integer, increased whenever any payload bytes change, including an expiry refresh |
| `issuedAt`, `expiresAt` | UTC epoch seconds; finite valid interval; the client rejects expired or excessively future metadata |
| Release `sequence` | Positive, unique and increasing across the application's releases; separate from manifest sequence |
| `version` | Display text only, at most 128 characters |
| `minimumInstalledSequence` | Minimum installed release allowed to update directly; zero means no additional floor |
| `target` | Exact runtime/artifact identifier; initial macOS convention is `macos-aarch64` or `macos-x64` |
| `kind` | A format understood by the configured installer, initially `dmg` |
| `minimumOsVersion` | One to four numeric components, compared numerically with missing components treated as zero |
| `size`, `sha256` | Exact full artifact bytes, maximum 32 GiB; both checked before extraction |
| `notes` | Up to 32 locale entries, at most 16,384 characters each; plain text or host-sanitized Markdown, never executable HTML |

Application, channel, target, kind, key and locale identifiers use `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`. At most 100 releases and 32 artifacts per release are accepted. Duplicate release numbers or target/kind pairs are invalid. Unknown JSON fields are rejected by v1. Do not append unpublished extensions to a v1 feed.

## Selection and replay

1. Bound and decode the envelope; authenticate the payload bytes before interpreting release fields.
2. Validate schema, field bounds, application/channel scope and time.
3. Under a persistent per-scope transaction, reject a lower manifest sequence or a different payload digest at the same sequence. Save the new checkpoint before offering an update.
4. Consider releases strictly newer than the installed sequence. Filter minimum installed release, target, minimum OS and the installer's supported formats.
5. Select the highest eligible release. Multiple supported artifacts for that release/target are an error; the client does not guess a preferred format.
6. Recheck offer expiry before download and installation. The selected artifact must still match its signed length and digest.

A valid feed with newer but incompatible releases yields `NoCompatibleUpdate`. A feed with no newer releases yields `UpToDate`. An empty release list is permitted, for example when a channel is temporarily withdrawn.

The clock tolerance is 300 seconds. Accepted time never moves backwards in the checkpoint. A local clock more than 300 seconds behind the persisted checkpoint fails; within tolerance the persisted time remains the floor. Removing local state weakens replay protection, and clock correctness remains a deployment assumption.

## Key transition

The envelope supports multiple signatures for planned overlap, not a threshold quorum. Distribute a release that pins both old and new keys, sign feeds with both while old clients remain supported, and later stop trusting the old key in newly distributed clients. Renewed feeds must also remain usable by whichever old client versions the publisher still supports.

This protocol has no unsigned fallback, online root replacement, delegated signing roles or emergency recovery from loss of the sole trusted key. Those are deliberate limits, not equivalent guarantees to Sparkle's platform-specific certificate fallback or TUF's root rotation.

## Publishing order

Build and platform-sign the application, finalize/notarize its distribution artifact, then calculate its digest and length. Generate and sign the manifest only after artifact bytes are final. Upload immutable artifacts first and atomically replace the manifest last. Any later stapling, recompression or modification of an artifact requires new artifact metadata and a newly signed manifest.

The HTTP implementation accepts only HTTPS URLs on an explicit host allowlist and rejects redirects. Use final CDN URLs. Publish a fresh signed manifest before the current one expires, even when there is no application release.

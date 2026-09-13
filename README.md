# KMP Updater

A Kotlin Multiplatform updater with signed release feeds, verified downloads and a small UI-independent API. Inspired by Sparkle, with a shared update engine and platform-specific installation.

**Early preview, `0.1.0-alpha.1`.** The first release focuses on macOS/JVM. Developer ID signed and Apple-notarized app upgrades have passed on ARM64. Try a complete isolated upgrade with [Updater Lab](samples/updater-lab/README.md).

## Install

The SDK is available from Maven Central. In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

Add the SDK in your module's `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.sensefy:updater-core:0.1.0-alpha.1")
        }
        jvmMain.dependencies {
            implementation("io.github.sensefy:updater-jvm:0.1.0-alpha.1")
        }
    }
}
```

Use JDK 21 and Kotlin 2.4.0 or newer. [GitHub Releases](https://github.com/senseFy/kmp-updater/releases) provides the publisher CLI, a universal macOS helper, a complete Maven repository ZIP, and `SHA256SUMS`. Embed and sign the helper as described [below](#macos-packaging-contract).

## Platforms

| Platform | Current support |
| --- | --- |
| macOS / JVM, directly distributed apps | DMG download, verification, installation and relaunch; ad-hoc and Developer ID/notarized app acceptance passed on ARM64 |
| Windows / Linux | Shared core and JVM services; installers are not implemented yet |
| Android / iOS | Future adapters for platform-managed updates; not included in the first release |

The core targets JVM and desktop Kotlin/Native. Installation follows the distribution channel: desktop installers replace the app bundle, while store adapters would request the store's update flow. See [platform integration](docs/architecture.md#platform-integration) for the Android and iOS paths.

## Structure

| Component | Implemented scope |
| --- | --- |
| `updater-core` | Authenticated manifest selection, replay/expiry checks, observable state, cancellation and installation contracts; JVM and desktop Kotlin/Native targets |
| `updater-jvm` | Ed25519/SHA-256 through JCA, bounded HTTPS downloads, persistent checkpoints, macOS helper adapter |
| `updater-publisher` | Offline key generation, artifact metadata and multi-key manifest signing |
| `native/macos` | DMG validation/staging, Developer ID identity checks, exact-process exit wait, atomic bundle exchange, status inspection and old-version retention |
| `samples/updater-lab` | Independent Compose app and local old-to-new upgrade acceptance, generated with create-kmp-app |

- [Architecture](docs/architecture.md)
- [Pinned Sparkle source study](docs/sparkle-study.md)
- [Manifest protocol and release publishing](docs/protocol.md)
- [Tests and acceptance results](docs/verification.md)
- [Try an isolated app upgrade](samples/updater-lab/README.md)
- [SDK release workflow](docs/releasing.md)

## Build and verify

Use JDK 21 and the Gradle wrapper. Swift/Xcode is required only for the macOS helper.

```sh
./gradlew verify :updater-publisher:installDist
./gradlew :updater-core:macosArm64Test
swift test --package-path native/macos
swift build --package-path native/macos -c release --product kmp-updater-helper
```

Run `./gradlew spotlessApply` to format Kotlin and Gradle files. Swift uses `xcrun swift-format` with the repository configuration. Library modules support `maven-publish`; `publishToMavenLocal` is available for local integration.

On macOS ARM64, run `python3 scripts/acceptance-macos.py` with `JAVA_HOME` set to a full JDK 21 to exercise eight app scenarios, including replacement/relaunch and helper crashes. Add `--manual` to operate the sample's buttons yourself. See the [verification matrix](docs/verification.md) for fixtures and results.

## Compose or other JVM UI integration

The core has no UI toolkit dependency. Observe `StateFlow<UpdateState>` and translate error codes into the application's own localized strings. The application decides when to check and which download/install actions the user has selected.

```kotlin
val configuration = UpdateConfiguration(
    appId = "com.example.app",
    channel = "stable",
    installedSequence = 11,
    target = "macos-aarch64",
    osVersion = "26.0",
    manifestUrl = "https://updates.example/stable.json",
)
val transport = HttpsUpdateTransport(
    cacheDirectory = updateData.resolve("downloads"),
    allowedHosts = setOf("updates.example"),
)
val installer = MacOsInstaller(
    application = applicationPath,
    helper = applicationPath.resolve("Contents/Helpers/kmp-updater-helper"),
    appId = configuration.appId,
    teamId = developerTeamId,
    installedSequence = configuration.installedSequence,
    requestDirectory = updateData.resolve("requests"),
)
val updater = Updater(
    configuration = configuration,
    transport = transport,
    cryptography = JcaCryptography(mapOf("release-2026" to pinnedPublicKey)),
    checkpoints = FileCheckpointStore(updateData.resolve("checkpoints")),
    downloader = transport,
    installer = installer,
)
```

`applicationPath`, `updateData`, `developerTeamId` and `pinnedPublicKey` are host-supplied values. Import core types from `saien.updater` and JVM types from `saien.updater.jvm`. Keep storage in a dedicated directory owned by the current user; do not pass a shared application directory or a user's general home directory.

Call `check()`, then `download()` when accepted, then `install()` when the user is ready to quit. These are suspend functions; expected failures throw `UpdateException` and update observable state. Overlapping commands fail with `BUSY`. Cancelling a check or download does not install anything. `discard()` releases an update before handoff.

`install()` returns `AwaitingExit(transactionId)` after the helper accepts ownership. Persist that ID in the host's settings before requesting normal exit; allow the app to finish or veto its own shutdown. The helper waits up to 120 seconds and never kills the host. After restart, call `installer.installationStatus(id)` to inspect the outcome and `confirmInstallation(id)` after the new release has started successfully. Confirmation removes the retained old bundle; the host decides when startup is successful.

Close `transport` when the updater's application-level owner is disposed. Drive optional background checks from the host's lifecycle/timer and user preference. Downloads and installation start through explicit commands.

## macOS packaging contract

1. Build the helper for the application's architecture and embed it at `Contents/Helpers/kmp-updater-helper` before signing the app. Sign it with the same Developer ID Application identity as the app, timestamp it and enable hardened runtime.
2. Embed `KMPUpdaterRelease` as a decimal **string** in the app's `Info.plist`. It must equal the signed manifest's release sequence, including in the currently installed application. `CFBundleVersion` and display version remain separate.
3. Sign and notarize the distribution. A DMG must contain exactly one top-level `.app`. The helper verifies its bundle ID, Developer ID publisher, embedded release and Gatekeeper assessment before staging with `ditto`.
4. Run from a writable, canonical installed `.app` path. Read-only disk images, privilege elevation, custom `NSUpdateSecurityPolicy` and sandboxed hosts are unsupported.

For Compose/JVM apps, include native libraries inside dependency JARs in the signing pass. The [notarized Updater Lab acceptance](docs/verification.md#developer-id-and-notarized-app-acceptance) demonstrates nested signing, notarization and an actual old-to-new upgrade.

The helper exchanges complete app bundles atomically on the same volume and retains the old bundle until confirmation. If preparation is interrupted, keep its staging directory for inspection. Use the [transaction recovery flow](docs/architecture.md#installation-contract) to inspect the installed version and clean up safely.

## Offline publisher

Extract the publisher ZIP from GitHub Releases and run `bin/updater-publisher` with JDK 21. When building from source, `:updater-publisher:installDist` puts it in `updater-publisher/build/install/updater-publisher/bin/`.

```sh
updater-publisher keygen release.private-key
updater-publisher artifact App.dmg https://updates.example/app-12.dmg macos-aarch64 dmg
updater-publisher sign payload.json signed-feed.json release-2026 release.private-key
```

`keygen` prints only the public key and creates a new private-key file (mode 0600 on POSIX). Existing keys and signed output files are never overwritten. `artifact` streams a file to produce its size and digest; put its output into the release payload and set any minimum OS requirement. `sign` accepts additional key-ID/private-key-file pairs for an overlapping key transition. Keep private keys offline from hosting and out of source control.

Build, code-sign, notarize and upload application packages through the application's release workflow.

## Compatibility

During alpha, API changes come with release notes and short migration examples. The update feed has its own schema version, with protocol compatibility tracked separately from SDK releases.

## License

[Apache-2.0](LICENSE).

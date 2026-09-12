# Releasing the SDK

`VERSION` is the SDK version. Library coordinates use `io.github.sensefy`; Kotlin imports remain `saien.updater` and `saien.updater.jvm`.

## GitHub Releases

On macOS with Xcode and a full JDK 21:

```sh
./gradlew verify :updater-core:macosArm64Test
swift test --package-path native/macos
python3 scripts/package-release.py
python3 scripts/verify-release.py "build/release/kmp-updater-$(cat VERSION)-maven.zip"
python3 scripts/acceptance-macos.py
```

Packaging produces three ZIPs and `SHA256SUMS` under `build/release/`:

| Archive | Contents |
| --- | --- |
| `kmp-updater-<version>-maven.zip` | All seven Maven publications, including POMs, Gradle metadata, JVM/Native binaries, sources and Javadoc companion JARs |
| `updater-publisher-<version>.zip` | Publisher launchers and runtime dependencies; requires JDK 21 |
| `kmp-updater-<version>-macos-helper.zip` | Production helper for arm64/x86_64; the consuming app signs and notarizes it with its own identity |

The consumer check extracts the Maven ZIP into a temporary directory outside this checkout. A separate Gradle project resolves SDK dependencies exclusively from that repository, exercises a real signed feed and persistent checkpoint on JVM, and compiles all four Native variants. It uses neither `includeBuild` nor `mavenLocal`.

Update `VERSION` and `CHANGELOG.md`, commit, then push a matching `v<version>` tag. The Release workflow runs CI, packages artifacts, verifies external consumption and exercises all eight app scenarios before creating a GitHub prerelease. Only reports are uploaded from app acceptance; its temporary signing keys stay out of published artifacts.

## Maven Central

The same library publications are configured with [Vanniktech Maven Publish](https://vanniktech.github.io/gradle-maven-publish-plugin/central/). Initial account setup is pending: sign into the [Central Portal](https://central.sonatype.com/) as the owner of `senseFy`, verify `io.github.sensefy`, and create a publishing token.

Add these GitHub repository secrets:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central token username |
| `MAVEN_CENTRAL_PASSWORD` | Central token password |
| `MAVEN_SIGNING_KEY` | ASCII-armored OpenPGP private signing key |
| `MAVEN_SIGNING_PASSWORD` | Signing key passphrase, if set |

Keep a recoverable copy of the signing key and publish its public key to a Central-supported keyserver. These OpenPGP keys sign SDK artifacts; applications use separate Ed25519 keys for update feeds and Developer ID identities for macOS bundles.

The current Maven signing fingerprint is `20F0998AF482B862EA206A20F303AD422D396F24`. Its [public key](https://keyserver.ubuntu.com/pks/lookup?op=get&search=0xF303AD422D396F24) is available from the Ubuntu keyserver.

Run the **Maven Central** workflow with an existing, verified release tag. It checks the version, signs every library publication and invokes `publishAndReleaseToMavenCentral`. After the artifacts are visible on Central, simplify the README's repository instructions to `mavenCentral()`.

## Contributing

Small fixes and platform adapters are welcome. Include the check that demonstrates the behavior you changed; use the full packaged acceptance when changing installation or its handoff protocol. Kotlin/Gradle formatting uses `./gradlew spotlessApply`, Swift uses `xcrun swift-format` and the root configuration.

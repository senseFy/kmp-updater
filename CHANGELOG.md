# Changelog

## Unreleased

- Add Developer ID signed and Apple-notarized Updater Lab acceptance using the production helper. Four scenarios pass on macOS ARM64, alongside the existing eight ad-hoc scenarios.
- Include native libraries inside dependency JARs in the sample's signing pass.

## 0.1.0-alpha.1

First macOS/JVM preview of KMP Updater.

- Shared update engine with signed feeds, replay protection, verified downloads and observable state.
- macOS DMG installer with publisher checks, atomic app replacement, relaunch and explicit startup confirmation.
- Offline publisher CLI, complete Maven repository archive and universal macOS helper.
- Independent Compose sample with eight packaged app acceptance scenarios, plus an external Maven consumer check.

The installer targets directly distributed macOS apps (macOS 13+, JDK 21). Initial packaged app acceptance used ARM64 ad-hoc fixtures; see the [verification results](docs/verification.md) for current coverage. Windows/Linux installers and Android/iOS adapters will follow separately.

The libraries are available from Maven Central as of 2026-09-13. GitHub Releases also provides the publisher CLI, macOS helper and Maven repository archive. Alpha API changes will be documented here.

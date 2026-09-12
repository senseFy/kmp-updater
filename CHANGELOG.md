# Changelog

## 0.1.0-alpha.1

First macOS/JVM preview of KMP Updater.

- Shared update engine with signed feeds, replay protection, verified downloads and observable state.
- macOS DMG installer with publisher checks, atomic app replacement, relaunch and explicit startup confirmation.
- Offline publisher CLI, complete Maven repository archive and universal macOS helper.
- Independent Compose sample with eight packaged app acceptance scenarios, plus an external Maven consumer check.

The installer targets directly distributed macOS apps (macOS 13+, JDK 21). Local app acceptance currently covers ARM64 with ad-hoc fixtures; Developer ID signed/notarized acceptance and Intel runtime coverage are next. Windows/Linux installers and Android/iOS adapters will follow separately.

Use the GitHub release archives for this preview; Maven Central activation is pending. Alpha API changes will be documented here.

# Sparkle source study

Reviewed 2026-09-12 against [Sparkle 2.9.6](https://github.com/sparkle-project/Sparkle/tree/ac2def288cbff5cfc7df3ffef6abdf45b72bcb0a), commit `ac2def288cbff5cfc7df3ffef6abdf45b72bcb0a`. Source links below are pinned so later upstream changes do not silently alter the basis of this design.

## Reading map

| Source | Finding and implication |
| --- | --- |
| [SPUUpdater.m](https://github.com/sparkle-project/Sparkle/blob/ac2def288cbff5cfc7df3ffef6abdf45b72bcb0a/Sparkle/SPUUpdater.m) | Configuration, scheduling and update-cycle ownership are centralized. Multiple UI triggers must converge on the same session. |
| [SPUCoreBasedUpdateDriver.m](https://github.com/sparkle-project/Sparkle/blob/ac2def288cbff5cfc7df3ffef6abdf45b72bcb0a/Sparkle/SPUCoreBasedUpdateDriver.m) | Composes basic, download and installer drivers and tracks resumable/downloaded state. Download completion transfers responsibility rather than ending the update. |
| [SPUUserDriver.h](https://github.com/sparkle-project/Sparkle/blob/ac2def288cbff5cfc7df3ffef6abdf45b72bcb0a/Sparkle/SPUUserDriver.h) | UI is a protocol with choices, cancellation and acknowledgements. The updater can support native or custom presentation. |
| [SPUAppcastItemStateResolver.m](https://github.com/sparkle-project/Sparkle/blob/ac2def288cbff5cfc7df3ffef6abdf45b72bcb0a/Sparkle/SPUAppcastItemStateResolver.m) | Eligibility has several dimensions, including OS and application requirements. A version comparison alone does not establish installability. |
| [SUUpdateValidator.m](https://github.com/sparkle-project/Sparkle/blob/ac2def288cbff5cfc7df3ffef6abdf45b72bcb0a/Sparkle/SUUpdateValidator.m) | Distinguishes archive validation, extracted bundle validation and trust-key transitions. Invalid platform signatures can reject an archive with a valid update signature. |
| [SPUInstallerDriver.m](https://github.com/sparkle-project/Sparkle/blob/ac2def288cbff5cfc7df3ffef6abdf45b72bcb0a/Sparkle/SPUInstallerDriver.m) | Enforces message ordering, observes connection failures and tracks installation stages. Cross-process installation needs an explicit protocol. |
| [SUPlainInstaller.m](https://github.com/sparkle-project/Sparkle/blob/ac2def288cbff5cfc7df3ffef6abdf45b72bcb0a/Autoupdate/SUPlainInstaller.m) | Checks downgrade from the actual incoming bundle, stages on the destination volume, attempts atomic swapping where appropriate and restores the old app if the fallback replacement fails. macOS code-signing policy affects which replacement operation is safe. |
| [SUFeedSignatureVerifierTest.swift](https://github.com/sparkle-project/Sparkle/blob/ac2def288cbff5cfc7df3ffef6abdf45b72bcb0a/Tests/SUFeedSignatureVerifierTest.swift) | Exercises exact-byte feed and release-note signatures, including mutations. Protocol tests should use real signatures and adversarial bytes. |

## Important distinctions

Sparkle 2.9 added signed feeds. Its documented defaults still make feed signing and pre-extraction verification opt-in; a new protocol can require both without compatibility obligations. See [setup and signature policy](https://sparkle-project.org/documentation/) and [configuration](https://sparkle-project.org/documentation/customization/).

Sparkle can recover some key changes through the application's Apple code-signing trust. That platform-specific fallback cannot be generalized into a cross-platform unsigned-update escape hatch. Our proposed trust model uses an explicit overlapping-key transition instead.

[Sandboxed integration](https://sparkle-project.org/documentation/sandboxing/) introduces XPC services and signing requirements. A standalone SDK should define sandbox support as a separate capability, not assume a normal child process can escape a sandbox.

Sparkle's rollback on a failed file move is not a general promise of crash-proof installation, durable transaction recovery or rollback after an application crashes on launch. Those guarantees require their own implementation and acceptance evidence.

Sparkle owns a complete update lifecycle. If a later adapter uses it, that adapter must expose a complete backend rather than let a second KMP engine download and install in parallel with Sparkle.

## Additional references

- [TUF specification](https://theupdateframework.github.io/specification/latest/): threat model, metadata expiry, rollback resistance and trust rotation. The SDK is not TUF compliant.
- [Kotlin Multiplatform publication](https://kotlinlang.org/docs/multiplatform/multiplatform-publish-lib-setup.html): standalone root and target-specific Maven artifacts.
- [Java Signature API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/Signature.html): platform cryptographic provider API; no custom Ed25519 implementation is needed.

No Sparkle implementation or test fixtures have been copied into this project. Referencing its architecture does not imply affiliation or compatibility.

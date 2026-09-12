# Verification

Acceptance has three layers: update logic, native replacement/recovery tests, and an independent packaged Compose app using the SDK. The matrix below records the fixtures, results and next checks.

## Run

Use JDK 21 for the SDK. Packaging Updater Lab needs a **full** JDK 21 with `jpackage` (for example, Corretto), a logged-in macOS desktop, and Xcode/Swift tools. The app harness currently targets macOS ARM64.

```sh
./gradlew verify :updater-core:macosArm64Test
swift test --package-path native/macos
xcrun swift-format lint --strict -r native/macos/Sources native/macos/Tests native/macos/Package.swift
python3 scripts/acceptance-macos.py
```

Set `JAVA_HOME` to the full JDK before running the harness. It builds the publisher, native helpers and sample, signs local fixtures, creates a DMG and runs all app scenarios. Reports and per-process logs remain under `build/acceptance/`. `--skip-build` reuses compiled binaries; use it only when their source has not changed. Fixtures and signing keys are still recreated.

For interactive verification, run `python3 scripts/acceptance-macos.py --manual`. Use **Check updates → Download → Install & relaunch** in the window. The harness verifies the same result and closes its test app. See [Updater Lab](../samples/updater-lab/README.md).

## Developer ID and notarized app acceptance

Use an existing Developer ID Application identity and a `notarytool` Keychain profile:

```sh
python3 scripts/acceptance-notarized-macos.py \
  --identity 'Developer ID Application: Your Name (TEAMID1234)' \
  --team-id TEAMID1234 \
  --notary-profile your-notary-profile
```

The script builds two isolated Updater Lab releases with the production helper. It signs nested native code, including libraries inside dependency JARs, then signs the JVM runtime and app bundles. Apple notarizes both apps and the update DMG; tickets are stapled and checked with Gatekeeper before testing the upgrade.

Four scenarios exercise the production configuration: normal upgrade, altered feed signature, wrong application scope and an incorrect artifact digest. They reuse the real HTTPS/Ed25519 harness and verify process replacement, startup confirmation and preserved application data. Fault injection remains in the separate ad-hoc suite.

Reports, Apple submission IDs and signed assets stay under `build/notarized-acceptance/<run>/`. If notarization is still pending or the network interrupts the run, repeat the command with `--resume <run>` to reuse its submissions. Use a fresh run after changing app contents or signing configuration. The script uses the named Keychain profile directly; no certificate or credential is exported into the project.

**Passed on 2026-09-12, macOS ARM64:** both app releases and the update DMG received Apple acceptance, stapled tickets validated, and all four production-helper scenarios passed. The successful upgrade booted release 2 in a different process, confirmed installation, removed the old bundle and preserved app data. Report: `build/notarized-acceptance/20260912-213856-eb33c8/report.json`.

The first submission caught an unsigned native library inside a Compose dependency JAR. The signing pass now includes those entries and updates their companion SHA-256 resources before sealing the containing app. Apple accepted the corrected submission.

## Logic and native acceptance

| Area | Assertions |
| --- | --- |
| Protocol and trust | Bounded envelope/payload, valid schema and URLs, real Ed25519 verification, altered payloads, unknown keys, overlapping keys, multilingual notes |
| Selection and checkpoints | Highest eligible release sequence, OS/target/format/minimum installed version, no-new vs incompatible, scope, expiry, future dates, replay/equivocation, clock rollback, failed checkpoint persistence |
| Session ownership | No implicit download/install, invalid/overlapping commands, old-offer invalidation, cancellation during preparation and handoff, explicit retry/discard, resource retention when cleanup fails |
| JVM effects | Streaming bounds, truncation/digest mismatch, redirects/foreign hosts, partial-file cleanup, changed cached files, persistent checkpoints and four independent JVM writers |
| Native transaction | Actual SHA-256 and atomic filesystem exchange, symlink rejection, exact process start-time identity, rejected/repeated handoff, exit veto, changed staged metadata, failures before/after exchange and before relaunch, recovery from stale journal, explicit backup confirmation |
| Compose UI | Ready state enables only installation; button invokes its action; component rendered to `samples/updater-lab/shared/build/reports/lab-ui.png` |

Core workflow tests use deterministic service doubles; JVM security tests use real cryptography and storage. HTTP unit tests use Ktor's mock engine. Native unit tests exercise the filesystem/journal with injected code-trust and process-identity inputs. The app layer below supplies real HTTPS, signatures, processes and bundles.

## Packaged app acceptance

Each case installs an isolated `saien.updater.lab` app under `build/acceptance/runs/<run>/`. The sample imports the SDK through a Gradle composite build, without depending on Enjoy. Release 1 and release 2 contain different embedded release metadata and resource markers. Both include a JVM and the test helper.

| Scenario | Required result |
| --- | --- |
| Normal upgrade | Signed feed → HTTPS download → verify/stage → acknowledge → normal host exit → atomic replace → new PID boots release 2 → inspect receipt → confirm/remove old bundle |
| Altered feed signature | `UNTRUSTED_SIGNATURE`; no download, handoff or replacement |
| Wrong application scope | `WRONG_SCOPE`; original remains installed |
| Artifact digest mismatch | `ARTIFACT_INTEGRITY`; downloaded files removed, installer never owns the update |
| Helper rejects before acknowledgement | `INSTALLATION`; host stays open and prepared files are discarded |
| Host vetoes exit | Helper times out; original app and host process remain intact |
| Helper abruptly exits before exchange | Original app and complete staged update remain; status reports `original-retained` |
| Helper abruptly exits after exchange, before journal update | New app and complete old backup remain; status reports `installed` despite a stale `replacing` journal; explicit relaunch confirms and removes the backup |

Every case checks that the application's data sentinel is unchanged. Successful upgrades require two different app PIDs. Rejected cases check download/staging cleanup. Faults use `_exit(77)` in a separate helper process, not just thrown exceptions. The harness terminates only recorded processes whose command still belongs to its fixture directory.

### Local trust boundary

The manifest is signed by the real publisher with an ephemeral Ed25519 key. The SDK uses its production HTTPS transport and cryptography. A temporary localhost certificate is trusted only by the fixture JVM through its own truststore; system trust is untouched.

Local app copies are ad-hoc signed. A separate test executable, `kmp-updater-fixture-helper`, accepts those code seals for marked Updater Lab fixtures in the acceptance directory. It reuses the transaction engine, validates code signatures and provides fault injection. Developer ID and Gatekeeper checks remain in the production helper.

The harness also passes a correctly shaped ad-hoc fixture request to `kmp-updater-helper` and requires rejection by the real Developer ID signature check. It requires the fixture executable to refuse execution outside its isolated app directory. Production packaging must embed only `kmp-updater-helper`:

```sh
swift build --package-path native/macos -c release --product kmp-updater-helper
```

## Results — 2026-09-12

macOS ARM64, JDK 21, SDK Kotlin 2.4.0, generated sample Kotlin 2.4.10, Swift 6.3.3:

| Check | Result |
| --- | --- |
| Common protocol + state-machine suites on JVM | 21 passed |
| Same common suites on Kotlin/Native macOS ARM64 | 21 passed |
| JVM security, transport and process-store suites | 15 passed |
| Publisher suite | 2 passed |
| Native installer and transaction suites | 14 passed |
| Compose interaction/rendering test | 1 passed |
| Packaged app scenarios above | 8 passed |
| Kotlin/Gradle and Swift formatting | Passed |
| Publisher, production helper, fixture helper and Compose app image | Built |

**74 test executions (53 distinct cases), plus 8 packaged app scenarios; no failures or skipped tests in the final invoked suites.** The full app report is `build/acceptance/report-20260912-194503-7d4105.json`. Logs and both retained fault transactions are in its referenced run directories. Build output is intentionally ignored by Git; the harness reproduces the evidence.

The new cleanup-failure test first failed: a later check/download could proceed while the session still owned a file it could not delete, allowing its reference to be overwritten. The engine now blocks both commands until explicit cleanup succeeds; the regression passes on JVM and Native.

Earlier acceptance also verified the publisher CLI signature independently with OpenSSL 3.6.3 and caught malformed UTF-8 exception differences across targets and cancellation during delivery of a completed JVM download.

## Next acceptance milestones

- Supported macOS versions and both architectures with actual release packages; multiple app instances, real disk exhaustion, read-only installations and modified signed staging content.

Current recovery checks cover atomic exchange and abrupt helper termination. Power-loss behavior and application-data migration need their own acceptance scenarios. Platform expansion is tracked in the [architecture](architecture.md#platform-integration).

## Distribution acceptance

`python3 scripts/verify-release.py build/release/kmp-updater-0.1.0-alpha.1-maven.zip` passed for the preview artifacts. A fresh Gradle project outside this checkout resolves all seven publications from the extracted archive, compiles JVM and all four Native targets, and verifies a signed feed plus a persisted checkpoint through the public JVM API. This adds one external-consumer test to the suites above. No local Maven cache publication or composite build supplies the SDK.

The macOS helper archive contains the production executable with both arm64 and x86_64 slices. GitHub Actions repeats packaging, external consumption and all eight app scenarios before publishing a release.

The `io.github.sensefy` Central namespace is verified and publishing credentials are configured. The [first signed Central upload](https://github.com/senseFy/kmp-updater/actions/runs/34698188639) passed validation on 2026-09-12. Deployment `94eaf3f0-5fb1-41c8-a771-8d9599fdfb36` is synchronizing to the public repository; direct Central consumption remains pending. Run `python3 scripts/verify-release.py --central` once the artifacts are available. Subsequent publishing workflows also wait for all seven public module files before reporting success.

# Updater Lab

A minimal, independent Compose Multiplatform app for exercising KMP Updater. It displays the installed release, update status and session events, with explicit check, download and install actions. There is no dependency on Enjoy.

## Run a real upgrade

From the SDK root, with `JAVA_HOME` pointing to a full JDK 21 that includes `jpackage`:

```sh
python3 scripts/acceptance-macos.py --manual
```

The harness opens an isolated release 1 app. Click **Check updates**, **Download**, then **Install & relaunch**. The old app exits normally; the new process shows release 2 and confirms that its data survived. The harness verifies the result and closes its app. A manual session has a ten-minute limit.

```sh
# All eight automated application scenarios
python3 scripts/acceptance-macos.py

# Reuse unchanged compiled binaries and run selected scenarios
python3 scripts/acceptance-macos.py --skip-build --cases success crash-after-swap
```

The harness owns localhost HTTPS hosting, temporary manifest keys, process-local TLS trust, local ad-hoc signatures, DMG packaging and isolated installation/data paths under the SDK's `build/acceptance/`. It does not access existing signing credentials or modify system trust. Reports and process logs remain there after the run. Do not run multiple harness sessions concurrently because they share build assets.

Actual installation is currently implemented for **macOS ARM64**. The generated Windows target is a UI scaffold; it does not provide a Windows updater. A regular Gradle desktop run previews the UI without an update fixture:

```sh
cd samples/updater-lab
./gradlew :desktopApp:run
./gradlew :shared:jvmTest
```

## Wiring

- `shared/commonMain`: the Compose screen and presentation state.
- `shared/jvmMain`: explicit SDK wiring, status observation, persisted transaction receipt and normal-exit callback. Automated mode invokes the same SDK operations as the buttons.
- `desktopApp`: window/lifecycle and packaging only.
- `settings.gradle.kts`: composite dependency on the independent SDK, so publishing to Maven is unnecessary.

The local fixture helper is a separate target under `native/macos/Tests/AcceptanceHelper`. It checks actual code seals while accepting explicitly marked ad-hoc fixtures; it also injects installer-process failures. It must never be embedded in a distributed application. Production Developer ID/Gatekeeper validation remains in `kmp-updater-helper`. Read the [acceptance matrix and remaining distribution requirements](../../docs/verification.md).

## Generation

Generated using [senseFy/create-kmp-app](https://github.com/senseFy/create-kmp-app), local revision `c5d1f89`, template `client-2026.08-v1`:

```sh
create-kmp-app create 'Updater Lab' --package saien.updater.lab \
  --targets macos,windows --no-integrations --output samples/updater-lab --yes
```

The generated Kotlin/Compose/Gradle version tuple is preserved. The UI, SDK dependency wiring and acceptance lifecycle were added to that scaffold. The screen test captures only its own rendered Compose content to `shared/build/reports/lab-ui.png`; it does not capture the user's desktop.

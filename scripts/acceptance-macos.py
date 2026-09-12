#!/usr/bin/env python3
"""Local macOS app upgrade acceptance. Uses only owned fixtures and process-local TLS trust."""
import argparse
import base64
import functools
import http.server
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import signal
import ssl
import subprocess
import sys
import threading
import time
import uuid

REPO = Path(__file__).resolve().parents[1]
OUTPUT = REPO / "build/acceptance"
ASSETS = OUTPUT / "assets"
APP_NAME = "Updater Lab.app"
APP_ID = "saien.updater.lab"
CASES = ("success", "bad-signature", "wrong-scope", "bad-artifact", "fail-before-accept",
         "exit-timeout", "crash-before-swap", "crash-after-swap")
ENV = os.environ.copy()


def run(*args, cwd=REPO, timeout=300, check=True):
    result = subprocess.run([str(a) for a in args], cwd=cwd, env=ENV, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"Command failed: {args[0]}\n{result.stdout[-12000:]}")
    return result


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def wait_for(predicate, description, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.2)
    raise TimeoutError(description)


def app_sequence(app):
    with (app / "Contents/Info.plist").open("rb") as source:
        return plistlib.load(source)["KMPUpdaterRelease"]


def helper_path(app):
    return app / "Contents/Helpers/kmp-updater-fixture-helper"


def copy_app(source, destination):
    run("/usr/bin/ditto", source, destination)


def build_assets(skip_build):
    ASSETS.mkdir(parents=True, exist_ok=True)
    if not skip_build:
        print("Building publisher, helpers and the independent Compose app…", flush=True)
        for args, cwd in [
            (("./gradlew", ":updater-publisher:installDist", "--console=plain"), REPO),
            (("swift", "build", "--package-path", "native/macos", "-c", "release"), REPO),
            (("./gradlew", ":shared:jvmTest", ":desktopApp:createDistributable", "--console=plain"), REPO / "samples/updater-lab"),
        ]:
            result = run(*args, cwd=cwd, timeout=900)
            (ASSETS / f"build-{cwd.name}-{args[0].replace('/', '')}.log").write_text(result.stdout)
    base = REPO / "samples/updater-lab/desktopApp/build/compose/binaries/main/app" / APP_NAME
    require(base.is_dir(), "Build the sample app with a full JDK 21 (including jpackage)")
    fixture_helper = REPO / "native/macos/.build/release/kmp-updater-fixture-helper"
    for sequence in (1, 2):
        app = ASSETS / f"release-{sequence}" / APP_NAME
        if app.parent.exists():
            shutil.rmtree(app.parent)
        copy_app(base, app)
        helper_path(app).parent.mkdir(exist_ok=True)
        shutil.copy2(fixture_helper, helper_path(app))
        info_file = app / "Contents/Info.plist"
        with info_file.open("rb") as source:
            info = plistlib.load(source)
        info.update(CFBundleIdentifier=APP_ID, CFBundleVersion=f"1.0.{sequence}",
                    CFBundleShortVersionString=f"1.0.{sequence}", KMPUpdaterRelease=str(sequence),
                    KMPUpdaterAcceptanceFixture=True)
        with info_file.open("wb") as target:
            plistlib.dump(info, target)
        (app / "Contents/Resources/build.txt").write_text(f"{sequence}\n")
        run("/usr/bin/codesign", "--force", "--deep", "--sign", "-", "--options", "runtime",
            "--entitlements", REPO / "native/macos/Tests/Fixtures/entitlements.plist", app)
        run("/usr/bin/codesign", "--verify", "--strict", "--deep", app)
    dmg = ASSETS / "release-2.dmg"
    dmg.unlink(missing_ok=True)
    run("/usr/bin/hdiutil", "create", "-quiet", "-volname", "Updater Lab Update", "-srcfolder",
        ASSETS / "release-2", "-format", "UDZO", dmg)
    return dmg


def tls_setup():
    cert = ASSETS / "localhost.crt"
    key = ASSETS / "localhost.key"
    store = ASSETS / "localhost.p12"
    store.unlink(missing_ok=True)
    run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
        "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
        "-keyout", key, "-out", cert)
    key.chmod(0o600)
    run(Path(ENV["JAVA_HOME"]) / "bin/keytool", "-importcert", "-noprompt", "-alias", "lab-localhost",
        "-file", cert, "-keystore", store, "-storetype", "PKCS12", "-storepass", "changeit")
    return cert, key, store


class FixtureHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        pass


def start_server(cert, key):
    handler = functools.partial(FixtureHandler, directory=str(ASSETS))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert, key)
    server.socket = context.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


def signed_feeds(port, dmg, stamp):
    publisher = REPO / "updater-publisher/build/install/updater-publisher/bin/updater-publisher"
    private = ASSETS / f"{stamp}.private-key"
    public = run(publisher, "keygen", private).stdout.strip()
    artifact = json.loads(run(publisher, "artifact", dmg, f"https://localhost:{port}/release-2.dmg", "macos-aarch64", "dmg").stdout)
    urls = {}
    for case in CASES:
        payload = dict(schema=1, appId="unrelated.app" if case == "wrong-scope" else APP_ID,
                       channel="stable", sequence=2, issuedAt=int(time.time()) - 60,
                       expiresAt=int(time.time()) + 3600,
                       releases=[dict(sequence=2, version="1.0.2", artifacts=[dict(artifact)])])
        if case == "bad-artifact":
            payload["releases"][0]["artifacts"][0]["sha256"] = "0" * 64
        source = ASSETS / f"{stamp}-{case}-payload.json"
        feed = ASSETS / f"{stamp}-{case}.json"
        write_json(source, payload)
        run(publisher, "sign", source, feed, "fixture", private)
        if case == "bad-signature":
            envelope = json.loads(feed.read_text())
            signature = bytearray(base64.b64decode(envelope["signatures"][0]["signature"]))
            signature[0] ^= 1
            envelope["signatures"][0]["signature"] = base64.b64encode(signature).decode()
            write_json(feed, envelope)
        urls[case] = f"https://localhost:{port}/{feed.name}"
    return public, urls


def stop_owned_processes(root, process):
    """Never kill by app name. Verify each recorded PID still refers to this fixture path."""
    candidates = {process.pid}
    log = root / "app-events.log"
    if log.exists():
        candidates.update(int(pid) for pid in re.findall(r"boot pid=(\d+)", log.read_text()))
    helper_pid = root / "helper-pid"
    if helper_pid.exists():
        candidates.add(int(helper_pid.read_text()))
    for pid in candidates:
        command = run("/bin/ps", "-p", pid, "-o", "command=", check=False).stdout
        if str(root) in command:
            try:
                os.kill(pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"Owned app did not stop; inspect {root}")


def check_production_gate(root, app):
    """Valid fixture metadata reaches the actual Developer ID gate, which must reject ad-hoc code."""
    request = dict(transactionId=str(uuid.uuid4()), target=str(app), archive=str(root / "archive.dmg"),
                   size=1, sha256="0" * 64, appId=APP_ID, teamId="FIXTURE000",
                   installedSequence=1, releaseSequence=2, hostPid=os.getpid())
    path = root / "production-rejection.json"
    write_json(path, request)
    production = REPO / "native/macos/.build/release/kmp-updater-helper"
    result = run(production, "prepare", path, check=False)
    require(result.returncode != 0 and "Invalid Developer ID signature or publisher" in result.stdout,
            f"Production signature policy was not exercised: {result.stdout}")
    (root / "production-rejection.log").write_text(result.stdout)
    fixture = REPO / "native/macos/.build/release/kmp-updater-fixture-helper"
    result = run(fixture, "prepare", path, check=False)
    require(result.returncode != 0 and "restricted" in result.stdout, "Fixture executable escaped its directory restriction")


def exercise(case, stamp, public, urls, trust_store, manual=False):
    root = OUTPUT / "runs" / f"{stamp}-{case}"
    root.mkdir(parents=True)
    (root / ".updater-lab-fixture").write_text("kmp-updater-acceptance-v1\n")
    app = root / APP_NAME
    copy_app(ASSETS / "release-1" / APP_NAME, app)
    sentinel = f"user-data-{uuid.uuid4()}"
    write_json(root / "scenario.json", {"fault": case})
    properties = dict(feed=urls[case], publicKey=public, trustStore=str(trust_store), sentinel=sentinel,
                      automate=str(not manual).lower(), vetoExit=str(case == "exit-timeout").lower())
    (root / "lab.properties").write_text("".join(f"{key}={value}\n" for key, value in properties.items()))
    check_production_gate(root, app)
    executable = app / "Contents/MacOS/Updater Lab"
    with (root / "console.log").open("w") as output:
        process = subprocess.Popen([str(executable)], stdout=output, stderr=subprocess.STDOUT, env=ENV)
        try:
            result_file = root / "result.txt"
            if case.startswith("crash-"):
                phase = case.removeprefix("crash-")
                wait_for(lambda: (root / f"helper-{phase}").exists() or result_file.exists(), "Helper did not reach fault boundary", 180)
                require(not result_file.exists(), f"Update failed before fault boundary: {result_file.read_text() if result_file.exists() else ''}")
                wait_for(lambda: process.poll() is not None, "Original app did not exit")
                pid = int((root / "helper-pid").read_text())
                wait_for(lambda: not run("/bin/ps", "-p", pid, "-o", "command=", check=False).stdout.strip(), "Fault helper did not terminate")
                transaction = (root / "state/pending-transaction").read_text().strip()
                directory = root / f".kmp-update-{APP_ID}-{transaction}"
                installed = case == "crash-after-swap"
                expected = "installed" if installed else "original-retained"
                status = run(helper_path(app), "status", directory).stdout.strip()
                require(status == expected, f"Recovery status: {status}")
                require((directory / "phase").read_text() == "replacing", "Fault did not interrupt the intended journal boundary")
                require(app_sequence(app) == ("2" if installed else "1"), "Incomplete installed app")
                require(app_sequence(directory / "next.app") == ("1" if installed else "2"), "Retained bundle changed")
                if installed:
                    run("/usr/bin/open", app)
                else:
                    result_file.write_text("1\noriginal-retained\n")
            wait_for(result_file.exists, "App did not report a result", 600 if manual else 180)
            result = result_file.read_text().splitlines()
            events = (root / "app-events.log").read_text()
            expected_error = {"bad-signature": "UNTRUSTED_SIGNATURE", "wrong-scope": "WRONG_SCOPE",
                              "bad-artifact": "ARTIFACT_INTEGRITY", "fail-before-accept": "INSTALLATION"}.get(case)
            if expected_error:
                require(result == ["1", f"failure:{expected_error}"], f"Unexpected rejection: {result}")
                require("release=1 accepted" not in events and app_sequence(app) == "1", "Rejected update crossed handoff")
                require(not list(root.glob(f".kmp-update-{APP_ID}-*")), "Rejected preparation leaked staging")
                require(not list((root / "state/downloads").iterdir()), "Rejected update leaked downloads")
            elif case in ("exit-timeout", "crash-before-swap"):
                require(result == ["1", "original-retained"], f"Original not retained: {result}")
                require(app_sequence(app) == "1", "Original was replaced despite veto/fault")
                if case == "exit-timeout":
                    require(process.poll() is None, "Installer killed the vetoing host")
            else:
                require(result == ["2", "success"], f"Upgrade failed: {result}")
                require(app_sequence(app) == "2", "App metadata was not replaced")
                require((app / "Contents/Resources/build.txt").read_text().strip() == "2", "New app resources missing")
                require("release=1 normal-exit" in events and "release=2 confirmed" in events, "Incomplete lifecycle")
                pids = re.findall(r"boot pid=(\d+)", events)
                require(len(pids) == 2 and pids[0] != pids[1], "New version did not boot in a new process")
                require(process.poll() is not None, "Old process still running")
                require(not list(root.glob(f".kmp-update-{APP_ID}-*")), "Confirmed backup was not removed")
            require((root / "state/user-data.txt").read_text() == sentinel, "Application data changed")
            return dict(case=case, passed=True, result=result, directory=str(root), events=events.splitlines())
        finally:
            stop_owned_processes(root, process)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="Reuse already-built binaries; fixture copies and keys are always fresh")
    parser.add_argument("--manual", action="store_true", help="Open a success fixture and use Check, Download and Install buttons yourself (10 minute limit)")
    parser.add_argument("--cases", nargs="+", choices=CASES, default=list(CASES))
    args = parser.parse_args()
    if args.manual:
        args.cases = ["success"]
        print("Manual session: use Check updates → Download → Install & relaunch in Updater Lab.", flush=True)
    require(sys.platform == "darwin" and os.uname().machine == "arm64", "This fixture harness currently targets macOS ARM64")
    require("JAVA_HOME" in ENV and (Path(ENV["JAVA_HOME"]) / "bin/jpackage").is_file(), "Set JAVA_HOME to a full JDK 21 with jpackage")
    stamp = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
    dmg = build_assets(args.skip_build)
    cert, key, store = tls_setup()
    server = start_server(cert, key)
    report = dict(timestamp=stamp, trust="Local ad-hoc app fixtures; real HTTPS and Ed25519; production Developer ID gate checked separately", cases=[])
    report_path = OUTPUT / f"report-{stamp}.json"
    try:
        public, urls = signed_feeds(server.server_port, dmg, stamp)
        for case in args.cases:
            print(f"Running {case}…", flush=True)
            try:
                result = exercise(case, stamp, public, urls, store, args.manual)
            except Exception as error:
                result = dict(case=case, passed=False, error=str(error))
            report["cases"].append(result)
            write_json(report_path, report)
            print(f"{'PASS' if result['passed'] else 'FAIL'} {case}: {result.get('error', result.get('result'))}", flush=True)
    finally:
        server.shutdown()
        server.server_close()
    print(f"Report: {report_path}", flush=True)
    require(len(report["cases"]) == len(args.cases) and all(case["passed"] for case in report["cases"]), "Acceptance failed; inspect retained logs")


if __name__ == "__main__":
    main()

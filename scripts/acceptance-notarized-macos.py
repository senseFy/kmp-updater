#!/usr/bin/env python3
"""Sign/notarize isolated Updater Lab releases and exercise the production helper."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("lab_acceptance", ROOT / "scripts/acceptance-macos.py")
lab = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lab)
OUTPUT = ROOT / "build/notarized-acceptance"
CASES = ("success", "bad-signature", "wrong-scope", "bad-artifact")
MACH_O = {b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf", b"\xce\xfa\xed\xfe",
          b"\xfe\xed\xfa\xce", b"\xca\xfe\xba\xbe", b"\xbe\xba\xfe\xca"}


def run(*args, timeout=900):
    return lab.run(*args, timeout=timeout)


def codesign(path, identity, entitlements=None):
    args = ["/usr/bin/codesign", "--force", "--sign", identity, "--options", "runtime", "--timestamp"]
    if entitlements:
        args += ["--entitlements", entitlements]
    run(*args, path)


def sign_app(app, identity, team):
    entitlements = ROOT / "native/macos/Tests/Fixtures/entitlements.plist"
    # Apple also scans native code inside dependency JARs, including unused architectures.
    for archive in (app / "Contents/app").glob("*.jar"):
        with zipfile.ZipFile(archive) as source:
            native = []
            for item in source.infolist():
                if item.filename.endswith((".dylib", ".jnilib")):
                    with source.open(item) as content:
                        if content.read(4) in MACH_O:
                            native.append(item.filename)
            if not native:
                continue
            lab.require(not any(name.upper().endswith((".SF", ".RSA", ".DSA"))
                                and name.upper().startswith("META-INF/") for name in source.namelist()),
                        f"Cannot modify a signed JAR: {archive.name}")
            replacements = {}
            with tempfile.TemporaryDirectory(prefix="notarized-jar-") as temporary:
                for index, name in enumerate(native):
                    library = Path(temporary) / f"library-{index}.dylib"
                    library.write_bytes(source.read(name))
                    codesign(library, identity)
                    replacements[name] = library.read_bytes()
                    if name + ".sha256" in source.namelist():
                        replacements[name + ".sha256"] = hashlib.sha256(replacements[name]).hexdigest().encode()
                updated = Path(temporary) / "updated.jar"
                with zipfile.ZipFile(updated, "w") as destination:
                    for item in source.infolist():
                        destination.writestr(item, replacements.get(item.filename, source.read(item)))
                replacement = archive.with_suffix(".signed-jar")
                shutil.copy2(updated, replacement)
        replacement.replace(archive)
    # Sign each nested executable before its containing bundle. --deep is verification only.
    binaries = []
    for path in app.rglob("*"):
        if path.is_file() and not path.is_symlink():
            with path.open("rb") as source:
                magic = source.read(4)
            if magic in MACH_O:
                kind = run("/usr/bin/file", "-b", path).stdout
                if "Mach-O" in kind:
                    binaries.append((path, "executable" in kind))
    for path, executable in sorted(binaries, key=lambda item: len(item[0].parts), reverse=True):
        codesign(path, identity, entitlements if executable and path.name != "kmp-updater-helper" else None)
    codesign(app / "Contents/runtime", identity)
    codesign(app, identity, entitlements)
    run("/usr/bin/codesign", "--verify", "--deep", "--strict", app)
    details = run("/usr/bin/codesign", "--display", "--verbose=4", app).stdout
    lab.require(f"TeamIdentifier={team}" in details and "Authority=Developer ID Application:" in details,
                "Application is not signed by the requested Developer ID team")
    lab.require(not (app / "Contents/Helpers/kmp-updater-fixture-helper").exists(),
                "Production acceptance must not contain the fixture helper")


def notarize(path, profile, submissions):
    record_path = path.with_name(path.name + ".notary.json")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if record_path.exists():
        record = json.loads(record_path.read_text())
        lab.require(record["sha256"] == digest, "Submitted archive changed; start a fresh acceptance run")
    else:
        print(f"Submitting {path.name} for Apple notarization…", flush=True)
        response = json.loads(run("xcrun", "notarytool", "submit", path,
                                  "--keychain-profile", profile, "--output-format", "json").stdout)
        record = {"id": response["id"], "file": path.name, "sha256": digest}
        lab.write_json(record_path, record)
    deadline = time.monotonic() + 1800
    while True:
        result = json.loads(run("xcrun", "notarytool", "info", record["id"],
                                "--keychain-profile", profile, "--output-format", "json").stdout)
        status = result["status"]
        if status == "Accepted":
            record["status"] = status
            lab.write_json(record_path, record)
            submissions.append(record)
            print(f"Apple accepted {path.name}: {record['id']}", flush=True)
            return
        if status != "In Progress":
            log_path = path.with_name(path.name + ".notary-log.json")
            run("xcrun", "notarytool", "log", record["id"], "--keychain-profile", profile, log_path)
            raise RuntimeError(f"Apple returned {status}; inspect {log_path}")
        if time.monotonic() >= deadline:
            raise TimeoutError(f"Notarization pending; resume this run using --resume {lab.ASSETS.parent.name}")
        time.sleep(15)


def prepare_assets(args, report):
    assets = lab.ASSETS
    assets.mkdir(parents=True, exist_ok=True)
    readiness = assets / "ready.json"
    if readiness.exists():
        previous = json.loads(readiness.read_text())
        lab.require(previous["team"] == args.team_id, "Resumed assets use another signing team")
        report["notarization"] = previous["notarization"]
        dmg = assets / "release-2.dmg"
        lab.require(hashlib.sha256(dmg.read_bytes()).hexdigest() == previous["dmgSha256"],
                    "Resumed DMG changed")
        return dmg
    if not args.resume:
        print("Building publisher, production helper and Updater Lab…", flush=True)
        for command, cwd in [
            (("./gradlew", ":updater-publisher:installDist", "--console=plain"), ROOT),
            (("swift", "build", "--package-path", "native/macos", "-c", "release", "--product", "kmp-updater-helper"), ROOT),
            (("./gradlew", ":shared:jvmTest", ":desktopApp:createDistributable", "--console=plain"), ROOT / "samples/updater-lab"),
        ]:
            result = lab.run(*command, cwd=cwd, timeout=1200)
            (assets / f"build-{cwd.name}-{command[0].replace('/', '')}.log").write_text(result.stdout)
        base = ROOT / "samples/updater-lab/desktopApp/build/compose/binaries/main/app" / lab.APP_NAME
        for sequence in (1, 2):
            app = assets / "applications" / f"release-{sequence}" / lab.APP_NAME
            app.parent.mkdir(parents=True)
            lab.copy_app(base, app)
            helper = app / "Contents/Helpers/kmp-updater-helper"
            helper.parent.mkdir(exist_ok=True)
            shutil.copy2(ROOT / "native/macos/.build/release/kmp-updater-helper", helper)
            info_path = app / "Contents/Info.plist"
            with info_path.open("rb") as source:
                info = plistlib.load(source)
            info.update(CFBundleIdentifier=lab.APP_ID, CFBundleVersion=f"1.0.{sequence}",
                        CFBundleShortVersionString=f"1.0.{sequence}", KMPUpdaterRelease=str(sequence))
            with info_path.open("wb") as destination:
                plistlib.dump(info, destination)
            (app / "Contents/Resources/build.txt").write_text(f"{sequence}\n")
            print(f"Signing release {sequence} with Developer ID…", flush=True)
            sign_app(app, args.identity, args.team_id)
        run("/usr/bin/ditto", "-c", "-k", "--keepParent", assets / "applications", assets / "applications.zip")
    notarize(assets / "applications.zip", args.notary_profile, report["notarization"])
    for sequence in (1, 2):
        app = assets / "applications" / f"release-{sequence}" / lab.APP_NAME
        run("xcrun", "stapler", "staple", app)
        run("xcrun", "stapler", "validate", app)
        assessment = run("/usr/sbin/spctl", "--assess", "--type", "execute", "--verbose=4", app).stdout
        lab.require("Notarized Developer ID" in assessment, "Gatekeeper did not recognize notarized app")
        (assets / f"release-{sequence}-gatekeeper.log").write_text(assessment)
        destination = assets / f"release-{sequence}" / lab.APP_NAME
        if destination.parent.exists():
            shutil.rmtree(destination.parent)
        destination.parent.mkdir()
        lab.copy_app(app, destination)
    dmg = assets / "release-2.dmg"
    if not dmg.exists():
        run("/usr/bin/hdiutil", "create", "-quiet", "-volname", "Updater Lab Update", "-srcfolder",
            assets / "release-2", "-format", "UDZO", dmg)
        codesign(dmg, args.identity)
    notarize(dmg, args.notary_profile, report["notarization"])
    run("xcrun", "stapler", "staple", dmg)
    run("xcrun", "stapler", "validate", dmg)
    assessment = run("/usr/sbin/spctl", "--assess", "--type", "open", "--context", "context:primary-signature",
                     "--verbose=4", dmg).stdout
    (assets / "dmg-gatekeeper.log").write_text(assessment)
    lab.write_json(readiness, dict(team=args.team_id, notarization=report["notarization"],
                                  dmgSha256=hashlib.sha256(dmg.read_bytes()).hexdigest()))
    return dmg


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--identity", required=True, help="Developer ID Application identity name or SHA-1")
    parser.add_argument("--team-id", required=True)
    parser.add_argument("--notary-profile", required=True, help="Existing notarytool Keychain profile")
    parser.add_argument("--resume", help="Run directory name printed by an earlier invocation")
    args = parser.parse_args()
    lab.require(sys.platform == "darwin" and os.uname().machine == "arm64", "Requires macOS ARM64")
    lab.require(re.fullmatch(r"[A-Z0-9]{10}", args.team_id), "Invalid team ID")
    lab.require("JAVA_HOME" in lab.ENV and (Path(lab.ENV["JAVA_HOME"]) / "bin/jpackage").is_file(),
                "Set JAVA_HOME to a full JDK 21")
    stamp = args.resume or time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
    lab.require(re.fullmatch(r"\d{8}-\d{6}-[a-f0-9]{6}", stamp), "Invalid run directory name")
    lab.OUTPUT = OUTPUT / stamp
    lab.ASSETS = lab.OUTPUT / "assets"
    lab.CASES = CASES
    if args.resume:
        lab.require(lab.OUTPUT.is_dir(), "Cannot resume a missing run")
    else:
        lab.OUTPUT.mkdir(parents=True)
    report = dict(timestamp=stamp, team=args.team_id, sdkVersion=(ROOT / "VERSION").read_text().strip(),
                  trust="Developer ID signed and Apple-notarized apps; production helper; real HTTPS and Ed25519",
                  notarization=[], cases=[])
    report_path = lab.OUTPUT / "report.json"
    print(f"Acceptance run: {lab.OUTPUT}", flush=True)
    server = None
    try:
        dmg = prepare_assets(args, report)
        cert, key, trust_store = lab.tls_setup()
        server = lab.start_server(cert, key)
        attempt = stamp + "-" + uuid.uuid4().hex[:6]
        public, urls = lab.signed_feeds(server.server_port, dmg, attempt)
        for case in CASES:
            print(f"Production helper: {case}…", flush=True)
            result = lab.exercise(case, attempt, public, urls, trust_store, developer_team=args.team_id)
            app = Path(result["directory"]) / lab.APP_NAME
            run("/usr/bin/codesign", "--verify", "--deep", "--strict", app)
            run("/usr/sbin/spctl", "--assess", "--type", "execute", app)
            report["cases"].append(result)
            lab.write_json(report_path, report)
            print(f"PASS {case}: {result['result']}", flush=True)
        report["passed"] = True
    except Exception as error:
        report.update(passed=False, error=str(error))
        raise
    finally:
        if server:
            server.shutdown()
            server.server_close()
        lab.write_json(report_path, report)
        print(f"Report: {report_path}", flush=True)


if __name__ == "__main__":
    main()

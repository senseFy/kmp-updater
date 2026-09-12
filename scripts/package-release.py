#!/usr/bin/env python3
"""Build the public archives. Requires macOS, JDK 21 and Xcode; no signing secrets."""
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "build/release"


def run(*args):
    subprocess.run([str(arg) for arg in args], cwd=ROOT, check=True)


def main():
    if sys.platform != "darwin":
        raise SystemExit("Package the macOS preview on a Mac with Xcode and JDK 21.")
    version = (ROOT / "VERSION").read_text().strip()
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?", version):
        raise SystemExit("VERSION must contain a release version.")
    for directory in (OUTPUT, ROOT / "build/release-repository"):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)
    run("./gradlew", "mavenRepositoryZip", ":updater-publisher:distZip", "--console=plain")
    shutil.copy2(ROOT / f"updater-publisher/build/distributions/updater-publisher-{version}.zip", OUTPUT)
    swift = ["swift", "build", "--package-path", "native/macos", "-c", "release",
             "--product", "kmp-updater-helper", "--arch", "arm64", "--arch", "x86_64"]
    run(*swift)
    bin_path = subprocess.check_output(swift + ["--show-bin-path"], cwd=ROOT, text=True).strip()
    helper = Path(bin_path) / "kmp-updater-helper"
    run("lipo", helper, "-verify_arch", "arm64", "x86_64")
    with tempfile.TemporaryDirectory(prefix="updater-helper-") as temporary:
        stage = Path(temporary)
        shutil.copy2(helper, stage / helper.name)
        shutil.copy2(ROOT / "LICENSE", stage)
        (stage / "README.txt").write_text(
            "KMP Updater macOS helper (macOS 13+, arm64 and x86_64).\n\n"
            "Embed at Contents/Helpers/kmp-updater-helper. Sign this executable with\n"
            "your app's Developer ID Application identity, hardened runtime and timestamp,\n"
            "then sign and notarize the containing app. This archive is not notarized.\n"
            "Packaging contract: https://github.com/senseFy/kmp-updater#macos-packaging-contract\n"
        )
        with zipfile.ZipFile(OUTPUT / f"kmp-updater-{version}-macos-helper.zip", "w",
                             compression=zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(stage.iterdir()):
                archive.write(file, file.name)
    checksums = []
    for file in sorted(OUTPUT.glob("*.zip")):
        checksums.append(f"{hashlib.sha256(file.read_bytes()).hexdigest()}  {file.name}\n")
    (OUTPUT / "SHA256SUMS").write_text("".join(checksums))
    print(f"Release archives: {OUTPUT}")


if __name__ == "__main__":
    main()

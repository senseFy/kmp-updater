#!/usr/bin/env python3
"""Consume a Maven ZIP or Maven Central in a fresh project outside the SDK checkout."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULES = {"updater-core", "updater-core-jvm", "updater-core-macosarm64",
           "updater-core-macosx64", "updater-core-linuxx64", "updater-core-mingwx64",
           "updater-jvm"}
CENTRAL = "https://repo.maven.apache.org/maven2"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def verify_repository(repository, version):
    modules = list(repository.rglob("*.module"))
    require({file.parent.parent.name for file in modules} == MODULES,
            "Missing or unexpected target publications")
    for file in modules:
        data = json.loads(file.read_text())
        require(data["component"]["group"] == "io.github.sensefy", f"Wrong group: {file}")
        require(data["component"]["version"] == version, f"Wrong version: {file}")
        for variant in data["variants"]:
            references = variant.get("files", [])
            if "available-at" in variant:
                references = references + [variant["available-at"]]
            for reference in references:
                target = (file.parent / reference["url"]).resolve()
                require(target.is_relative_to(repository) and target.is_file(),
                        f"Missing or external metadata reference: {file}: {reference['url']}")
                if "sha256" in reference:
                    require(hashlib.sha256(target.read_bytes()).hexdigest() == reference["sha256"],
                            f"Metadata digest mismatch: {target}")
        stem = file.with_suffix("")
        for suffix in ("-sources.jar", "-javadoc.jar", ".pom"):
            require(Path(str(stem) + suffix).is_file(), f"Missing publication companion: {stem}{suffix}")
        pom = ET.parse(file.with_suffix(".pom"))
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        for field in ("name", "description", "url", "licenses/license/name", "developers/developer/id",
                      "scm/connection"):
            require(pom.find("/".join(f"m:{part}" for part in field.split("/")), ns) is not None,
                    f"Missing POM {field}: {file}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, nargs="?")
    parser.add_argument("--central", action="store_true", help="Resolve the release from Maven Central")
    args = parser.parse_args()
    if bool(args.archive) == args.central:
        parser.error("Choose a Maven ZIP or --central")
    archive = args.archive.resolve() if args.archive else None
    version = (ROOT / "VERSION").read_text().strip()
    report_dir = ROOT / "build" / ("central-verification" if args.central else "release-verification")
    if report_dir.exists():
        shutil.rmtree(report_dir)
    report_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="published-kmp-updater-") as temporary:
        temporary = Path(temporary).resolve()
        require(not temporary.is_relative_to(ROOT), "Consumer must live outside the SDK checkout")
        if archive:
            with zipfile.ZipFile(archive) as source:
                for name in source.namelist():
                    require((temporary / name).resolve().is_relative_to(temporary), "Unsafe archive path")
                source.extractall(temporary)
            repository = temporary / "repository"
            verify_repository(repository, version)
            repository_url = repository.as_uri()
        else:
            repository_url = CENTRAL
        consumer = temporary / "consumer"
        shutil.copytree(ROOT / "tests/consumer", consumer,
                        ignore=shutil.ignore_patterns(".gradle", ".kotlin", "build"))
        shutil.copy2(ROOT / "gradlew", consumer)
        shutil.copytree(ROOT / "gradle/wrapper", consumer / "gradle/wrapper")
        with (report_dir / "consumer.log").open("w") as log:
            result = subprocess.run(
                [str(consumer / "gradlew"), "verifyPublishedDependencies", "--console=plain",
                 f"-PreleaseRepository={repository_url}", f"-PupdaterVersion={version}"],
                cwd=consumer, stdout=log, stderr=subprocess.STDOUT,
            )
        if (consumer / "build/test-results").is_dir():
            shutil.copytree(consumer / "build/test-results", report_dir / "test-results", dirs_exist_ok=True)
        if result.returncode:
            print((report_dir / "consumer.log").read_text()[-16000:])
            raise SystemExit(result.returncode)
        (report_dir / "result.json").write_text(json.dumps({
            "version": version,
            **({"archive": archive.name, "sha256": hashlib.sha256(archive.read_bytes()).hexdigest()}
               if archive else {"repository": CENTRAL}),
            "publications": sorted(MODULES), "externalConsumer": "passed",
            "jvmSignedFeed": "passed", "nativeCompilation": ["macosArm64", "macosX64", "linuxX64", "mingwX64"],
        }, indent=2) + "\n")
    print(f"Published artifacts verified; evidence: {report_dir}")


if __name__ == "__main__":
    main()

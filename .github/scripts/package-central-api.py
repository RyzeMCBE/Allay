#!/usr/bin/env python3
"""Validate and package Engine API for Sonatype Central Portal.

By default prepares a *dry-run* (unsigned, NEVER submit to Central).
--sign is only for an authorized maintainer with repository-scoped
GitHub Actions secrets configured in a manual workflow_dispatch run.
No external network requests occur in this script.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

GROUP = "io.github.ryzemcbe"
ARTIFACT = "engine-api"
NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}
BASE = Path("build/central-api-maven")
DIST = Path("dist")


def require(condition, message):
    if not condition:
        raise SystemExit("ERROR: " + message)


def metadata(path, version):
    root = ET.parse(path).getroot()

    def entry(name):
        value = root.find(f"m:{name}", NAMESPACE)
        return None if value is None else value.text

    expected = (GROUP, ARTIFACT, version)
    actual = tuple(entry(key) for key in ("groupId", "artifactId", "version"))
    require(actual == expected, f"Unexpected coordinates in POM: {actual}")
    for item in ("name", "description", "url", "licenses", "developers", "scm"):
        require(root.find(f"m:{item}", NAMESPACE) is not None, f"Missing Maven Central POM metadata: {item}")
    require(root.find("m:dependencies", NAMESPACE) is not None, "POM lost API dependency metadata")


def checksum(data, algorithm):
    return hashlib.new(algorithm, data).hexdigest().encode("ascii")


def gpg_setup(gnupg_home):
    key = os.getenv("CENTRAL_GPG_PRIVATE_KEY", "")
    passphrase = os.getenv("CENTRAL_GPG_PASSPHRASE", "")
    require(key.strip() != "", "Missing CENTRAL_GPG_PRIVATE_KEY GitHub secret")
    require(passphrase != "", "Missing CENTRAL_GPG_PASSPHRASE GitHub secret")
    gnupg_home.mkdir(mode=0o700)
    env = dict(os.environ, GNUPGHOME=str(gnupg_home))
    subprocess.run(["gpg", "--batch", "--import"], input=key.encode("utf-8"),
                   env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return env, passphrase


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--sign", action="store_true", help="Sign every bundle asset using CI secrets")
    args = parser.parse_args()
    version = args.version
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) is not None,
            "Maven Central releases must be X.Y.Z, not SNAPSHOT or -dev")

    publish_dir = BASE / GROUP.replace(".", "/") / ARTIFACT / version
    artifacts = [publish_dir / f"{ARTIFACT}-{version}{suffix}"
                 for suffix in (".jar", "-sources.jar", "-javadoc.jar", ".pom")]
    module = publish_dir / f"{ARTIFACT}-{version}.module"
    if module.exists():
        artifacts.append(module)
    for item in artifacts[:4]:
        require(item.is_file() and item.stat().st_size > 0, f"Missing artifact {item}")
    metadata(artifacts[3], version)
    with zipfile.ZipFile(artifacts[0]) as jar:
        require("org/allaymc/api/plugin/Plugin.class" in jar.namelist(),
                "Public Allay API class missing from executable API jar")
    with zipfile.ZipFile(artifacts[1]) as sources:
        require("org/allaymc/api/plugin/Plugin.java" in sources.namelist(),
                "Public Allay API source missing from sources jar")
    with zipfile.ZipFile(artifacts[2]) as docs:
        require("index.html" in docs.namelist(), "Javadoc jar lacks index.html")

    DIST.mkdir(exist_ok=True)
    name = f"Engine-API-{version}-MavenCentral"
    out = DIST / (name + ("-unsigned" if not args.sign else "") + ".zip")

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        payload = {}
        prefix = f"{GROUP.replace('.', '/')}/{ARTIFACT}/{version}/"
        for item in artifacts:
            data = item.read_bytes()
            relative = prefix + item.name
            payload[relative] = data
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                payload[relative + "." + algorithm] = checksum(data, algorithm)

        if args.sign:
            environment, password = gpg_setup(tmp_path / "gnupg")
            for item in artifacts:
                target = tmp_path / item.name
                target.write_bytes(item.read_bytes())
                sig_path = tmp_path / (item.name + ".asc")
                subprocess.run(
                    ["gpg", "--batch", "--yes", "--pinentry-mode", "loopback",
                     "--passphrase-fd", "0", "--armor", "--detach-sign",
                     "--output", str(sig_path), str(target)],
                    input=(password + "\n").encode("utf-8"), env=environment,
                    stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True)
                payload[prefix + item.name + ".asc"] = sig_path.read_bytes()

        with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
            for filename, data in sorted(payload.items()):
                bundle.writestr(filename, data)
        digest = hashlib.sha256(out.read_bytes()).hexdigest()
        (DIST / (out.name + ".sha256")).write_text(
            f"{digest}  {out.name}\n", encoding="utf-8")

    print(f"VALID Maven Central POM, sources, Java API, Javadoc, checksums: {version}")
    print(f"Bundle: {out} (signed={args.sign})")
    print("NOTE: No upload performed. Validation of the Sonatype namespace is still required.")


if __name__ == "__main__":
    main()

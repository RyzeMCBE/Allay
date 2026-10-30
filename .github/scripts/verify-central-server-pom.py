#!/usr/bin/env python3
"""Audit an opt-in Engine server Maven Central POM without uploading it."""
from pathlib import Path
import xml.etree.ElementTree as ET

pom = Path("server/build/publications/maven/pom-default.xml")
assert pom.is_file(), f"Missing generated Maven POM: {pom}"
root = ET.parse(pom).getroot()

def value(node, name):
    found = node.find("{*}" + name)
    return found.text.strip() if found is not None and found.text else None

assert value(root, "groupId") == "io.github.ryzemcbe", value(root, "groupId")
assert value(root, "artifactId") == "engine-server", value(root, "artifactId")
assert value(root, "version") == "0.14.3-beta.1", value(root, "version")
assert value(root, "name")
assert root.find("{*}licenses/{*}license") is not None
assert root.find("{*}developers/{*}developer") is not None
assert root.find("{*}scm/{*}url") is not None

deps = {(value(n, "groupId"), value(n, "artifactId")): value(n, "version")
        for n in root.findall(".//{*}dependency")}
expected = {
    ("io.github.ryzemcbe", "engine-api"): "0.30.0",
    ("io.github.ryzemcbe", "bedrock-connection"): "1.26.60-R1",
    ("io.github.ryzemcbe", "common"): "0.1.1",
    ("io.github.ryzemcbe", "block-updater"): "1.26.60-R1",
    ("io.github.ryzemcbe", "item-updater"): "1.26.50-R1",
}
for coordinate, version in expected.items():
    assert deps.get(coordinate) == version, (
        "Missing/incompatible dependency", coordinate, version, deps.get(coordinate))
    print("OK:", *coordinate, version)
assert ("org.allaymc.allay", "api") not in deps, (
    "Legacy internal Allay API must not leak into the published POM")
assert ("org.allaymc.protocol", "bedrock-connection") not in deps
print("Engine server publication POM verified (transitive Central closure is a separate gate).")

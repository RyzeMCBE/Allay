#!/usr/bin/env bash
# Build the reusable Developer SDK from the Gradle Maven publication.
# It does not compile or provide an example plugin.
set -euo pipefail

version="${1:-0.30.0-SNAPSHOT}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-SNAPSHOT)?$ ]]; then
  echo "::error::Expected X.Y.Z or X.Y.Z-SNAPSHOT SDK version." >&2
  exit 1
fi

root="$(pwd)"
maven_dir="$root/build/engine-sdk-maven"
artifact_dir="$maven_dir/org/ryzemcbe/allay/api/$version"
for name in "api-$version.jar" "api-$version-sources.jar" "api-$version-javadoc.jar" "api-$version.pom"; do
  if [[ ! -s "$artifact_dir/$name" ]]; then
    echo "::error::Missing Maven publication: $artifact_dir/$name" >&2
    exit 1
  fi
done
if [[ ! -s api/build/docs/javadoc/index.html ]]; then
  echo "::error::Javadoc HTML not generated" >&2
  exit 1
fi

listing="$(mktemp)"
jar tf "$artifact_dir/api-$version.jar" > "$listing"
if ! grep -Fxq 'org/allaymc/api/plugin/Plugin.class' "$listing"; then
  echo "::error::Missing stable Allay Plugin API class" >&2
  rm -f "$listing"
  exit 1
fi
rm -f "$listing"

python3 - "$artifact_dir/api-$version.pom" "$version" <<'PY'
import sys
import xml.etree.ElementTree as ET

pom, expected = sys.argv[1:]
root = ET.parse(pom).getroot()
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
def value(tag):
    item = root.find(f"m:{tag}", ns)
    return item.text if item is not None else None
actual = (value("groupId"), value("artifactId"), value("version"))
assert actual == ("org.ryzemcbe.allay", "api", expected), actual
assert root.find("m:dependencies", ns) is not None, "Missing transitive dependency metadata"
print(f"Verified Maven POM: {actual}")
PY

git_sha="$(git rev-parse --short=12 HEAD)"
name="Engine-Developer-SDK-$version-$git_sha"
mkdir -p dist
out="$root/dist"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
bundle="$work/$name"
mkdir -p "$bundle/maven" "$bundle/javadoc" "$bundle/LICENSES"
cp -a "$maven_dir/." "$bundle/maven/"
cp -a api/build/docs/javadoc/. "$bundle/javadoc/"
cp docs/developer-distribution.md "$bundle/README.md"
cp docs/engine-compatibility.md "$bundle/COMPATIBILITY.md"
cp LICENSE "$bundle/LICENSES/Allay-LGPL-3.0.txt"
cp protocol-local/LICENSE "$bundle/LICENSES/Protocol-Apache-2.0.txt"
cp stateupdater-local/LICENSE "$bundle/LICENSES/StateUpdater-LGPL-3.0.txt"
printf '%s\n' "$git_sha" > "$bundle/ENGINE_COMMIT.txt"

# Provide manifest hashes of everything bundled; verify before zipping.
(
 cd "$bundle"
 find . -type f ! -name SHA256SUMS.txt -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS.txt
 sha256sum -c SHA256SUMS.txt > /dev/null
)
(cd "$work" && zip -q -r "$out/$name.zip" "$name")
(cd "$out" && sha256sum "$name.zip" > "$name.zip.sha256" && sha256sum -c "$name.zip.sha256")
echo "Developer SDK ready: $name"
ls -lh "$out/$name.zip" "$out/$name.zip.sha256"

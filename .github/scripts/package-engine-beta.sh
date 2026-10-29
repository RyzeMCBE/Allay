#!/usr/bin/env bash
# Package Engine beta artifacts from a clean, tested tag checkout.
set -euo pipefail

tag="${1:?Usage: package-engine-beta.sh engine-vX.Y.Z-beta.N}"
if [[ ! "$tag" =~ ^engine-v([0-9]+\.[0-9]+\.[0-9]+)-beta\.([1-9][0-9]*)$ ]]; then
  echo "::error::Invalid Engine beta tag: $tag" >&2
  exit 1
fi
base_version="${BASH_REMATCH[1]}"
beta_number="${BASH_REMATCH[2]}"
version="$base_version-beta.$beta_number"
configured="$(sed -n 's/^server.version=//p' gradle.properties | tr -d '\r')"
api_version="$(sed -n 's/^api.version=//p' gradle.properties | tr -d '\r')"
if [[ "$configured" != "$base_version" || -z "$api_version" ]]; then
  echo "::error::Tag version $base_version must match server.version=$configured" >&2
  exit 1
fi

shopt -s nullglob
jars=(server/build/libs/Engine-"$version"-*-shaded.jar)
if (( ${#jars[@]} != 1 )); then
  echo "::error::Expected one executable Engine-$version shaded JAR, got ${#jars[@]}" >&2
  exit 1
fi
api_jars=(api/build/libs/*.jar)
if (( ${#api_jars[@]} == 0 )); then
  echo "::error::API artifacts are missing" >&2
  exit 1
fi

mkdir -p dist
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

jar_name="Engine-$version-linux-x86_64.jar"
cp "${jars[0]}" "dist/$jar_name"

# Server distribution: JAR, licensing, notes and a getting-started guide.
server_dir="$work/server"
mkdir -p "$server_dir/LICENSES"
cp "dist/$jar_name" "$server_dir/"
cp LICENSE "$server_dir/LICENSES/Allay-LGPL-3.0.txt"
cp protocol-local/LICENSE "$server_dir/LICENSES/Protocol-Apache-2.0.txt"
cp stateupdater-local/LICENSE "$server_dir/LICENSES/StateUpdater-LGPL-3.0.txt"
cp docs/engine-beta-distribution.md "$server_dir/START-HERE.md"
cp "docs/releases/$tag.md" "$server_dir/RELEASE-NOTES.md"
(cd "$server_dir" && zip -q -r "$OLDPWD/dist/Engine-$version-server-linux-x86_64.zip" .)

# Developer SDK: exact API binaries, source/Javadoc JARs, POM and documentation.
dev_dir="$work/developers"
mkdir -p "$dev_dir/libs" "$dev_dir/LICENSES"
cp "${api_jars[@]}" "$dev_dir/libs/"
if [[ -f api/build/publications/ryzeApi/pom-default.xml ]]; then
  cp api/build/publications/ryzeApi/pom-default.xml "$dev_dir/engine-api.pom"
fi
cp LICENSE "$dev_dir/LICENSES/Allay-LGPL-3.0.txt"
cp protocol-local/LICENSE "$dev_dir/LICENSES/Protocol-Apache-2.0.txt"
cp stateupdater-local/LICENSE "$dev_dir/LICENSES/StateUpdater-LGPL-3.0.txt"
cp docs/engine-beta-developers.md "$dev_dir/README.md"
cp docs/engine-compatibility.md "$dev_dir/COMPATIBILITY.md"
(cd "$dev_dir" && zip -q -r "$OLDPWD/dist/Engine-$version-developers.zip" .)

# GitHub's automatic source ZIP contains gitlinks, not nested submodule sources.
# Include both pinned dependency source trees and their license texts here.
source_dir="$work/source/Engine-$version"
mkdir -p "$source_dir/protocol-local" "$source_dir/stateupdater-local"
git archive HEAD | tar -xf - -C "$source_dir"
git -C protocol-local archive HEAD | tar -xf - -C "$source_dir/protocol-local"
git -C stateupdater-local archive HEAD | tar -xf - -C "$source_dir/stateupdater-local"
(cd "$work/source" && zip -q -r "$OLDPWD/dist/Engine-$version-source.zip" .)

(
  cd dist
  sha256sum "$jar_name" "Engine-$version-server-linux-x86_64.zip" \
    "Engine-$version-developers.zip" "Engine-$version-source.zip" > SHA256SUMS.txt
  sha256sum --check SHA256SUMS.txt
)
echo "Engine beta assets are ready for tag $tag"
ls -lh dist/

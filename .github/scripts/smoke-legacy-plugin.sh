#!/usr/bin/env bash
# Compile a real plugin against the baseline Allay API and load the resulting
# unmodified class/JAR in Engine. This tests binary compatibility, not merely JSON.
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
work="$(mktemp -d)"
baseline="$work/baseline"
cleanup() {
    git -C "$root" worktree remove --force "$baseline" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

git -C "$root" fetch --no-tags --depth=1 origin main:refs/remotes/origin/main
# Compare against the 1.26.60 branch: its legitimate protocol API updates
# must not be mistaken for changes introduced by the Engine branding.
git -C "$root" fetch --no-tags --depth=1 origin feat/minecraft-1.26.60:refs/remotes/origin/feat/minecraft-1.26.60

# The identity PR must not modify any existing public Allay API source.
if ! git -C "$root" diff --quiet origin/feat/minecraft-1.26.60 HEAD -- api/src/main/java; then
    echo "::error::Engine branding must preserve the existing Allay Java API sources" >&2
    exit 1
fi

git -C "$root" worktree add --detach "$baseline" origin/main
git -C "$baseline" submodule update --init --recursive
(
    cd "$baseline"
    chmod +x gradlew
    ./gradlew :api:jar --no-daemon
)

api_jar="$(find "$baseline/api/build/libs" -maxdepth 1 -type f -name '*.jar' \
    ! -name '*sources*' ! -name '*javadoc*' -print -quit)"
test -n "$api_jar" && test -f "$api_jar"

mkdir -p "$work/source/example" "$work/classes" "$work/plugin-meta" "$work/runner"
cat > "$work/source/example/LegacyAllayPlugin.java" <<'JAVA'
package example;

import org.allaymc.api.plugin.Plugin;

// The compiled bytecode deliberately uses the original Allay API types.
public final class LegacyAllayPlugin extends Plugin {
    @Override
    public void onEnable() {
        if (getPluginContainer() == null) {
            throw new IllegalStateException("Missing legacy PluginContainer");
        }
        if (!"LegacyAllayPlugin".equals(getPluginContainer().descriptor().getName())) {
            throw new IllegalStateException("Legacy plugin descriptor changed");
        }
        System.setProperty("engine.compat.legacyEnabled", "yes");
    }
}
JAVA

javac --release 21 -cp "$api_jar" -d "$work/classes" \
    "$work/source/example/LegacyAllayPlugin.java"

cat > "$work/plugin-meta/plugin.json" <<'JSON'
{
  "name": "LegacyAllayPlugin",
  "entrance": "example.LegacyAllayPlugin",
  "version": "1.0.0",
  "authors": ["Engine compatibility test"],
  "api_version": "^0.30.0"
}
JSON

jar --create --file "$work/LegacyAllayPlugin.jar" \
    -C "$work/classes" . -C "$work/plugin-meta" plugin.json

cat > "$work/runner/EngineLegacyPluginSmoke.java" <<'JAVA'
import java.nio.file.Path;
import org.allaymc.server.Allay;
import org.allaymc.server.plugin.jar.JarPluginLoader;

public final class EngineLegacyPluginSmoke {
    public static void main(String[] args) {
        Allay.initI18n();

        var loader = new JarPluginLoader(Path.of(args[0]));
        var descriptor = loader.loadDescriptor();
        if (!"^0.30.0".equals(descriptor.getAPIVersion())) {
            throw new AssertionError("Legacy api_version descriptor was changed");
        }

        var plugin = loader.loadPlugin().plugin();
        plugin.onLoad();
        plugin.onEnable();
        if (!"yes".equals(System.getProperty("engine.compat.legacyEnabled"))) {
            throw new AssertionError("Old Allay plugin did not enable in Engine");
        }
        plugin.onDisable();
        System.out.println("PASS: baseline Allay plugin JAR loaded and executed in Engine");
    }
}
JAVA

mapfile -t engine_jars < <(find "$root/server/build/libs" -maxdepth 1 -type f \
    -name 'Engine-*-shaded.jar' | sort)
if (( ${#engine_jars[@]} != 1 )); then
    echo "::error::Expected exactly one Engine shaded JAR" >&2
    exit 1
fi
engine_jar="${engine_jars[0]}"

javac --release 21 -cp "$engine_jar" -d "$work/runner" \
    "$work/runner/EngineLegacyPluginSmoke.java"
(
    cd "$work"
    java -Djava.awt.headless=true -cp "$work/runner:$engine_jar" \
        EngineLegacyPluginSmoke "$work/LegacyAllayPlugin.jar"
)

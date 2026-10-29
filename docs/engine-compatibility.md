# Engine: compatibility with existing Allay plugins

Engine is a RyzeMCBE distribution of [AllayMC/Allay](https://github.com/AllayMC/Allay) under LGPL-3.0. Its **identity** changes, not its existing plugin ABI.

## Stable compatibility layer
- Preserve `org.allaymc.api.*`, including `AllayAPI`, and existing method signatures.
- Keep `org.allaymc.server.*` for binary linkage; internal APIs are not guaranteed stable.
- Keep `plugins/`, `extensions/`, `plugin.json` and `extension.json` unchanged.
- Keep `api.version=0.30.0` initially. Engine's product version is separate.
- Preserve legacy Gradle/Maven coordinates, `protocol-local` and `stateupdater-local` composite builds.
- Keep `server-settings.yml`, world and player data paths; existing MOTDs remain user-configured.
- Retain the Java entrypoint `org.allaymc.server.Allay` so existing integrations work.
- Product name reported by `/version` is `Engine`, while `AllayAPI.getCoreName()` intentionally remains `allay` for legacy plugin detection.

Existing `plugin.json` files still work:
```json
{"name":"LegacyPlugin","entrance":"example.LegacyPlugin","version":"1.0.0","authors":["Author"],"api_version":"^0.30.0"}
```

## Gate before merging or releasing
1. Run `git submodule update --init --recursive` and `./gradlew clean build :server:shadowJar --no-daemon`.
2. Confirm `/version` advertises Engine but the API version remains unchanged.
3. Test already compiled Allay plugins: loading, enabling, command/event registration and disabling. Do **not** recompile to hide ABI incompatibilities.
4. Load copies of existing worlds and player data; test clean shutdown and restart.
5. Build Docker with checked out submodules, and keep existing volume `allaymc-data` for deployed worlds.
6. Verify licenses and notices of shaded third-party dependencies before public JAR distribution.
7. Keep the Minecraft 1.26.60/NetherNet integration in its independent PR until gameplay is verified.

A plugin tied to undocumented internals or exact `coreName=allay` may need a compatibility shim after testing. The branding PR alone is not a guarantee that every third-party plugin works.

Engine is not affiliated with Microsoft or Mojang. Preserve upstream AllayMC, CloudburstMC, and other third-party notices. The upstream logo is not used as Engine's branding.

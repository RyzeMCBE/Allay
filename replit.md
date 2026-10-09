# Allay

A third-party Minecraft: Bedrock Edition server written from scratch in Java 21, aiming to be reliable, fast, and feature-rich.

- **GitHub**: https://github.com/AllayMC/Allay
- **Docs**: https://docs.allaymc.org
- **License**: LGPL-3.0 (server/api), MIT (data, codegen)

## Project Structure

| Module | Purpose |
|--------|---------|
| `api/` | Public plugin contracts and interfaces |
| `server/` | Runtime implementation, JUnit tests (`src/test/`), JMH benchmarks (`src/jmh/`) |
| `data/` | Data-processing tools and runtime data |
| `codegen/` | Java source-generation tools |
| `docs/` | MkDocs documentation site |

Dependency versions live in `gradle/libs.versions.toml`. CI workflows are in `.github/workflows/`.

## Build & Run Commands

Requires **Java 21** and the included Gradle wrapper.

Initialize the pinned protocol revision before building. Do not use
`--remote`: that option ignores the tested gitlink and can pull an
incompatible protocol serializer.

```bash
git submodule sync --recursive
git submodule update --init --recursive
```

```bash
# Compile all modules, run tests, produce artifacts
./gradlew build

# Run JUnit tests only
./gradlew :server:test

# Build the distributable shaded JAR
./gradlew :server:shadowJar

# Launch the server locally (working dir: .run/)
./gradlew :server:runShadow

# Run JMH benchmarks
./gradlew :server:jmh

# Generate JaCoCo coverage report
./gradlew :server:jacocoTestReport
```

The shaded JAR is output to `server/build/libs/allay-server-*-shaded.jar`.

## Running with Docker

```bash
docker compose up
```

The server listens on **port 19132** (UDP + TCP).

## Coding Conventions

- UTF-8, 4-space indentation, Java brace style
- `PascalCase` for types, `camelCase` for methods/fields, `UPPER_SNAKE_CASE` for constants
- Packages lowercase under `org.allaymc`
- Lombok is used extensively — match nearby code
- No `@NonNull`/`@Nullable` annotations (project policy)
- All Javadoc and comments in English

## User Preferences

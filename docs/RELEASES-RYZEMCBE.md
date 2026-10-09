# Versiones beta y oficiales de RyzeMCBE Allay

El servidor utiliza etiquetas **Git independientes de la versión de la API**.
La publicación automática está configurada en
[`.github/workflows/release.yml`](../.github/workflows/release.yml).

## Formatos de etiquetas

| Canal | Etiqueta | Resultado |
| --- | --- | --- |
| Beta | `v0.14.1-beta.1` | GitHub Release marcado como **Pre-release**, con `Allay-0.14.1-beta.1.jar` |
| Beta siguiente | `v0.14.1-beta.2` | Nueva pre-release con su JAR propio |
| Oficial | `v0.14.1` | GitHub Release **estable** con `Allay-0.14.1.jar` |

La versión base debe coincidir con `server.version` en `gradle.properties`
del commit etiquetado (actualmente `0.14.1`). Para la versión `0.14.2`,
modifica primero esa propiedad en `main` y verifica su compilación.

No se usa `api-v...` para publicar el servidor. Ese formato sigue reservado
para publicar versiones de la API Maven mediante `publish-api.yml`.

## Crear etiquetas desde GitHub (incluso desde un celular)

1. En [RyzeMCBE/Allay](https://github.com/RyzeMCBE/Allay), entra a
   **Releases → Draft a new release**.
2. En **Choose a tag**, escribe `v0.14.1-beta.1` para beta o
   `v0.14.1` para oficial.
3. Crea la etiqueta apuntando al commit deseado de `main`. **No marques
   una release manual sin artefactos**: el flujo de Actions necesita crearla
   tras probar el software.
4. En vez de publicar manualmente la release desde el formulario, puedes crear
   solo la etiqueta mediante **Git**, como se muestra a continuación.
   Crear o publicar una release manual antes de que finalice Actions puede
   provocar que la publicación automatizada se rechace porque ya existe.

**Procedimiento recomendado (Git):**

```bash
git clone --recurse-submodules https://github.com/RyzeMCBE/Allay.git
cd Allay
git switch main
git pull --ff-only
git tag v0.14.1-beta.1
git push origin v0.14.1-beta.1
```

Para la versión oficial, una vez verificada la beta:

```bash
git switch main
git pull --ff-only
git tag v0.14.1
git push origin v0.14.1
```

**No recrees ni muevas etiquetas ya publicadas.** Utiliza una beta nueva o
incrementa la versión para una nueva versión estable.

## Qué realiza GitHub Actions

Al recibir un tag `vX.Y.Z` o `vX.Y.Z-beta.N`:

1. Descarga Allay junto con los submódulos `protocol-local` y
   `stateupdater-local` registrados en ese commit.
2. Valida el formato y que el commit esté integrado en `main`.
3. Ejecuta compilación limpia y pruebas con **Java 21** y Gradle.
4. Compila un JAR ejecutable con el nombre interno de la versión del tag.
5. Adjunta `Allay-<versión>.jar` y
   `Allay-<versión>.jar.sha256` al run de Actions.
6. Crea un [GitHub Release](https://github.com/RyzeMCBE/Allay/releases),
   marcado como preliminar para las betas y oficial para las estables,
   con los mismos dos archivos descargables.

**Nunca publica JAR si la compilación o las pruebas fallan.**
La versión oficial se compila con `allay.is-dev-build=false` sin cambiar
permanentemente `gradle.properties` de `main`. Los informes de pruebas
se conservan en Actions para diagnósticos.

## Probar el workflow sin publicar una release

Desde **Actions → Allay · Beta y versión oficial → Run workflow**,
selecciona `main` e ingresa `v0.14.1-beta.1` como `version_tag`.
La ejecución manual realiza las validaciones, pruebas y genera el JAR
en **Artifacts**, pero **no publica una GitHub Release** ni crea una etiqueta.
Es recomendable ejecutar primero esta prueba antes de publicar tags reales.

## Verificar el archivo

Tras descargar el JAR y el archivo SHA-256 del mismo lanzamiento:

```bash
sha256sum -c Allay-0.14.1-beta.1.jar.sha256
java -jar Allay-0.14.1-beta.1.jar
```

La suma SHA-256 valida la integridad de la descarga, pero una compilación
exitosa **no sustituye las pruebas con clientes Bedrock reales**.

## Diferencia entre artefactos de Actions y Releases

Los artefactos de Actions son descargas temporales para depuración.
Los archivos adjuntos a GitHub Releases permiten a los usuarios encontrar
cada beta y cada versión oficial desde un único lugar sin depender del
historial de compilaciones.

## La API para desarrolladores

El versionado de la API es independiente del servidor:
`api.version` se mantiene en `gradle.properties` y su publicación
Maven se gestiona mediante
[`publish-api.yml`](../.github/workflows/publish-api.yml).
Un tag de servidor `v0.14.1` **no publica** por sí solo una versión
nueva de la API.

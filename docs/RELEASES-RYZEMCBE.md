# Versiones beta y oficiales de RyzeMCBE Allay

El servidor utiliza etiquetas **Git independientes de la versión de la API**.
La publicación automática está configurada en
[`.github/workflows/release.yml`](../.github/workflows/release.yml).

## Formatos de etiquetas

| Canal | Etiqueta | Resultado |
| --- | --- | --- |
| Beta | `v0.14.2-beta.1` | GitHub Release marcado como **Pre-release**, con `Allay-0.14.2-beta.1.jar` |
| Beta siguiente | `v0.14.2-beta.2` | Nueva pre-release con su JAR propio |
| Oficial | `v0.14.2` | GitHub Release **estable** con `Allay-0.14.2.jar` |

La versión base debe coincidir con `server.version` en `gradle.properties`
del commit etiquetado (actualmente `0.14.2`). Para una versión posterior, actualiza primero esa propiedad en `main` y verifica su compilación.

No se usa `api-v...` para publicar el servidor. Ese formato sigue reservado
para publicar versiones de la API Maven mediante `publish-api.yml`.

## Crear etiquetas desde GitHub (incluso desde un celular)

1. En [RyzeMCBE/Allay](https://github.com/RyzeMCBE/Allay), entra a
   **Releases → Draft a new release**.
2. En **Choose a tag**, escribe `v0.14.2-beta.1` para beta o
   `v0.14.2` para oficial.
3. Crea la etiqueta apuntando al commit deseado de `main`. **No marques
   una release manual sin artefactos**: el flujo de Actions necesita crearla
   tras probar el software.
4. En vez de publicar manualmente la release desde el formulario, puedes crear
   solo la etiqueta mediante **Git**, como se muestra a continuación.
   Si ya publicaste una release sin adjuntar el JAR, ahora puedes recuperarla
   sin cambiar el tag mediante la ejecución manual descrita más abajo.

**Procedimiento recomendado (Git):**

```bash
git clone --recurse-submodules https://github.com/RyzeMCBE/Allay.git
cd Allay
git switch main
git pull --ff-only
git tag v0.14.2-beta.1
git push origin v0.14.2-beta.1
```

Para la versión oficial, una vez verificada la beta:

```bash
git switch main
git pull --ff-only
git tag v0.14.2
git push origin v0.14.2
```

**No recrees ni muevas etiquetas ya publicadas.** Los ejemplos anteriores
solo se usan si la etiqueta todavía no existe. Si `v0.14.2` ya existe, no vuelvas
a crearla: usa la recuperación descrita abajo. Para una versión futura utiliza
una etiqueta y versión nuevas.

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
   con los mismos dos archivos descargables y las notas de `docs/releases/<tag>.md` si existen.
   Si el release ya existe, **adjunta solo los archivos que faltan** sin
   reemplazar su contenido, sus notas ni sus archivos publicados.

**Nunca publica JAR si la compilación o las pruebas fallan.**
La versión oficial se compila con `allay.is-dev-build=false` sin cambiar
permanentemente `gradle.properties` de `main`. Los informes de pruebas
se conservan en Actions para diagnósticos.

## Probar el workflow sin publicar una release

Desde **Actions → Allay · Beta y versión oficial → Run workflow**,
selecciona `main` e ingresa `v0.14.2-beta.1` como `version_tag`.
La ejecución manual realiza las validaciones, pruebas y genera el JAR
en **Artifacts**, pero **no publica una GitHub Release** ni crea una etiqueta.
Es recomendable ejecutar primero esta prueba antes de publicar tags reales.

## Recuperar un release ya publicado sin JAR

Si el tag `v0.14.2` ya existe y la publicación manual dejó una release sin
archivos, **no borres ni muevas la etiqueta ni vuelvas a publicar el release**.
La compilación de la etiqueta puede haber terminado correctamente aunque el paso
de publicación haya fallado al detectar que ya existía una release.

En GitHub:

1. Abre [Actions → Allay · Beta y versión oficial](https://github.com/RyzeMCBE/Allay/actions/workflows/release.yml).
2. Selecciona **Run workflow** y elige la rama **`main`**, porque allí está la
   corrección del procedimiento de publicación.
3. En **version_tag**, escribe **`v0.14.2`**.
4. Marca **`publish_existing_release`** como **true**.
5. Ejecuta el workflow. Este comprueba que el tag existe, descarga exactamente
   el commit etiquetado con sus submódulos, compila, ejecuta pruebas, genera
   `Allay-0.14.2.jar` y el SHA-256, y adjunta únicamente los archivos ausentes
   de la release existente.

La recuperación **no sobrescribe** los assets ya publicados ni altera las
notas manuales del release. Si seleccionas `publish_existing_release=false`,
el workflow solo compila y conserva los artefactos en Actions, sin publicarlos.
La recuperación manual nunca crea automáticamente un release que no existe.

**Importante:** si el workflow había fallado en un tag antes de incorporar
esta corrección, **reintentar aquel workflow antiguo reutilizará su definición
original**. Para recuperar `v0.14.2`, usa **Run workflow en `main`** y la
opción `publish_existing_release=true`.

## Verificar el archivo

Tras descargar el JAR y el archivo SHA-256 del mismo lanzamiento:

```bash
sha256sum -c Allay-0.14.2-beta.1.jar.sha256
java -jar Allay-0.14.2-beta.1.jar
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
Un tag de servidor `v0.14.2` **no publica** por sí solo una versión
nueva de la API.

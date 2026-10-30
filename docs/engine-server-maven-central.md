# Preparar `engine-server` para Maven Central

## Estado

**Solo propuesta en pull request: no publicar todavía.** El namespace
`io.github.ryzemcbe` ya está verificado por el propietario.

El artefacto de desarrollo propuesto es:

```text
io.github.ryzemcbe:engine-server:0.14.3-beta.1
```

Esta biblioteca contiene las clases del módulo Gradle `:server` y debe
ser compatible con los plugins que utilizan implementación nativa.
**No es** la distribución `shadowJar` ejecutable de Engine.

La publicación de la API existente
`io.github.ryzemcbe:engine-api:0.30.0` se mantiene independiente.

## Modo de validación opt-in

La propiedad `-PcentralRelease=true` elige las coordenadas de
`engine-server` para Maven Central y **cambia las dependencias
declaradas del servidor** a las coordenadas propuestas para sus
bibliotecas de primer nivel, sin alterar la compilación normal:

- `io.github.ryzemcbe:engine-api:0.30.0`
- `io.github.ryzemcbe:bedrock-connection:1.26.60-R1`
- `io.github.ryzemcbe:common:0.1.1`
- `io.github.ryzemcbe:block-updater:1.26.60-R1`
- `io.github.ryzemcbe:item-updater:1.26.50-R1`

Las publicaciones anteriores de Protocol y StateUpdater deben estar
**PUBLISHED** y ser resolubles desde Maven Central antes de crear una
versión definitiva de Engine Server.

El modo de desarrollo habitual no cambia `server.version` ni usa estas
coordenadas nuevas.

## Revisar POM sin subir nada

```bash
./gradlew :server:generatePomFileForMavenPublication \
  -PcentralRelease=true --no-daemon
python3 .github/scripts/verify-central-server-pom.py
```

Esto comprueba el namespace, la versión y las dependencias entre los
módulos de la organización. **No** prueba que todas las bibliotecas
transitivas de terceros estén disponibles en Maven Central.

## Bloqueos de publicación

1. Publicar y verificar Protocol y StateUpdater con sus dependencias
   externas accesibles desde Maven Central.
2. Auditar el POM completo de Engine Server y asegurar que todas las
   dependencias externas se resuelven desde `mavenCentral()`, sin
   repositorios de terceros, JitPack ni snapshots inaccesibles.
3. Verificar JAR normal, sources, Javadoc, firmas y licencias.
4. Probar HCF con `engine-server` publicado, sin descargar
   ningún JAR de servidor manualmente.
5. Realizar un despliegue firmado y manual en Sonatype; revisarlo en
   https://central.sonatype.com/publishing y confirmar Publish.

**No ejecutar `publishToMavenCentral` antes de completar esta lista.**
No se reemplazan ni retaggean versiones ya publicadas.

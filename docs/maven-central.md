# Publicar Engine API en Maven Central

**Estado:** preparación técnica en el PR. NO se debe anunciar la API como publicada en Maven Central hasta que Sonatype valide la distribución y ésta aparezca en https://central.sonatype.com/.

## Objetivo

Los developers podrán usar la API pública de Engine con **Java 21** y sólo `mavenCentral()`:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("io.github.ryzemcbe:engine-api:0.30.0")
}
```

**La dependencia anterior no existe todavía**: requiere verificar el namespace `io.github.ryzemcbe` y completar el despliegue firmado. La versión `0.30.0` corresponde a la API compatible con Allay, pero el nombre Maven `engine-api` es nuevo. Los imports de Java y el descriptor `plugin.json` mantienen `org.allaymc.api.*` y `api_version: "^0.30.0"`. No se crea ningún plugin de ejemplo.

## Qué automatizamos

La rama contiene una publicación Gradle **separada** `centralApi`, con POM de licencia, autores, URL y SCM completos; JAR, sources y Javadoc. La publicación anterior `ryzeApi` para GitHub Packages y el SDK local sigue intacta.

El workflow [Engine API · Maven Central](../.github/workflows/publish-central-api.yml) hace:
1. Compilar la API y producir una estructura de repositorio Maven local (`build/central-api-maven/`).
2. Verificar las coordenadas `io.github.ryzemcbe:engine-api:0.30.0`, licencia, autores, SCM, clases públicas, fuentes, Javadoc y metadatos de dependencias.
3. Crear una **vista previa sin firma**, sólo para auditoría en Actions. **No se puede subir a Sonatype.**
4. Sólo cuando un responsable ejecuta el workflow manualmente con `publish=true`, una etiqueta exacta `api-v0.30.0` y los cuatro secretos, firma artefactos y los envía al Central Portal en modo **USER_MANAGED**.
5. Sonatype valida el despliegue; el responsable debe aprobar **Publish** en el portal. No existe publicación automática desde PR ni push a `main`.

Este proceso no altera la release beta del servidor ni renombra las clases Java de Allay.

## Requisitos de Sonatype que debe completar el propietario

1. Entra en [Sonatype Central Portal](https://central.sonatype.com/) con tu cuenta autorizada y solicita el namespace **`io.github.ryzemcbe`** en **Namespaces**. No presupongas que está concedido: Sonatype asigna namespaces personales de GitHub automáticamente, **pero no el de una organización**. Para una organización, completa la verificación que Sonatype solicite (puede requerir crear un repositorio público temporal con la clave de verificación dentro de `RyzeMCBE`).
2. Prepara una clave GPG de firma, publica su **clave pública** en un servidor de claves compatible, y conserva la **clave privada** en lugar seguro. Para Sonatype las firmas `.asc` son obligatorias.
3. En Central Portal, **Account → Generate User Token**, genera las credenciales específicas de publicación. No uses ni copies tu contraseña de acceso normal.
4. En GitHub, abre **RyzeMCBE/Engine → Settings → Secrets and variables → Actions → New repository secret** y crea exactamente estos secretos:
   - `ENGINE_CENTRAL_TOKEN_USERNAME`: usuario del token de Sonatype
   - `ENGINE_CENTRAL_TOKEN_PASSWORD`: contraseña del token de Sonatype
   - `ENGINE_CENTRAL_GPG_PRIVATE_KEY`: clave privada GPG completa (formato ASCII-armored)
   - `ENGINE_CENTRAL_GPG_PASSPHRASE`: contraseña de esa clave GPG

**Nunca envíes las credenciales, tokens ni claves privadas por chat, un issue, un PR o en archivos del repositorio.** El workflow consume los secretos directamente desde GitHub Actions. Si no puedes crear secretos en la organización, solicita permisos a quien administre su configuración.

### Crear la primera versión pública cuando el namespace esté aprobado

1. Espera a que el PR del workflow pase CI y esté integrado en `main`.
2. En `RyzeMCBE/Engine` crea la etiqueta `api-v0.30.0` **desde la revisión establecida de main**. No reutilices una etiqueta con otro commit: Maven Central no permite reemplazar la versión publicada.
3. Ve a **Actions → Engine API · Maven Central (manual, signed) → Run workflow**, selecciona `main`, escribe `0.30.0` y marca `publish=true`.
4. El workflow extrae exactamente `api-v0.30.0`, exige que esté integrado en `main` y revisa los secretos. Compila, firma, genera checksums y sube el ZIP a Sonatype en modo `USER_MANAGED`.
5. Abre [Central Portal → Publishing](https://central.sonatype.com/publishing), revisa el despliegue y los avisos. Cuando esté **VALIDATED** pulsa **Publish**.
6. Comprueba que [Engine API en Maven Central](https://central.sonatype.com/search?q=io.github.ryzemcbe) y el repositorio Maven Central permiten resolver `io.github.ryzemcbe:engine-api:0.30.0` antes de anunciar la versión como disponible.

La publicación inicial puede requerir revisión de Sonatype. La ausencia de una versión en Central tras ejecutar el workflow **no demuestra un fallo** si todavía no se ha aprobado.

## Auditoría antes del lanzamiento

- Prueba el consumo desde un proyecto externo con **sólo Maven Central**, validando que se resuelven las dependencias transitivas declaradas en el POM.
- Preserva atribuciones LGPL-3.0 de Allay y licencias de dependencias. La publicación de esta API no presupone que todo el servidor sombreado esté listo para redistribución estable.
- Si Sonatype rechaza `io.github.ryzemcbe`, se deberá aprobar otro `groupId` antes de modificar el POM o anunciar dependencias; **no publiques un identificador que no esté verificado**.

## Fuentes

- [Registrar un namespace](https://central.sonatype.org/register/namespace/)
- [API de publicación de Central Portal](https://central.sonatype.org/publish/publish-portal-api/)
- [Requisitos de POM, firmas y checksum](https://central.sonatype.org/publish/requirements/)

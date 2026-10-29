# Engine Developer SDK — distribución de la API

**Engine** es un software de servidor Bedrock basado en Allay. Este kit es exclusivamente para desarrolladores: **no contiene un ejemplo de plugin**, no modifica la API heredada y no es un JAR ejecutable de servidor.

## Versiones y compatibilidad

| Campo | Versión |
| --- | --- |
| Servidor de referencia | Engine 0.14.3 Beta 1, Minecraft Bedrock 1.26.60 |
| API binaria para descriptores `plugin.json` | `0.30.0` |
| Dependencia disponible en GitHub Packages | `org.ryzemcbe.allay:api:0.30.0-SNAPSHOT` |
| Java requerido | 21 |
| Nombre de las clases públicas | `org.allaymc.api.*` — se mantiene |

El sufijo Maven **SNAPSHOT no modifica** `api_version` de un `plugin.json`: se sigue utilizando `^0.30.0`. Las versiones SNAPSHOT pueden reemplazarse con nuevas compilaciones; para un SDK reproducible, guarda el ZIP y el commit indicado en su nombre.

## Descargar el SDK

- [GitHub Actions: Engine Developer SDK](https://github.com/RyzeMCBE/Engine/actions/workflows/developer-sdk.yml): abre una ejecución correcta, entra en **Artifacts** y descarga `engine-developer-sdk-...`.
- [Beta existente](https://github.com/RyzeMCBE/Engine/releases/tag/engine-v0.14.3-beta.1): incluye `Engine-0.14.3-beta.1-developers.zip` de la beta inicial. Esta entrega del SDK es independiente y no modifica los archivos de la beta publicada.

El paquete independiente incluye:
- `maven/org/ryzemcbe/allay/api/<versión>/`: artefacto de API, POM y metadata de Gradle/Maven, fuentes y Javadoc empaquetados;
- `javadoc/`: páginas HTML de la API para lectura local;
- `LICENSES/`: licencias principales del núcleo y dependencias fuente;
- `COMPATIBILITY.md`, `README.md` y `SHA256SUMS.txt` internos.

Todos los paquetes incluyen su SHA-256 externo para comprobar que la descarga llegó íntegra.

## Método 1: Gradle con GitHub Packages

Esta API está publicada en GitHub Packages desde el workflow de publicación de `main`. **GitHub Packages puede requerir autenticación incluso con repositorios públicos**. Utiliza tu propio nombre de usuario de GitHub y un token con permiso `read:packages`; no subas tokens a Git ni los pegues en un issue.

Agrega a tu `build.gradle.kts` (solo configuración de dependencias):

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.pkg.github.com/RyzeMCBE/Engine") {
        credentials {
            username = providers.gradleProperty("gpr.user")
                .orElse(providers.environmentVariable("GITHUB_ACTOR")).getOrElse("")
            password = providers.gradleProperty("gpr.key")
                .orElse(providers.environmentVariable("GITHUB_TOKEN")).getOrElse("")
        }
    }
    maven("https://repo.opencollab.dev/maven-releases/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
}

dependencies {
    compileOnly("org.ryzemcbe.allay:api:0.30.0-SNAPSHOT")
}
```

Configura `gpr.user` y `gpr.key` en `~/.gradle/gradle.properties` (fuera del proyecto) o las variables de entorno indicadas. Otras dependencias transitivas siguen descargándose desde repositorios Maven.

## Método 2: Gradle con el SDK descargado (sin credenciales de GitHub)

Extrae el ZIP del SDK en una carpeta local, por ejemplo `/opt/engine-sdk/`, y utiliza su repositorio Maven incluido:

```kotlin
repositories {
    maven { url = uri("/opt/engine-sdk/maven") }
    mavenCentral()
    maven("https://repo.opencollab.dev/maven-releases/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
}

dependencies {
    compileOnly("org.ryzemcbe.allay:api:0.30.0-SNAPSHOT")
}
```

**Sin credenciales GitHub** significa que el repositorio de la API es local; las dependencias externas pueden necesitar conexión a internet. Ajusta la ruta a tu sistema.

## Método 3: Maven

En `pom.xml`, agrega el repositorio y la dependencia; usa `provided` para no incluir la API del servidor en tu plugin:

```xml
<repositories>
  <repository>
    <id>engine-github</id>
    <url>https://maven.pkg.github.com/RyzeMCBE/Engine</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>org.ryzemcbe.allay</groupId>
    <artifactId>api</artifactId>
    <version>0.30.0-SNAPSHOT</version>
    <scope>provided</scope>
  </dependency>
</dependencies>
```

Para GitHub Packages, utiliza `~/.m2/settings.xml` con un servidor `id=engine-github` y credenciales personales; **no** incorpores contraseñas ni tokens a `pom.xml`. Si trabajas sin GitHub Packages, cambia la URL del repositorio a `file:///ruta/al/sdk/maven`.

## API y migración

- Los imports existentes `org.allaymc.api.*`, `org.allaymc.server.*` y los descriptores `plugin.json` / `extension.json` permanecen compatibles.
- `AllayAPI.getInstance().getCoreName()` conserva `allay` para detección heredada; el nombre visual del producto es **Engine**.
- No incrustes la API del servidor en el JAR de tu plugin: usa `compileOnly` en Gradle o `provided` en Maven.
- La versión Maven `org.ryzemcbe.allay:api` es **una distribución nueva**, sin alterar las coordenadas de dependencias internas originales de Protocol/StateUpdater.
- La compatibilidad de plugins con APIs internas no públicas y dependencias de terceros debe comprobarse caso por caso.

Consulta [política de compatibilidad](engine-compatibility.md) y el [código fuente](https://github.com/RyzeMCBE/Engine). No se requiere ejemplo de plugin para instalar ni distribuir el SDK.

## Licencias y atribución

La API procede de Allay bajo LGPL-3.0. Protocol utiliza Apache-2.0 y StateUpdater LGPL-3.0. El SDK contiene los textos de licencia originales; revisa también las licencias de dependencias transitivas para tu caso de redistribución. Engine no está afiliado a Microsoft ni Mojang.

# Desarrollo de plugins con RyzeMCBE Allay y VS Code

RyzeMCBE ofrece una API Java 21 para desarrollar plugins del fork de Allay.
La dependencia de Maven se publica mediante GitHub Packages y proporciona
autocompletado, navegación de clases y código fuente en VS Code.

## Configuración del entorno

Instala **JDK 21** y las extensiones **Extension Pack for Java** y
**Gradle for Java** de Visual Studio Code. Abre una carpeta que contenga
un proyecto Gradle Java existente.

El registro Maven de GitHub Packages exige credenciales incluso para
descargar paquetes públicos. Utiliza un token personal clásico de GitHub
con permiso **read:packages** y guárdalo fuera del repositorio.

En `~/.gradle/gradle.properties`:

```properties
gpr.user=USUARIO_GITHUB
gpr.key=TOKEN_PERSONAL
```

También puedes establecer las variables de entorno `GITHUB_ACTOR` y
`GITHUB_TOKEN`. Nunca publiques credenciales ni las añadas a Git.

## Añadir la dependencia de RyzeMCBE

En el `build.gradle.kts` de tu propio plugin:

```kotlin
plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/RyzeMCBE/Allay")
        credentials {
            username = providers.gradleProperty("gpr.user")
                .orElse(providers.environmentVariable("GITHUB_ACTOR")).getOrElse("")
            password = providers.gradleProperty("gpr.key")
                .orElse(providers.environmentVariable("GITHUB_TOKEN")).getOrElse("")
        }
    }
}

dependencies {
    compileOnly("org.ryzemcbe.allay:api:0.30.0-SNAPSHOT")
}
```

La versión `0.30.0-SNAPSHOT` es una **versión prevista** para la rama
`main`; antes de incorporarla verifica que el workflow de publicación
haya terminado correctamente y que el paquete exista en GitHub Packages.
Cuando se publique `api-v0.30.0`, se podrá usar la versión fija `0.30.0`.

## Habilitar el autocompletado

Abre la carpeta raíz del plugin en VS Code, espera la sincronización de
Gradle y selecciona Java 21. Puedes probar a importar:

```java
import org.allaymc.api.player.Player;
```

La extensión Java utiliza el archivo JAR para sugerir tipos y métodos,
y descarga las fuentes para navegar entre definiciones. Si falta
autocompletado, ejecuta la sincronización de Gradle o
**Java: Clean Java Language Server Workspace** desde la paleta de comandos.

La API debe declararse con `compileOnly`: no la integres dentro del
JAR del plugin porque el servidor ya proporciona esas clases.

## Versiones y publicación

- `api.version` se administra en `gradle.properties` del servidor.
- `main` publica `<api.version>-SNAPSHOT` después de ejecutar pruebas.
- Un GitHub Release etiquetado `api-vX.Y.Z` publica la versión estable
  `X.Y.Z` solamente cuando coincide con `api.version`.
- El workflow utiliza el token temporal de Actions con permisos
  `packages: write`. No requiere guardar un secreto adicional para
  publicar dentro del mismo repositorio.
- Los artefactos tienen coordenadas
  **`org.ryzemcbe.allay:api:<versión>`** y contienen el JAR de la
  biblioteca, fuentes y Javadoc.

Consulta [Publicar Allay API](../.github/workflows/publish-api.yml).
La plantilla oficial de plugins se implementará en una etapa posterior.

## Problemas habituales

**401/403**: revisa el token clásico `read:packages` y el acceso al
paquete. **404**: comprueba que la versión se haya publicado.
**Clases sin resolver**: verifica el JDK, la sincronización de Gradle
y que el archivo `build.gradle.kts` esté abierto como proyecto Gradle.

Esta API puede contener cambios respecto de la distribución original
de Allay. Usa una versión fija para proyectos de producción y comprueba
la compatibilidad con la versión del servidor.

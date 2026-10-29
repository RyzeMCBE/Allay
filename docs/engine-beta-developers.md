# Engine SDK 0.14.3-beta.1 — plugins Java 21

El kit de desarrollo corresponde a Engine con Minecraft Bedrock 1.26.60. Su **API de compatibilidad es Allay 0.30.0**. No se renombraron las clases públicas `org.allaymc.api.*`, los paquetes `org.cloudburstmc.protocol.*` ni los descriptores de plugins.

## Contenido del ZIP para desarrolladores

- `libs/api-0.30.0.jar` (o el JAR de API con el nombre de la versión construida).
- `libs/*-sources.jar` y `libs/*-javadoc.jar` para consultar implementaciones y documentación.
- `engine-api.pom` con las dependencias declaradas en la publicación Maven de esta beta.
- `COMPATIBILITY.md` y los archivos `LICENSES/`.

## Compilar contra el JAR de API descargado

Por ejemplo, en un proyecto Gradle Kotlin DSL con el kit extraído en una carpeta `libs/` del proyecto:

```kotlin
plugins {
    java
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    compileOnly(files("libs/api-0.30.0.jar"))
}
```

Si tu plugin necesita otras librerías expuestas por la API (Guava, NBT, SLF4J, Gson, etc.), declara esas dependencias de compilación explícitamente. El JAR del SDK no las incluye todas. Alternativamente usa el proyecto fuente con Gradle para obtener metadatos de dependencias.

El `engine-api.pom` del ZIP emplea la versión de SDK `0.30.0-engine-beta.1`; **no implica que esa versión ya esté publicada en GitHub Packages**. Para publicar paquetes Maven de forma remota hay que habilitar y verificar el registro correspondiente.

## Descriptor compatible

Conserva el descriptor Allay original. Un ejemplo mínimo:

```json
{
  "name": "MiPlugin",
  "entrance": "com.ejemplo.MiPlugin",
  "version": "1.0.0",
  "authors": ["Desarrollador"],
  "api_version": "^0.30.0"
}
```

El punto de entrada Java debe implementar o extender la interfaz/clase de plugin que requiera la API actual. Los plugins existentes **no necesitan un nuevo descriptor Engine**.

## Publicar localmente la API con Gradle

Desde una copia del repositorio de Engine con los submódulos inicializados:

```bash
git clone --recurse-submodules https://github.com/RyzeMCBE/Allay.git Engine
cd Engine
git checkout engine-v0.14.3-beta.1
git submodule update --init --recursive
./gradlew :api:publishRyzeApiPublicationToMavenLocal \
  -Pryzemcbe.apiVersion=0.30.0-engine-beta.1 --no-daemon
```

Después de publicarlo en Maven Local puedes declararlo como dependencia `compileOnly("org.ryzemcbe.allay:api:0.30.0-engine-beta.1")` si tienes `mavenLocal()` en `repositories`. Este identificador es para la distribución del SDK; la comprobación de plugins dentro del servidor mantiene `api_version=0.30.0`.

## Compatibilidad garantizada y límites

El pipeline del PR de Engine prueba un plugin binario compilado con Allay y lo ejecuta sin recompilación. No se garantiza que plugins que hagan reflexión sobre clases internas, usen APIs inestables o asuman versiones específicas del protocolo funcionen sin modificaciones.

Código fuente, atribuciones y notas: [Engine en GitHub](https://github.com/RyzeMCBE/Allay) · [Política de compatibilidad](engine-compatibility.md).

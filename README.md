<div align="center">

# ENGINE

### Servidor de Minecraft: Bedrock Edition · Java 21

**Una plataforma Bedrock mantenida por RyzeMCBE Development Studio, basada en Allay y orientada a servidores y desarrolladores de plugins.**

[![Build](https://github.com/RyzeMCBE/Engine/actions/workflows/gradle.yml/badge.svg)](https://github.com/RyzeMCBE/Engine/actions/workflows/gradle.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://adoptium.net/)
[![Engine API](https://img.shields.io/badge/Engine%20API-0.30.0-2563eb)](https://central.sonatype.com/artifact/io.github.ryzemcbe/engine-api/0.30.0)
[![Licencia](https://img.shields.io/badge/License-LGPL--3.0-0f766e)](LICENSE)
[![Server](https://img.shields.io/badge/Server-0.14.3%20Beta-7c3aed)](https://github.com/RyzeMCBE/Engine/releases/tag/engine-v0.14.3-beta.1)

[**API para developers**](#-desarrollo-con-engine-api) · [**Descargar servidor**](#-descargar-y-ejecutar-engine) · [**Compatibilidad**](#-compatibilidad-con-allay) · [**Compilar**](#-compilar-desde-el-código-fuente) · [**Documentación**](docs/index.md)

</div>

---

## ✨ ¿Qué es Engine?

**Engine** es una distribución independiente de servidor para **Minecraft Bedrock Edition**, desarrollada en **Java 21** a partir de [AllayMC/Allay](https://github.com/AllayMC/Allay). Mantiene la API pública de Allay para facilitar el uso de plugins existentes y ofrece una distribución propia de la API para quienes desarrollan extensiones.

- **Para administradores:** servidor Bedrock con soporte multiversión, mundos y dimensiones, y una beta distribuida para Linux.
- **Para desarrolladores:** dependencia Maven de Engine API, fuentes, Javadoc y compatibilidad con el modelo de plugins de Allay.
- **Para contribuidores:** código abierto, compilación reproducible con Gradle y dependencias Protocol/StateUpdater enlazadas por submódulos.

> [!IMPORTANT]
> **Engine Server sigue en fase beta.** El soporte de una versión de protocolo en el código no garantiza que todos los dispositivos, mundos, plugins y escenarios de red hayan sido verificados dentro del juego. Prueba tus datos en una copia antes de migrar.

### Enlaces rápidos

| Recurso | Acceso |
| --- | --- |
| Engine API 0.30.0 | [Maven Central](https://central.sonatype.com/artifact/io.github.ryzemcbe/engine-api/0.30.0) |
| Descargas del servidor y SDK | [GitHub Releases](https://github.com/RyzeMCBE/Engine/releases) |
| Guía del SDK y alternativa sin Maven Central | [Developer SDK](docs/developer-distribution.md) |
| Distribución de la beta de Linux | [Instalación del servidor](docs/engine-beta-distribution.md) |
| Reportar problemas | [Issues](https://github.com/RyzeMCBE/Engine/issues) |

## 🧩 Desarrollo con Engine API

La API es una dependencia **para compilar plugins**; no es el JAR ejecutable del servidor. Utiliza **JDK 21** y declara la API como `compileOnly` (Gradle) o `provided` (Maven). **No empaquetes el JAR de Engine API dentro de tu plugin**: el servidor proporciona esas clases al ejecutarlo.

### Gradle — Kotlin DSL (`build.gradle.kts`)

```kotlin
plugins {
    java
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("io.github.ryzemcbe:engine-api:0.30.0")
}
```

### Gradle — Groovy DSL (`build.gradle`)

```groovy
plugins {
    id 'java'
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly 'io.github.ryzemcbe:engine-api:0.30.0'
}
```

### Maven — `pom.xml`

Maven Central es el repositorio predeterminado de Maven; no necesitas añadir un repositorio adicional para Engine API.

```xml
<properties>
    <maven.compiler.release>21</maven.compiler.release>
</properties>

<dependencies>
    <dependency>
        <groupId>io.github.ryzemcbe</groupId>
        <artifactId>engine-api</artifactId>
        <version>0.30.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

> [!NOTE]
> La publicación de `0.30.0` se realizó a través de Sonatype. Si Gradle o Maven responde **404** al buscar esta versión, comprueba primero su [disponibilidad en Maven Central](https://central.sonatype.com/artifact/io.github.ryzemcbe/engine-api/0.30.0) y la sincronización del repositorio. No confundas el envío o la validación en Sonatype con la disponibilidad pública del artefacto.

### Compatibilidad del código y descriptores

La **coordenada Maven es nueva**, pero **no** se han renombrado los paquetes Java ni se exige migrar el descriptor de un plugin Allay.

| Concepto | Qué utilizar |
| --- | --- |
| Dependencia nueva (Maven Central) | `io.github.ryzemcbe:engine-api:0.30.0` |
| Imports Java públicos | `org.allaymc.api.*` |
| API en `plugin.json` | `"api_version": "^0.30.0"` |
| JDK | 21 |
| Compilación sin incluir la API en el plugin | `compileOnly` / `provided` |

El `api_version` del descriptor **no** es el `groupId` de Maven. Si tu proyecto ya funciona con la API pública Allay 0.30.0, puedes conservar sus imports y el descriptor; prueba siempre el resultado en el servidor Engine concreto. Las APIs internas (`org.allaymc.server.*`), reflexión y dependencias de terceros no tienen la misma garantía de estabilidad.

**Fuentes, Javadoc y autocompletado:** los artefactos `-sources.jar` y `-javadoc.jar` acompañan la publicación Maven. Puedes utilizarlos desde IntelliJ IDEA, VS Code u otro IDE Java tras sincronizar el proyecto.

**Otras vías de distribución:** el [Developer SDK](docs/developer-distribution.md) también incluye un repositorio Maven local y explica GitHub Packages con la coordenada heredada `org.ryzemcbe.allay:api:0.30.0-SNAPSHOT`. Esa coordenada **no es** la de Maven Central y GitHub Packages puede requerir credenciales. El SDK no incluye un proyecto de plugin de ejemplo.

## 🚀 Descargar y ejecutar Engine

La distribución de referencia es **Engine 0.14.3 Beta 1**, para **Linux glibc x86_64** con **Java 21**. La beta incorpora soporte registrado para Minecraft Bedrock 1.26.60, además de versiones anteriores de protocolo incluidas en el fork.

1. Descarga [Engine 0.14.3 Beta 1](https://github.com/RyzeMCBE/Engine/releases/tag/engine-v0.14.3-beta.1), preferiblemente el paquete `server-linux-x86_64.zip`.
2. Extrae el paquete y consulta su `SHA256SUMS.txt`.
3. Ejecuta el JAR de servidor desde una carpeta de datos dedicada:

```bash
java -version
java -jar /ruta/al/Engine-0.14.3-beta.1-linux-x86_64.jar
```

Para clientes Bedrock 1.26.60, la configuración predeterminada utiliza **TCP 19132** para señalización NetherNet y **UDP 19132** para tráfico NetherNet/RakNet. Revisa la [guía de red para Linux](docs/bedrock-1.26.60-nethernet-linux.md), especialmente si trabajas con Docker, NAT o proxies.

> [!WARNING]
> La beta es experimental. No reemplaces directamente tu servidor de producción ni reutilices mundos, plugins o archivos de configuración sin copias de seguridad. Consulta la [guía de instalación](docs/engine-beta-distribution.md).

## ⚡ Funciones y áreas de trabajo

| Área | Descripción |
| --- | --- |
| **Bedrock multiversión** | Codecs internacionales registrados desde **1.21.90 (818)** hasta **1.26.60 (2225)**, más variantes NetEase específicas. No se admiten automáticamente todas las revisiones intermedias. |
| **NetherNet y RakNet** | Transporte para clientes recientes y anteriores, sujeto a las pruebas de red y plataforma. |
| **Plugins JVM** | API Java y descriptores compatibles con Allay 0.30.0. |
| **Mundos y dimensiones** | Overworld, Nether, End, LevelDB y modos de almacenamiento `SHARED` / `SEPARATE`. |
| **Portales y transferencias** | Reglas de destino, protección temporal y transferencia de jugadores entre dimensiones. |
| **Desarrollo y pruebas** | Gradle, pruebas automatizadas, Protocol y StateUpdater fijados mediante submódulos. |

La tabla resume componentes implementados, **no una certificación de todas sus funciones**. Los protocolos registrados se mantienen en [`ProtocolRegistry.java`](server/src/main/java/org/allaymc/server/network/protocol/ProtocolRegistry.java). Para cambios de mundo y dimensión, consulta la [documentación](docs/index.md) y realiza pruebas con copias de tus datos.

## 🛠️ Compilar desde el código fuente

Requisitos: **JDK 21**, Git y las herramientas necesarias para Gradle. Clona los submódulos de Protocol y StateUpdater incluidos por el proyecto.

```bash
git clone --recurse-submodules https://github.com/RyzeMCBE/Engine.git
cd Engine

git submodule sync --recursive
git submodule update --init --recursive

chmod +x gradlew
./gradlew build --no-daemon
./gradlew :server:shadowJar --no-daemon
```

Los JAR generados se guardan en `server/build/libs/`. Usa el **nombre real del archivo** producido por tu compilación. Para iniciar el servidor desde Gradle:

```bash
./gradlew :server:runShadow
```

Para desarrollar dentro del repositorio:

```bash
./gradlew :server:test
./gradlew :server:jacocoTestReport
```

Los módulos principales son `api/`, `server/`, `data/` y `codegen/`; los repositorios [Protocol](https://github.com/RyzeMCBE/Protocol) y [StateUpdater](https://github.com/RyzeMCBE/StateUpdater) se montan en `protocol-local/` y `stateupdater-local/`. La compilación del fork **necesita ambos submódulos**, pero consumir la dependencia de Maven Central no requiere clonar Engine.

## 🔄 Versiones y distribución

Las versiones de **Engine Server** y **Engine API** son independientes:

| Producto | Versión de referencia | Canal |
| --- | --- | --- |
| **Engine Server** | `0.14.3-beta.1` | [Beta en GitHub Releases](https://github.com/RyzeMCBE/Engine/releases) |
| **Engine API** | `0.30.0` | [Artefacto Maven Central](https://central.sonatype.com/artifact/io.github.ryzemcbe/engine-api/0.30.0) |
| **Developer SDK** | `0.30.0-SNAPSHOT` (coordenada heredada) | [SDK descargable / GitHub Packages](docs/developer-distribution.md) |

Cambiar el README, mejorar el servidor o corregir errores internos **no implica publicar una API nueva**. Si cambian los contratos públicos de la API y quieres distribuirlos, necesitarás una versión Maven nueva; las versiones ya publicadas son inmutables.

[GitHub Actions](https://github.com/RyzeMCBE/Engine/actions) · [Guía de publicación de la API](docs/maven-central.md) · [Política de compatibilidad](docs/engine-compatibility.md)

## 🤝 Comunidad y contribuciones

Reporta errores y propuestas en [Issues](https://github.com/RyzeMCBE/Engine/issues). Incluye versión de Engine, versión y protocolo del cliente Bedrock, sistema operativo, pasos para reproducirlo y logs **sin credenciales ni datos privados**. Antes de enviar un PR, consulta [AGENTS.md](AGENTS.md) y [CONTRIBUTING.md](CONTRIBUTING.md).

## 📜 Licencia y créditos

Engine es un fork independiente de [AllayMC/Allay](https://github.com/AllayMC/Allay). Conserva su API pública, los créditos de sus autores y la licencia **[LGPL-3.0](LICENSE)**. Los módulos `data/` y `codegen/` incluyen sus respectivas licencias MIT; otras bibliotecas tienen licencias propias. Consulta los avisos correspondientes al redistribuir el servidor o el SDK.

<div align="center">

**RyzeMCBE Development Studio**

[GitHub](https://github.com/RyzeMCBE) · [Engine](https://github.com/RyzeMCBE/Engine) · [AllayMC (upstream)](https://github.com/AllayMC/Allay)

<sub>Proyecto independiente. Minecraft es una marca de Mojang Studios. Engine no está afiliado a Mojang Studios ni a Microsoft.</sub>

</div>

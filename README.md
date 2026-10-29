<div align="center">

# ENGINE

### Software de servidor para Minecraft Bedrock Edition

**Engine · RyzeMCBE Development Studio**  
**Compatible con plugins de Allay que utilizan la API pública conservada.**

[![Compilación](https://github.com/RyzeMCBE/Allay/actions/workflows/gradle.yml/badge.svg)](https://github.com/RyzeMCBE/Allay/actions/workflows/gradle.yml)
[![Java 21](https://img.shields.io/badge/Java-21-f97316)](https://github.com/RyzeMCBE/Allay)
[![Licencia LGPL-3.0](https://img.shields.io/badge/Licencia-LGPL--3.0-blue)](LICENSE)

[**Código**](https://github.com/RyzeMCBE/Allay) · [**Compilaciones**](https://github.com/RyzeMCBE/Allay/actions) · [**Compatibilidad**](docs/engine-compatibility.md) · [**Incidencias**](https://github.com/RyzeMCBE/Allay/issues)

</div>

---

## 📖 ¿Qué es Engine?

**Engine** es una distribución para **Minecraft: Bedrock Edition**, desarrollada en **Java 21** y basada en Allay. Separa la API pública del servidor y conserva compatibilidad con plugins existentes.

Engine deriva de [AllayMC/Allay](https://github.com/AllayMC/Allay) y conserva sus atribuciones y obligaciones LGPL-3.0. Protocol y StateUpdater mantienen inicialmente sus coordenadas originales. Consulta la [guía de compatibilidad](docs/engine-compatibility.md).

> [!IMPORTANT]
> **Estado: desarrollo activo y validación.** El código integrado en `main` superó compilación y pruebas automatizadas. Eso **no garantiza que todos los mundos personalizados y clientes hayan sido probados dentro de Minecraft**. Verifica el [workflow de compilación](https://github.com/RyzeMCBE/Allay/actions/workflows/gradle.yml) de la revisión que vayas a utilizar.

## 🚀 Características y áreas de trabajo

| Área | Descripción |
| --- | --- |
| 🌐 **Multiversión** | Protocolos Bedrock internacionales específicos desde 1.21.90 (818) hasta 1.26.52 (2193) y variantes NetEase. |
| ⚡ **Rendimiento** | Herramientas y diseño orientados a un servidor eficiente sobre la JVM. |
| 🧩 **Plugins** | API para extensiones en Java y otros lenguajes compatibles con la JVM. |
| 🌍 **Mundos y dimensiones** | Overworld, Nether y End, LevelDB `SHARED`/`SEPARATE`, rutas configurables, conversiones y mapas personalizados. |
| 🚪 **Portales** | Portales Nether/End, controles de reentrada, llegada segura y coordenadas configurables mediante API de plugins. |
| 🧱 **Contenido de Bedrock** | Bloques, ítems, entidades, inventarios y serialización vinculada a protocolos. |
| 🧪 **Pruebas** | Gradle y regresiones automatizadas para protocolos, mundos y transferencias; deben complementarse con pruebas dentro del juego. |

La presencia de un componente en el código no garantiza que todas sus mecánicas estén verificadas en este fork.

🔗 **Dependencias locales del fork:** [RyzeMCBE/Protocol](https://github.com/RyzeMCBE/Protocol) (`protocol-local`) y [RyzeMCBE/StateUpdater](https://github.com/RyzeMCBE/StateUpdater) (`stateupdater-local`). Ambos repositorios están enlazados mediante submódulos Git y builds compuestos de Gradle.

## 🌐 Multiversión: versiones soportadas por el código

Engine (basado en Allay) utiliza un registro de **protocolos exactos**, separados por variante de cliente. Las versiones internacionales registradas en [`ProtocolRegistry.java`](server/src/main/java/org/allaymc/server/network/protocol/ProtocolRegistry.java), contrastadas con los codecs de [RyzeMCBE/Protocol](https://github.com/RyzeMCBE/Protocol), son:

| Minecraft Bedrock internacional | Protocolo |
| --- | ---: |
| 1.21.90 | 818 |
| 1.21.93 | 819 |
| 1.21.100 | 827 |
| 1.21.111 | 844 |
| 1.21.120 | 859 |
| 1.21.124 | 860 |
| 1.21.130 | 898 |
| 1.26.0 | 924 |
| 1.26.10 | 944 |
| 1.26.20 | 975 |
| 1.26.30 | 1001 |
| 1.26.40 | 2168 |
| 1.26.45 | 2169 |
| **1.26.50–1.26.52** | **2193** |
| **1.26.60** | **2225** |

**Versión más reciente en esta rama de integración: Minecraft Bedrock 1.26.60 (protocolo 2225).** Los clientes 1.26.60 utilizan NetherNet (señalización TCP + tráfico de juego UDP); versiones anteriores conservan RakNet. La entrada de un cliente 1.26.60 ya fue confirmada, pero las comprobaciones completas de juego y retrocompatibilidad siguen abiertas en [#43](https://github.com/RyzeMCBE/Allay/issues/43). **Versión internacional más antigua registrada: 1.21.90 (818).** No debe interpretarse esta tabla como garantía de compatibilidad con todas las revisiones intermedias o versiones anteriores a las registradas.

Se incluyen por separado los codecs **NetEase 766, 819 y 860**. El servidor identifica la variante y el protocolo en el proceso de conexión; NetEase no equivale a la misma versión del cliente internacional.

La compatibilidad de protocolo **no significa que todos los bloques, mundos, plugins y transferencias estén certificados en juego**. Para reproducir problemas, anota versión exacta del cliente y protocolo.

La configuración predeterminada para Linux x86_64 comparte el número de puerto **19132 TCP/UDP** entre NetherNet y RakNet; consulta la [guía de conexión de 1.26.60](docs/bedrock-1.26.60-nethernet-linux.md). Las plataformas distintas de Linux glibc x86_64 no se consideran verificadas por esta distribución.

## 🌍 Dimensiones, mundos y almacenamiento

El núcleo incluye **Overworld** (`minecraft:overworld`), **Nether** (`minecraft:nether`) y **The End** (`minecraft:the_end`), más las interfaces para tipos de dimensión personalizados.

- **`SHARED` (valor predeterminado):** las tres dimensiones pueden almacenarse en el mismo LevelDB del mundo.
- **`SEPARATE`:** usa directorios y bases LevelDB independientes; `world-settings.yml` permite asignar una ruta `path` a cada dimensión y habilitar/deshabilitar dimensiones.
- **Importación:** reconoce determinadas disposiciones de datos Bedrock y LevelDB heredado de PocketMine, paletas NBT y subchunks, y puede leer el spawn guardado de un Nether o End personalizado.
- **Protección del almacenamiento:** verificación de rutas para evitar solapamientos, preparación de conversiones, copias de seguridad, manifiestos y recuperación frente a cambios interrumpidos. Una incompatibilidad no debe provocar que se cargue silenciosamente un mundo vacío donde había datos existentes.

Ejemplo de `world-settings.yml` con carpetas independientes para un mundo llamado `world`:

```yaml
worlds:
  world:
    storage-type: LEVELDB
    use-virtual-thread: false
    dimension-storage-mode: SEPARATE
    dimensions:
      minecraft:overworld:
        path: world
        generator-type: FLAT
        generator-preset: ''
      minecraft:nether:
        path: world-nether
        generator-type: VOID
        generator-preset: ''
      minecraft:the_end:
        path: world-end
        generator-type: VOID
        generator-preset: ''
```

Las rutas son relativas a la carpeta de mundos y deben corresponder a la estructura real de tus mapas. Para mapas personalizados ya construidos, el ejemplo usa `VOID` en Nether y End para no generar terreno adicional; **el registro de generadores de este núcleo contiene actualmente `VOID` y `FLAT`, no generadores llamados `NETHER` o `THE_END`**. **Detén el servidor y guarda una copia completa de los mundos antes de importar un mapa o alternar entre `SHARED` y `SEPARATE`.** La verificación final de un mapa importado debe realizarse dentro del juego.

## 🚪 Portales Nether y End

- **Nether ↔ Overworld:** búsqueda de portales existentes, generación cuando el destino es seguro, escala de coordenadas predeterminada y preparación de chunks sin bloquear el hilo principal.
- **Prevención de reentrada:** evita que el jugador vuelva a atravesar inmediatamente el portal cuando permanece dentro tras un cambio de dimensión.
- **Sincronización Bedrock:** se resuelve la coordenada final antes de transmitir el cambio; las colas de chunks de dimensiones anteriores se descartan y la confirmación se envía en el orden previsto.
- **End personalizado:** prioriza el spawn importado, busca una superficie segura y solo construye una plataforma de obsidiana cuando no hay terreno existente que deba preservarse.
- **API de destinos configurables:** un plugin puede usar `EntityPortalEnterEvent#setDestinationOverride(Location3d)`. Sin destino indicado, se conserva la lógica normal. Las coordenadas se validan antes de teletransportar o crear un portal.

## 🛡️ Inmunidad durante la carga de dimensiones

Al iniciarse una transferencia, el jugador obtiene **inmunidad temporal a daños**. Mientras esté protegido, tampoco puede atacar; esto evita ventajas injustas en PvP. El sistema no congela físicamente al jugador y **no fuerza el cierre** de la pantalla de carga de Bedrock.

La protección termina cuando el cliente comunica `END_LOADING_SCREEN` y el servidor valida un primer movimiento real. Por seguridad también expira tras **3 segundos desde el aviso** si el jugador no se mueve, o tras **65 segundos desde el inicio del cambio** si el aviso nunca llega.

Las pruebas unitarias cubren la inmunidad durante una pantalla de carga de 30 segundos, el fin de la carga, los límites de tiempo y las transiciones repetidas.

## 📌 Versiones de la API y del servidor

Esta rama mantiene `server.version=0.14.3` y `api.version=0.30.0`. El nombre público de la distribución es **Engine**, mientras la API binaria, la línea de versión y el identificador interno `allay` se conservan para plugins anteriores. Todavía es una **versión de desarrollo**, no un release estable de Engine. Consulta la [guía de compatibilidad](docs/engine-compatibility.md) y los PR de integración.

## 🏗️ Estructura del repositorio

~~~text
Engine/ (repositorio RyzeMCBE/Allay)
├── api/                   API pública y contratos para plugins
├── server/                Implementación del servidor y pruebas
├── data/                  Datos y recursos auxiliares
├── codegen/               Herramientas de generación de código
├── docs/                  Documentación y recursos gráficos
├── gradle/                Wrapper y catálogo de dependencias
├── .github/workflows/     Compilación automática
├── build.gradle.kts       Configuración principal de Gradle
├── settings.gradle.kts    Módulos y resolución del protocolo
├── .gitmodules            Referencias a Protocol y StateUpdater
└── gradlew                Ejecutor Gradle para Linux/macOS
~~~

El código fuente y las atribuciones a AllayMC y las bibliotecas derivadas se conservan en los repositorios. Este cambio de identidad no requiere migrar o renombrar los paquetes Java de los plugins.

## 🛠️ Requisitos

- **JDK 21** configurado.
- Git.
- Acceso a las dependencias Maven/Gradle necesarias.
- Submódulos locales **protocol-local** y **stateupdater-local** correctamente inicializados.
- En Linux, permisos de ejecución para el Gradle Wrapper.

### ⚠️ Dependencia local de protocolo

En este fork, el archivo <code>.gitmodules</code> declara el submódulo **[RyzeMCBE/Protocol](https://github.com/RyzeMCBE/Protocol)** en <code>protocol-local</code> y **[RyzeMCBE/StateUpdater](https://github.com/RyzeMCBE/StateUpdater)** en <code>stateupdater-local</code>.

Además, <code>settings.gradle.kts</code> requiere que exista un archivo de prueba de regresión específico:

~~~text
protocol-local/bedrock-codec/src/test/java/org/cloudburstmc/protocol/bedrock/codec/v2168/serializer/ItemStackResponseSerializer_v2168Test.java
~~~

**Actualización:** ambos submódulos están enlazados a los repositorios de RyzeMCBE y `main` ha superado la compilación y las pruebas automatizadas. Utiliza `--recurse-submodules` al clonar y conserva las comprobaciones de regresión del protocolo.

## 📥 Clonar y compilar

En Ubuntu o cualquier distribución Linux con Java 21:

~~~bash
git clone --recurse-submodules https://github.com/RyzeMCBE/Allay.git
cd Allay

git submodule sync --recursive
git submodule update --init --recursive

chmod +x gradlew
./gradlew build --no-daemon
~~~

Para generar únicamente el JAR distribuible con dependencias incluidas:

~~~bash
./gradlew :server:shadowJar --no-daemon
~~~

El archivo generado, **si la compilación tiene éxito**, debería encontrarse en <code>server/build/libs/</code> y seguir un patrón parecido a:

~~~text
allay-server-<versión>-<commit>-shaded.jar
~~~

En Windows, utiliza <code>gradlew.bat</code> en lugar de <code>./gradlew</code>.

## ▶️ Iniciar el servidor

Después de obtener un JAR válido, crea un directorio para ejecutar Allay y utiliza el nombre real del archivo:

~~~bash
java -jar allay-server-<versión>-<commit>-shaded.jar
~~~

Para desarrollo, el proyecto también contempla:

~~~bash
./gradlew :server:runShadow
~~~

> [!WARNING]
> Antes de modificar mundos, dimensiones o portales en un servidor con jugadores, haz una copia de seguridad. Verifica siempre que la revisión elegida admite los datos y el protocolo del cliente utilizado.

## 🔁 Compilaciones automáticas y versiones beta/oficiales

El workflow [`.github/workflows/gradle.yml`](.github/workflows/gradle.yml)
ejecuta compilación y pruebas en `main` y pull requests, y conserva el JAR
como artefacto temporal de GitHub Actions.

El workflow [`.github/workflows/release.yml`](.github/workflows/release.yml)
publica **GitHub Releases con el JAR ejecutable y SHA-256** al crear
etiquetas verificadas en `main`:

- **Beta:** `v0.14.1-beta.1` → `Allay-0.14.1-beta.1.jar` (pre-release).
- **Oficial:** `v0.14.1` → `Allay-0.14.1.jar` (estable).

Antes de publicar, Gradle ejecuta las pruebas y genera un JAR limpio con
el número de versión correspondiente al tag. También se puede lanzar
manualmente desde Actions para validar y generar artefactos **sin publicar**.

**[Guía completa de versiones beta y oficiales](docs/RELEASES-RYZEMCBE.md)** ·
[**GitHub Releases**](https://github.com/RyzeMCBE/Allay/releases) ·
[**GitHub Actions**](https://github.com/RyzeMCBE/Allay/actions)

## 🧩 Desarrollo de plugins con VS Code

La API propia de RyzeMCBE puede consumirse como dependencia Gradle para obtener
**autocompletado Java, navegación a fuentes y documentación Javadoc**.
La publicación de la API es independiente del JAR del servidor y utiliza las
coordenadas `org.ryzemcbe.allay:api` en GitHub Packages.

- En `main` se publican versiones `<api.version>-SNAPSHOT`.
- Un GitHub Release con etiqueta `api-vX.Y.Z` publica una versión fija,
  previa compilación y pruebas.
- GitHub Packages requiere autenticación con un token clásico
  `read:packages` para los consumidores. No introduzcas tokens en el repositorio.

**Guía paso a paso:** [Desarrollo de plugins y configuración de VS Code](docs/DESARROLLO-PLUGINS.md).
**Proceso automatizado:** [Publicación de Allay API](.github/workflows/publish-api.yml).

**Nota:** verifica que el paquete esté publicado antes de declararlo como
dependencia; compilar el servidor no garantiza por sí solo que GitHub Packages
haya aceptado la publicación.

## 🧪 Comandos útiles para desarrolladores

~~~bash
# Compilar todos los módulos y ejecutar las pruebas
./gradlew build

# Ejecutar pruebas del módulo servidor
./gradlew :server:test

# Crear el JAR ejecutable
./gradlew :server:shadowJar

# Generar el informe de cobertura
./gradlew :server:jacocoTestReport
~~~

## 🤝 Contribuciones y reportes

Puedes presentar propuestas, correcciones o reportes mediante las [issues](https://github.com/RyzeMCBE/Allay/issues) y los pull requests cuando estén habilitados. Revisa [CONTRIBUTING.md](CONTRIBUTING.md) y [AGENTS.md](AGENTS.md) antes de enviar cambios.

Para informar de un error incluye la versión exacta de Bedrock, protocolo, sistema operativo, revisión de Git, pasos para reproducirlo y registros relevantes. **Nunca compartas contraseñas, tokens o datos privados en logs públicos.**

## ⚖️ Licencias y créditos

Allay fue creado originalmente por el equipo de **[AllayMC](https://github.com/AllayMC)**. Este repositorio es un fork independiente mantenido por RyzeMCBE: reconocemos y conservamos los créditos de sus autores y colaboradores.

El proyecto principal incluye la licencia **[LGPL-3.0](LICENSE)**; los directorios <code>data/</code> y <code>codegen/</code> contienen sus respectivas licencias **MIT**. Otros recursos pueden tener condiciones propias; consulta los avisos incluidos.

También agradecemos a los proyectos citados en el repositorio original: [Endstone](https://github.com/EndstoneMC/Endstone), [Cloudburst Protocol](https://github.com/CloudburstMC/Protocol), [df-mc](https://github.com/df-mc), [gophertunnel](https://github.com/Sandertv/gophertunnel), [PocketMine-MP](https://github.com/pmmp/PocketMine-MP) y [YourKit](https://www.yourkit.com/).

---

<div align="center">

### 🦊 RyzeMCBE Development Studio

**Desarrollo de software, compatibilidad y herramientas para comunidades.**

[Organización](https://github.com/RyzeMCBE) · [Nuestro fork](https://github.com/RyzeMCBE/Allay) · [Proyecto original](https://github.com/AllayMC/Allay)

<sub>Proyecto independiente. Minecraft es una marca de Mojang Studios. No afiliado con Mojang Studios ni con Microsoft.</sub>

</div>

<div align="center">

<img src="docs/assets/logo/allay-chan-640x.png" alt="Logotipo de Allay" width="170">

# Allay · RyzeMCBE

### Software de servidor para Minecraft Bedrock Edition

**Fork de Allay mantenido por RyzeMCBE Development Studio.**

[![Compilación](https://github.com/RyzeMCBE/Allay/actions/workflows/gradle.yml/badge.svg)](https://github.com/RyzeMCBE/Allay/actions/workflows/gradle.yml)
[![Java 21](https://img.shields.io/badge/Java-21-f97316)](https://github.com/RyzeMCBE/Allay)
[![Licencia](https://img.shields.io/badge/Licencia-LGPL--3.0-blue)](LICENSE)
[![Estado](https://img.shields.io/badge/Estado-En%20desarrollo-orange)](https://github.com/RyzeMCBE/Allay/actions)

[**Código fuente**](https://github.com/RyzeMCBE/Allay) · [**Compilaciones**](https://github.com/RyzeMCBE/Allay/actions) · [**Reportar problemas**](https://github.com/RyzeMCBE/Allay/issues) · [**Organización**](https://github.com/RyzeMCBE)

</div>

---

## 📖 ¿Qué es Allay?

**Allay** es un software de servidor independiente para **Minecraft: Bedrock Edition**, desarrollado en **Java 21**. Su arquitectura separa la API pública de la implementación del servidor y permite crear extensiones para la JVM.

Este repositorio contiene un **fork de trabajo de [AllayMC/Allay](https://github.com/AllayMC/Allay)** bajo la organización **RyzeMCBE Development Studio**. Lo utilizamos como base para estudiar, integrar y mantener funcionalidades relacionadas con protocolos, compatibilidad, mundos, dimensiones y rendimiento.

> [!IMPORTANT]
> **Estado de este fork: desarrollo y validación.** El código está disponible en la rama principal, y la compilación anterior falló cuando aún faltaba el submódulo del protocolo; este ya fue enlazado y la compilación debe volver a verificarse. No afirmamos que esta revisión esté lista para producción. Comprueba el [estado actual de GitHub Actions](https://github.com/RyzeMCBE/Allay/actions) antes de utilizarla.

## 🚀 Características y áreas de trabajo

| Área | Descripción |
| --- | --- |
| 🌐 **Multiversión** | Arquitectura para gestionar familias de protocolos Bedrock. La compatibilidad real depende de las implementaciones y pruebas de cada versión. |
| ⚡ **Rendimiento** | Herramientas y diseño orientados a un servidor eficiente sobre la JVM. |
| 🧩 **Plugins** | API para extensiones en Java y otros lenguajes compatibles con la JVM. |
| 🌍 **Mundos y dimensiones** | Sistemas de mundo con Overworld, Nether, End y soporte de tipos de dimensión personalizables. |
| 🚪 **Portales** | Componentes y eventos relacionados con portales del Nether y del End. |
| 🧱 **Contenido de Bedrock** | Bloques, ítems, entidades, inventarios y serialización vinculada a protocolos. |
| 🧪 **Pruebas** | Tareas Gradle, pruebas automatizadas y pruebas de regresión. |

La presencia de un componente en el código no garantiza que todas sus mecánicas estén verificadas en este fork.

🔗 **Biblioteca de protocolo utilizada:** [RyzeMCBE/Protocol](https://github.com/RyzeMCBE/Protocol) (submódulo `protocol-local`).

## 🆕 Novedades documentadas

El archivo [CHANGELOG.md](CHANGELOG.md) identifica la línea **Allay 0.14.1 / API 0.30.0** como *no publicada* y enumera, entre otros cambios:

- Incorporación de soporte para **Minecraft Bedrock 1.26.40** (protocolo **v2168**).
- Soporte para **NetEase 1.21.124** (protocolo **v860**).
- Nueva arquitectura multiversión **mvv2**.
- Corrección de recogida de flechas con efectos y encantamiento Infinity.

Las versiones anteriores del historial también documentan APIs para dimensiones y biomas personalizados, sistemas de portales, cambios de inventarios y otras mecánicas.

**Importante:** estas son funcionalidades descritas en el código importado, **no una lista de versiones certificadas** por RyzeMCBE. No se garantiza compatibilidad con todas las versiones de Bedrock ni con versiones posteriores a las que aparecen en el historial.

## 🏗️ Estructura del repositorio

~~~text
Allay/
├── api/                   API pública y contratos para plugins
├── server/                Implementación del servidor y pruebas
├── data/                  Datos y recursos auxiliares
├── codegen/               Herramientas de generación de código
├── docs/                  Documentación y recursos gráficos
├── gradle/                Wrapper y catálogo de dependencias
├── .github/workflows/     Compilación automática
├── build.gradle.kts       Configuración principal de Gradle
├── settings.gradle.kts    Módulos y resolución del protocolo
├── .gitmodules            Referencia al repositorio de protocolo
└── gradlew                Ejecutor Gradle para Linux/macOS
~~~

El código fuente se encuentra directamente en el repositorio: **ya no existe el archivo comprimido Allay-master.zip**.

## 🛠️ Requisitos

- **JDK 21** configurado.
- Git.
- Acceso a las dependencias Maven/Gradle necesarias.
- Código correcto del protocolo local **protocol-local**.
- En Linux, permisos de ejecución para el Gradle Wrapper.

### ⚠️ Dependencia local de protocolo

En este fork, el archivo <code>.gitmodules</code> declara el submódulo **[Mykoss/Protocol](https://github.com/Mykoss/Protocol)** con la ruta <code>protocol-local</code>.

Además, <code>settings.gradle.kts</code> requiere que exista un archivo de prueba de regresión específico:

~~~text
protocol-local/bedrock-codec/src/test/java/org/cloudburstmc/protocol/bedrock/codec/v2168/serializer/ItemStackResponseSerializer_v2168Test.java
~~~

**Actualización:** el submódulo `protocol-local` ya está conectado a [RyzeMCBE/Protocol](https://github.com/RyzeMCBE/Protocol), y el repositorio de protocolo incluye el archivo de regresión indicado. Para descargarlo debes clonar con `--recurse-submodules` o ejecutar los comandos de sincronización siguientes. La compilación y el funcionamiento del servidor todavía requieren validación; no recomendamos desactivar esta comprobación.

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

## 🔁 Compilación automática en GitHub

El archivo [<code>.github/workflows/gradle.yml</code>](.github/workflows/gradle.yml) configura **GitHub Actions** para:

- Ejecutar Gradle con **Java 21**.
- Iniciarse al actualizar <code>main</code>, abrir o actualizar pull requests, publicar etiquetas que comiencen por <code>v</code>, o lanzarlo manualmente.
- Ejecutar <code>./gradlew build --no-daemon</code>.
- Subir los JAR de <code>server/build/libs/</code> como **artefactos descargables** solamente cuando la compilación finalice correctamente.

**Los artefactos de Actions no se publican automáticamente como GitHub Releases.**

Consulta el [historial de compilaciones](https://github.com/RyzeMCBE/Allay/actions/workflows/gradle.yml) para comprobar resultados y descargar el JAR de una ejecución exitosa.

## 🧩 Desarrollo de plugins

Los plugins pueden desarrollarse en Java u otros lenguajes de la JVM. Como referencia del ecosistema original:

- [Plantilla para Java](https://github.com/AllayMC/JavaPluginTemplate)
- [Plantilla para Kotlin](https://github.com/MineBuilders/allaymc-kotlin-plugin-template)
- [Plantilla para Scala](https://github.com/AllayMC/ScalaPluginTemplate)
- [AllayGradle](https://github.com/AllayMC/AllayGradle)

Ejemplo para utilizar una **versión publicada de la API original** en Gradle Kotlin DSL:

~~~kotlin
repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.allaymc.allay:api:<versión-publicada>")
}
~~~

No supongas que las versiones publicadas por AllayMC incluyen todas las modificaciones internas de RyzeMCBE.

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

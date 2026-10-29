# Engine 0.14.3-beta.1 — Minecraft Bedrock 1.26.60

**Canal: BETA PÚBLICA — no recomendada para producción.**

Engine es una distribución derivada de Allay y desarrollada por RyzeMCBE. Esta primera beta combina la identidad de Engine con los codecs de Minecraft Bedrock **1.26.60 (protocolo 2225)**, su transporte NetherNet y la compatibilidad con versiones anteriores mediante RakNet.

## Qué se incluye

- **Servidor Linux x86_64 (glibc), Java 21**: JAR ejecutable; NetherNet usa UDP 19132 y señalización TCP 19132, compartiendo el número de puerto con RakNet.
- **Kit de desarrollo**: JAR de la API binariamente compatible con Allay, fuentes, Javadoc, POM y guía de desarrollo.
- **Código fuente completo** de la revisión de Engine con los submódulos Protocol y StateUpdater fijados por commit.
- Licencias de los proyectos principales, guía de distribución y sumas SHA-256.

## Compatibilidad con plugins

Se mantienen `org.allaymc.api.*`, las firmas públicas existentes, el identificador `AllayAPI.getCoreName() = allay`, los descriptores `plugin.json` y `extension.json`, y la línea de API `0.30.0`. La marca visible es **Engine**.

Se completaron las pruebas automáticas de compilación y una prueba de un plugin compilado contra Allay que carga en Engine sin recompilar. Esto **no certifica todos los plugins externos**, especialmente los que usan clases internas.

## Pruebas pendientes y límites

- Se confirmó la entrada a la versión 1.26.60 con un cliente real sobre el trabajo previo; la beta final aún necesita validación en juego de chunks, movimiento, inventarios, bloques, dimensiones y reconexión.
- Hay que probar clientes RakNet de versiones anteriores en un servidor real.
- El transporte nativo NetherNet está empaquetado para **Linux glibc x86_64**, no se garantiza funcionamiento equivalente en otros sistemas.
- No se ha completado la auditoría de licencias de **todas** las dependencias incluidas en el JAR sombreado; revisar `LICENSES/`, código fuente y términos de dependencias antes de redistribuirlo más ampliamente.
- No reutilices datos de producción sin respaldo; conserva `nethernet-identity.pem` para mantener la identidad de la instancia.

## Instalación y desarrollo

Consulta [distribución y primera instalación](../engine-beta-distribution.md) y [desarrollo de plugins](../engine-beta-developers.md).

**Soporte y reporte de pruebas:** [Issue #43](https://github.com/RyzeMCBE/Allay/issues/43) para conexión y juego 1.26.60, y [PR #45](https://github.com/RyzeMCBE/Allay/pull/45) para la integración de Engine.

Engine y este fork no están afiliados a Mojang Studios ni Microsoft. Se mantienen los créditos originales a AllayMC, CloudburstMC y los autores de las dependencias.

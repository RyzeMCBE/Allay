# Engine — beta para administradores de servidores

## Versión

Engine **0.14.3-beta.1**, compatible con Minecraft Bedrock **1.26.60** (protocolo 2225) y clientes anteriores compatibles con codecs RakNet incluidos. Esta beta es experimental y no sustituye una versión estable.

**Requisitos:** Linux x86_64 con glibc, Java 21, suficiente memoria y los puertos requeridos.

## Primera instalación

1. Descarga **`Engine-0.14.3-beta.1-server-linux-x86_64.zip`** en la [página de releases de Engine](https://github.com/RyzeMCBE/Engine/releases).
2. Extrae el ZIP; este incluye el servidor, la guía, notas y licencias principales.
3. Crea una carpeta de datos nueva, diferente de tu servidor principal.
4. Desde esa carpeta, inicia el servidor con Java 21:

```bash
java -jar /ruta/al/Engine-0.14.3-beta.1-linux-x86_64.jar
```

Al iniciar por primera vez se generarán archivos de configuración. Para ensayar con tus mundos existentes, **trabaja sobre copias** de los mundos, de los plugins y de la configuración.

## Red: RakNet y NetherNet

- Puerto **UDP 19132** compartido para RakNet y tráfico de juego NetherNet.
- Puerto **TCP 19132** para señalización NetherNet (cliente Bedrock 1.26.60).
- La configuración de NetherNet usa el puerto principal como referencia de forma predeterminada. No es necesario asignar otro UDP cuando el modo de puerto compartido está habilitado.
- Si ya existe una identidad `nethernet-identity.pem` de esa misma instancia, **consérvala y guárdala de forma segura** entre actualizaciones; no la incluyas en paquetes públicos.
- Consulta la [guía NetherNet en Linux](bedrock-1.26.60-nethernet-linux.md) si hay problemas de NAT, proxies o direcciones anunciadas.

## Compatibilidad con plugins y datos

Se conservan `plugins/`, `extensions/`, `plugin.json`, `extension.json` y la API binaria Allay 0.30.0. Prueba primero los plugins sobre una copia de tu servidor. Los que utilizan código interno no documentado pueden requerir cambios.

La identidad visible es Engine. Para preservar detección heredada, el identificador interno del núcleo sigue siendo `allay`.

## Verificación y reporte

Comprueba el checksum del archivo descargado con `SHA256SUMS.txt`:

```bash
sha256sum -c SHA256SUMS.txt
```

La descarga y el archivo SHA-256 deben estar juntos para verificarlos. Reporta versión del cliente, modo de conexión, comportamiento reproducible y logs **sin tokens, claves privadas ni datos sensibles** a [RyzeMCBE/Allay — incidencias](https://github.com/RyzeMCBE/Engine/issues).

## Licencias y distribución

Engine procede de Allay (LGPL-3.0), Protocol (Apache-2.0) y StateUpdater (LGPL-3.0). Los ZIP de servidor y desarrollo incluyen sus textos de licencia, y la beta ofrece el código fuente correspondiente y sus submódulos. Otras dependencias sombreadas tienen licencias propias que deben revisarse antes de afirmar cumplimiento completo de redistribución. No presentes Engine como software oficial de Minecraft.

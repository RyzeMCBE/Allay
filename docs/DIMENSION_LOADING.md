# Carga de dimensiones en Allay (RyzeMCBE)

## Objetivo
Reducir las esperas durante cambios Nether ↔ Overworld y End sin alterar el
handshake validado de Allay para Bedrock 1.26.52/protocolo 2193. Se estudió
la prioridad de los nueve chunks de llegada del PR #39 de RyzeMP, pero esta
implementación es Java y utiliza el cargador de chunks nativo de Allay.

## Flujo
1. El servidor resuelve el destino final antes de anunciar el cambio.
2. `ChangeDimensionPacket` incluye un identificador nuevo de pantalla en
   protocolos >= 712; las versiones anteriores siguen sin identificador.
3. El jugador se registra en la dimensión destino.
4. Allay encola `DIMENSION_CHANGE_SUCCESS` antes de activar el chunk loader.
   **No se invierte este orden para imitar RyzeMP.**
5. Durante un máximo de 2 segundos después de encolar el ACK, el cargador
   prioriza solo los chunks solicitados de un área 3×3 alrededor del destino.
   Los chunks no disponibles se dejan pendientes, sin bloquear a los vecinos
   ya cargados; se conserva el presupuesto por tick. Al expirar la ventana,
   la cola sigue de forma normal.
6. El cliente envía `START_LOADING_SCREEN` y `END_LOADING_SCREEN`.
   Solo se asocian al cambio actual si coincide su ID opcional. Las señales
   antiguas no finalizan una transición nueva.
7. Se conserva el sistema existente de inmunidad durante la carga; la pantalla
   la controla exclusivamente el cliente. No hay cierre forzado ni paquete
   inventado.

## Configuración
En `world-settings`:
```yaml
world-settings:
  tick-dimension-in-parallel: false
  dimension-transition-diagnostics: false
```
El seguimiento temporal existe con el diagnóstico desactivado. Activar
`dimension-transition-diagnostics: true` genera un resumen por transición
completada o cuyo seguimiento expira a los 120 segundos:
`elapsedMs`, chunks de llegada **encolados**/esperados, inicio de pantalla
observado y ACK del servidor encolado. **No** son confirmaciones de entrega
RakNet ni pruebas de renderizado de terreno. El timeout elimina únicamente
el registro diagnóstico: no teletransporta, no expulsa ni oculta pantallas.

El modo de tick paralelo continúa desactivado por defecto por el problema
documentado en [#27](https://github.com/RyzeMCBE/Allay/issues/27).
No activarlo para evaluar esta mejora de forma aislada.

## Pruebas reales requeridas
- Arranque limpio con Overworld/Nether/End generados e importados.
- Entrar y salir del Nether repetidas veces con Bedrock 1.26.52; repetir en End.
- Repetir con y sin plugins, mismo JAR y mismos mundos; medir el tiempo visible.
- Comprobar chunks cercanos, movimiento, chat, inventarios y ausencia de
  desconexiones `Block`, `TIMED_OUT` o congelación del hilo de mundo.
- A/B de `dimension-transition-diagnostics`, sin modificar envío ni paquetes.
- Comprobar versiones anteriores registradas y que START/END antiguos
  (con ID diferente) no completen una transición más reciente.

Los tiempos observados en RyzeMP no constituyen garantía de rendimiento en
Allay. CI valida compilación, estructura y pruebas unitarias, no una pantalla
de carga de un cliente real.

# Issue #27 — diagnóstico del tick paralelo

**Estado: investigación, no corrección final.** Este diagnóstico recopila evidencia
de bloqueos del tick del mundo con dimensiones paralelas. No cambia ejecutores,
orden de paquetes, teletransportes, generación ni protección de carga.

## Configuración de laboratorio

Haz una copia completa de los mundos y usa una instancia de prueba, no el HCF
de producción. En `server-settings.yml`:

```yaml
world-settings:
  tick-dimension-in-parallel: true
  parallel-tick-diagnostics: true
  dimension-transition-diagnostics: true
```

`worlds/world-settings.yml` puede conservar
`dimension-storage-mode: SEPARATE`; no cambies nombres ni carpetas entre las
pruebas A/B. En producción conserva:

```yaml
world-settings:
  tick-dimension-in-parallel: false
  parallel-tick-diagnostics: false
```

Reinicia el servidor para cambiar el modo. Los valores por defecto de ambos
interruptores son `false`.

## Qué registra

Cuando un tick paralelo supera **8 segundos**, un observador independiente
registra **una sola captura por tick** con prefijo
`PARALLEL_DIMENSION_STALL_SUSPECTED`:

- mundo, número de tick y duración transcurrida;
- última fase vista por cada dimensión: `queued`, `started`, `lightning`,
  `scheduler`, `entities-and-physics`, `entity-auto-save`, `entity-queue`,
  `entity-ai-and-base-tick`, `entity-physics`, `chunk-manager`,
  `block-updates`, `chunk-tasks` o `finished`;
- paralelismo, hilos activos y tareas pendientes del `ForkJoinPool` de cálculo;
- IDs de hilos bloqueados reconocidos por `ThreadMXBean` y stacks acotados de
  hilo de mundo, workers de dimensión y trabajadores `AllayComputeThread-*`.

Una fase `queued` puede indicar que todavía no se asignó un worker; no
demuestra por sí sola saturación. `jvmDeadlockedThreadIds=null` tampoco
descarta starvation u otros bloqueos. Si el tick tarda ocho segundos por
generación de chunks, el log es un **candidato** a bloqueo, no prueba automática
de deadlock. El observador nunca interrumpe el tick, resetea el mundo, cambia
el ejecutor ni fuerza a cerrar la pantalla de carga del cliente.

El observador se crea **solo si ambos**
`tick-dimension-in-parallel` y `parallel-tick-diagnostics` están activados.
Se detiene durante el apagado normal del mundo. La captura de stacks
puede generar logs extensos; úsala solamente durante la reproducción.

## Matriz de reproducción

Para cada caso usa **el mismo JAR**, los **mismos mundos** y los **mismos
plugins**; solo modifica el flag de paralelismo y reinicia:

1. `parallel=false`: entrar, interactuar, viajar
   Overworld → Nether → Overworld, luego End → Overworld.
2. `parallel=true`: repetir exactamente el mismo recorrido.
3. Repetir sin plugins y luego con los plugins existentes.
4. Revisar si chat, inventarios, chunks, TPS y movimiento responden.
5. Guardar `server.log`, tiempo de inicio y de desconexión y, de ser posible,
   el registro del cliente si hay crash o `TIMED_OUT`.

Es imprescindible comparar el síntoma de bloqueo del hilo de mundo con la
falla histórica del cliente que no envió `END_LOADING_SCREEN`: pueden tener
causas distintas. El PR histórico #23 está cerrado **sin merge**.
Solo una prueba real confirmará el resultado y el problema #27 **debe quedar
abierto** hasta demostrar la causa raíz y validar una corrección.

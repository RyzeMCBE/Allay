# Diagnóstico de paquetes entre dos jugadores

Esta herramienta temporal permite investigar una desconexión «Block» al mezclar clientes. Está desactivada
por defecto y solo filtra paquetes relacionados con un UUID de origen para un UUID receptor y un protocolo.
Allay genera esos paquetes; no son bytes reenviados directamente desde el cliente de origen.

En `server-settings.yml`, añade el bloque dentro del `network-settings` existente. Usa los UUID reales
mostrados en el log; los UUID de ejemplo siguientes no identifican a jugadores reales.

```yaml
network-settings:
  player-packet-diagnostics:
    enabled: true
    source-uuid: '00000000-0000-0000-0000-000000000001'
    recipient-uuid: '00000000-0000-0000-0000-000000000002'
    recipient-protocol: 2193
    excluded-packets: []
    max-log-packets: 200
```

Reinicia el servidor después de cambiar la configuración. El diagnóstico usa una copia de los ajustes por
conexión. Conserva las versiones, skins, posiciones, mundo, NPCs y plugins entre intentos. Repite cada
variante desde una conexión nueva del receptor, con el jugador de origen conectado primero.

## Secuencia de pruebas

1. `excluded-packets: []`: reproduce el fallo y guarda los mensajes `[PLAYER-PACKET-DIAG]` como referencia.
2. `excluded-packets: ['*']`: omite todos los paquetes del origen que esta herramienta reconoce. La lista
   compartida conserva las entradas del receptor y de otros jugadores. El origen quedará oculto para el
   receptor durante este intento. Si «Block» persiste, estos paquetes no explican por sí solos el fallo.
3. Vuelve a la referencia y omite **un solo tipo por intento**, empezando por `PLAYER_SKIN`,
   `UPDATE_ABILITIES`, `MOB_ARMOR_EQUIPMENT`, `MOB_EQUIPMENT`, `SET_ENTITY_DATA` y `UPDATE_ATTRIBUTES`.
   Restablece `[]` entre variantes para comprobar que el fallo original sigue reproduciéndose.
4. Prueba `ADD_PLAYER` y `PLAYER_LIST` al final. Son pruebas de dependencias: si omitir uno evita el fallo,
   todavía no demuestra que sus bytes sean incorrectos. La ausencia de la entidad o de su entrada de skin
   puede impedir que el cliente procese los paquetes posteriores. No se corrige ni reordena el protocolo
   durante estas pruebas. `PLAYER_SKIN` y `PLAYER_LIST` transportan skin por separado: omitir solo uno no
   descarta la skin que sigue viajando por el otro.
5. Confirma cualquier candidato repitiendo referencia → exclusión → referencia. Solo después se prepara
   una corrección enfocada y una prueba con ambos órdenes de entrada.

El conjunto reconocido es: `PLAYER_LIST`, `PLAYER_SKIN`, `ADD_PLAYER`, `SET_ENTITY_DATA`,
`MOB_ARMOR_EQUIPMENT`, `MOB_EQUIPMENT`, `UPDATE_ABILITIES`, `UPDATE_ATTRIBUTES`, `MOVE_PLAYER`,
`MOVE_ENTITY_ABSOLUTE`, `MOVE_ENTITY_DELTA`, `SET_ENTITY_MOTION`, `ANIMATE`, `ENTITY_EVENT`, `REMOVE_ENTITY`.
`*` se limita a ese conjunto y al jugador seleccionado: no elimina chunks, bloques ni registros globales.
Un tipo desconocido o no admitido desactiva el diagnóstico y deja pasar el tráfico con una advertencia.

## Interpretación y límites

Los mensajes indican el tipo, número de secuencia, UUIDs, protocolo receptor y decisión `ALLOW`/`OMIT`.
Las skins incluyen dimensiones, flags y longitud/huella de geometría; no se imprimen texturas, geometría
completa, JWTs ni datos de autenticación. `ALLOW` significa que el paquete pasó el filtro antes de encolarse;
no confirma recepción, orden real en la red ni aceptación por Minecraft. El límite de mensajes reduce el
registro, pero no desactiva las exclusiones.

Las entradas de `PlayerListPacket` se filtran creando otro paquete, sin modificar el original compartido.
Los paquetes identificados por runtime ID solo se filtran después de observar el `AddPlayer` del UUID de
origen; ese ID se aprende incluso si `ADD_PLAYER` está excluido. Paquetes nuevos no incluidos en el conjunto,
efectos sobre el mundo o modificaciones globales de plugins requieren un diagnóstico adicional.

Al terminar, establece `enabled: false`, reinicia y retira esta compilación de diagnóstico cuando exista
una corrección validada. La herramienta no afirma identificar automáticamente el paquete que Minecraft rechazó.

# Diagnóstico de paquetes entre protocolos

Esta herramienta temporal permite investigar una desconexión «Block» al mezclar clientes. Está desactivada
por defecto y selecciona todos los jugadores de un protocolo de origen hacia todos los receptores de otro protocolo.
Allay genera esos paquetes; no son bytes reenviados directamente desde el cliente de origen.

En `server-settings.yml`, añade el bloque dentro del `network-settings` existente. El ejemplo selecciona
1.21.100 (827) hacia 1.26.52 (2193), sin restricciones por nick ni UUID. Para probar 1.21.114, usa 844 como protocolo
de origen. Sustituye el bloque de diagnóstico
anterior completo: ya no se usan `source-uuid` ni `recipient-uuid`.

```yaml
network-settings:
  player-packet-diagnostics:
    enabled: true
    source-protocol: 827
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
   compartida conserva las entradas del propio receptor y de jugadores de otros protocolos. Todos los
   jugadores del protocolo de origen quedarán ocultos para los receptores del protocolo seleccionado. Si «Block» persiste, estos paquetes no explican por sí solos el fallo.
3. Vuelve a la referencia y omite **un solo tipo por intento**, empezando por `PLAYER_SKIN`,
   `UPDATE_ABILITIES`, `MOB_ARMOR_EQUIPMENT`, `MOB_EQUIPMENT`, `SET_ENTITY_DATA` y `UPDATE_ATTRIBUTES`.
   Restablece `[]` entre variantes para comprobar que el fallo original sigue reproduciéndose.
4. Prueba `ADD_PLAYER` y `PLAYER_LIST` al final. Son pruebas de dependencias: si omitir uno evita el fallo,
   todavía no demuestra que sus bytes sean incorrectos. La ausencia de una entidad o de su entrada de skin
   puede impedir que el cliente procese los paquetes posteriores. No se corrige ni reordena el protocolo
   durante estas pruebas. `PLAYER_SKIN` y `PLAYER_LIST` transportan skin por separado: omitir solo uno no
   descarta la skin que sigue viajando por el otro.
5. Confirma cualquier candidato repitiendo referencia → exclusión → referencia. Solo después se prepara
   una corrección enfocada y una prueba con ambos órdenes de entrada.

El conjunto reconocido es: `PLAYER_LIST`, `PLAYER_SKIN`, `ADD_PLAYER`, `SET_ENTITY_DATA`,
`MOB_ARMOR_EQUIPMENT`, `MOB_EQUIPMENT`, `UPDATE_ABILITIES`, `UPDATE_ATTRIBUTES`, `MOVE_PLAYER`,
`MOVE_ENTITY_ABSOLUTE`, `MOVE_ENTITY_DELTA`, `SET_ENTITY_MOTION`, `ANIMATE`, `ENTITY_EVENT`, `REMOVE_ENTITY`.
`*` se limita a ese conjunto y al cruce de protocolos seleccionado: no elimina chunks, bloques ni registros globales.
Un tipo desconocido o no admitido desactiva el diagnóstico y deja pasar el tráfico con una advertencia.

Si aparece «Invalid or corrupt skin» al entrar, busca `[SKIN-LOGIN-DIAG]`. Este aviso se emite
cuando la validación de login rechaza la skin, incluso con `debug` y `debug-packets` desactivados.
Incluye el nombre validado, el protocolo, la presencia del identificador, las dimensiones y longitud
de la textura y el estado de `SkinResourcePatch`. No imprime identificadores de skin, texturas,
geometría ni tokens. Esta validación no comprueba `SkinGeometryData`; el aviso describe el rechazo
de login y no identifica por sí mismo la causa de «Block» en otro cliente.

## Interpretación y límites

Los mensajes indican el tipo, número de secuencia, UUIDs observados, ambos protocolos y decisión `ALLOW`/`OMIT`.
Los UUIDs solo sirven para identificar las entidades en el registro; no son filtros de configuración.
Las skins incluyen dimensiones, flags y longitud/huella de geometría; no se imprimen texturas, geometría
completa, JWTs ni datos de autenticación. `ALLOW` significa que el paquete pasó el filtro antes de encolarse;
no confirma recepción, orden real en la red ni aceptación por Minecraft. El límite de mensajes reduce el
registro, pero no desactiva las exclusiones.

Las entradas de `PlayerListPacket` se filtran creando otro paquete, sin modificar el original compartido.
Los protocolos de origen se registran al decodificar el login, antes de la aparición inicial, y se retiran
al desconectarse sin borrar registros de una reconexión posterior del mismo UUID. Los paquetes identificados
por runtime ID solo se filtran después de observar el `AddPlayer` de cada origen; ese ID se aprende incluso
si `ADD_PLAYER` está excluido. Cada receptor mantiene un seguimiento independiente de varios jugadores.
Los NPCs sin conexión propia no tienen protocolo negociado y quedan fuera de esta selección; las entradas
y paquetes del propio receptor también se conservan si se elige el mismo protocolo en ambos extremos.
Paquetes nuevos no incluidos en el conjunto,
efectos sobre el mundo o modificaciones globales de plugins requieren un diagnóstico adicional.

Al terminar, establece `enabled: false`, reinicia y retira esta compilación de diagnóstico cuando exista
una corrección validada. La herramienta no afirma identificar automáticamente el paquete que Minecraft rechazó.

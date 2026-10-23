# Reproducción con datos capturados de jugadores

`CapturedPlayerPacketReplayTest` usa los datos de apariencia recibidos en los logs de la investigación de «Block».
No conecta un cliente Minecraft ni intenta reutilizar las credenciales de los jugadores.

## Datos y alcance

- `windows-844-party.json.gz`: PartySteve de Windows 1.21.114. Textura RGBA de 64×64 y geometría `1.8.0` conservadas
  byte por byte. El test comprueba sus SHA-256 para evitar sustituir accidentalmente esta captura por una skin genérica.
- `android-2193-classic.json.gz`: apariencia del receptor 1.26.52 del intento original que falló.
- `android-2193-persona.json.gz`: apariencia Persona 256×256 del receptor 1.26.52 de los intentos que funcionaron.

Las capturas contienen una lista explícita de campos de apariencia y plataforma. Se retiran tokens firmados, claves,
direcciones de red, nombres de cuenta, UUIDs, XUIDs e identificadores de dispositivo. El modelo de hardware es genérico
y el `ProfileHash` no vacío se sustituye por una cadena fija de igual longitud. Los datos gráficos se conservan.

La prueba invoca el analizador de apariencia de `AllayLoginData` por reflexión, con un envoltorio de token local sin
firma. Así ejercita el decodificador real sin crear una API pública nueva ni simular autenticación válida.

## Casos

El encoder real del protocolo receptor 2193 genera esta secuencia de aparición:

`ADD_PLAYER`, `SET_ENTITY_DATA`, `MOB_ARMOR_EQUIPMENT`, dos `MOB_EQUIPMENT`, `PLAYER_SKIN`, `PLAYER_LIST`, `UPDATE_ABILITIES`.

Se conservan el orden y los valores de equipamiento vacío, permisos y velocidades observados en el log. Las entidades
y las cuentas son dobles de prueba con identificadores sintéticos. No se reconstruyen el mundo, NPCs, plugins,
temporización, compresión, cifrado ni toda la secuencia de login del servidor.

Se prueban las dos apariencias del receptor, ambos órdenes de entradas en la lista y los valores de plataforma 7 y 1.
El caso Android cambia únicamente `DeviceOS` sobre la captura de Windows: es un control de esa variable, no otra
captura de cliente. Se usan helpers independientes para codificar y decodificar cada paquete, se exige consumir todos
los bytes y se comprueba la conservación de textura, geometría y plataforma. Otro caso compara los bytes generados
por Windows y Android tras normalizar únicamente la plataforma de `PLAYER_LIST`.

Dos contratos adicionales comprueban que el `ProfileHash` del receptor Persona se conserva y que su color `#RRGGBB`
se transmite con alfa opaco, siguiendo las correcciones descritas en AllayMC/Allay#1856 y AllayMC/Protocol#290.
Son comprobaciones semánticas independientes del mero round-trip; pueden fallar aunque el codec lea sus propios bytes.
Un fallo en esos controles no demuestra que PartySteve provocara la desconexión original.

## Ejecución e interpretación

Con Java 21:

```sh
./gradlew :server:test --tests org.allaymc.server.player.CapturedPlayerPacketReplayTest
./gradlew build
```

Un fallo al codificar, decodificar o consumir los bytes localiza un problema reproducible del servidor. Los contratos
semánticos señalan datos perdidos o transformados. Si los casos de PartySteve pasan, solo se descarta ese tipo de
fallo en el camino cubierto: el codec del servidor puede compartir una interpretación incorrecta en ambos sentidos,
y el cliente Minecraft puede imponer validaciones adicionales. El resultado «Block» requiere una prueba en el cliente.

Este cambio añade únicamente pruebas, capturas desidentificadas y documentación. No aplica las correcciones de los PR
externos ni cambia el tráfico del servidor.

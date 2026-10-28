# Bedrock 1.26.60 on Linux: NetherNet + legacy RakNet

Allay speaks **two transports** at the same time:

- **NetherNet**: 1.26.60 and other NetherNet-capable Bedrock clients. HTTP signaling on **TCP** and DTLS/WebRTC ICE media on a dedicated **UDP** port.
- **RakNet**: earlier Bedrock and NetEase clients on the traditional Bedrock **UDP** port.

The supported gameplay protocol is **2225** for Minecraft Bedrock **1.26.60**, with older protocol adapters retained. Merely adding codec 2225 without the NetherNet listener is not sufficient for 1.26.60 clients.

## Linux x86_64

The server uses CloudburstMC/Network's NetherNet transport and includes the matching `libdatachannel-java` **Linux glibc x86_64** native through Gradle dependencies. Use Java 21. This packaging does **not** claim support for musl/Alpine, Linux aarch64, macOS or Windows.

Default `network-settings` values:

```yaml
network-settings:
  ip: 0.0.0.0
  port: 19132
  enablev6: true
  portv6: 19133
  nethernet-enabled: true
  nethernet-signaling-port: 0 # 0 = use port, TCP 19132
  nethernet-ice-port: 19134 # UDP 19134, separate from RakNet
  nethernet-identity-file: nethernet-identity.pem
  nethernet-advertise-addresses: []
```

Open **TCP 19132** for HTTP signaling, **UDP 19134** for WebRTC/ICE and **UDP 19132** for legacy RakNet. The same numeric TCP/UDP signaling and legacy port can coexist; **do not assign the WebRTC UDP port to RakNet**. When using IPv6 the legacy IPv6 UDP port 19133 must also differ from WebRTC's UDP port.

On a VPS with a public IP, WebRTC can advertise the server's gathered host candidates. Behind NAT or a tunnel, set `nethernet-advertise-addresses` to the actual externally reachable `host:port` of the forwarded **WebRTC media** port, for example `["203.0.113.10:19134"]` (documentation address only). The UDP forward must preserve its advertised source endpoint. If you allocate a separate public signaling port, configure `nethernet-signaling-port` accordingly and open its TCP port.

NetherNet creates `nethernet-identity.pem` on first launch. **Preserve this file** across deployments: connected clients can pin its public key, so replacing it changes the server identity.

## Authentication and security

NetherNet uses Microsoft/Minecraft-issued identity assertions on the signaling channel (`TokenTrust.MINECRAFT_AUTH`). After a client opens a WebRTC data channel, Allay checks that the Bedrock login chain public key is the key bound to the validated signaling identity. The server must reject any missing or mismatched transport-to-login binding. DTLS encrypts NetherNet traffic, therefore it does not perform the legacy RakNet Bedrock AES handshake; RakNet behavior remains unchanged.

Allay's normal version negotiation, gameplay packet processing, compression negotiation and connection events are shared between transports.

## Verification

1. Check that both the TCP signaling port and UDP media port bind, and that the Linux WebRTC native loads.
2. Verify a real Bedrock 1.26.60 client can connect, receive StartGame and play; also verify an older RakNet client.
3. Check malformed / unauthenticated NetherNet logins are rejected and that world and inventory packets round-trip correctly.
4. Run `./gradlew build` and publish the shaded server JAR only when tests pass.

Automated CI validates registry imports, protocol codec tests, frame and recipe handling. **No automated test can substitute for connecting an actual Bedrock client through the full HTTPS/HTTP signaling, ICE, DTLS and login flow.**

Sources:
- CloudburstMC Network NetherNet implementation: https://github.com/CloudburstMC/Network/tree/nethernet
- Geyser NetherNet operational documentation: https://geysermc.org/blog/preparing-for-nethernet/

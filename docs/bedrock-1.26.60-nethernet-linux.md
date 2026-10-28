# Bedrock 1.26.60 on Linux: NetherNet + legacy RakNet

Allay speaks **two transports on the same public IPv4 port** by default:

- **NetherNet**: 1.26.60 and other NetherNet-capable Bedrock clients. HTTP signaling on **TCP** and DTLS/WebRTC ICE media on the normal Bedrock **UDP** port.
- **RakNet**: earlier Bedrock and NetEase clients on the traditional Bedrock **UDP** port.

The supported gameplay protocol is **2225** for Minecraft Bedrock **1.26.60**, with older protocol adapters retained. Merely adding codec 2225 without the NetherNet listener is not sufficient for 1.26.60 clients.

## Linux x86_64

The server uses CloudburstMC/Network's NetherNet transport and includes the matching `libdatachannel-java` **Linux glibc x86_64** native through Gradle dependencies. RakNet remains pinned to the legacy version from the Protocol fork to preserve `RakDisconnectReason` and other older APIs. Use Java 21. This packaging does **not** claim support for musl/Alpine, Linux aarch64, macOS or Windows.

Default `network-settings` values:

```yaml
network-settings:
  ip: 0.0.0.0
  port: 19132
  enablev6: true
  portv6: 19133
  nethernet-enabled: true
  nethernet-signaling-port: 0 # 0 = use port, TCP 19132
  nethernet-shared-port: true # RakNet and WebRTC both use UDP 19132
  nethernet-ice-port: 19134 # used only when nethernet-shared-port is false
  nethernet-identity-file: nethernet-identity.pem
  nethernet-advertise-addresses: []
```

Open **TCP 19132 and UDP 19132**. Players keep the same address and port for both old and new clients; no public UDP 19134 allocation is needed in shared mode. A hosting panel or container must forward **both protocols** for the main port. The existing optional legacy IPv6 listener remains on UDP 19133.

The shared UDP socket routes RakNet packets normally and forwards authenticated ICE/DTLS to a private native endpoint on loopback. Per-client PROXY protocol v2 relays preserve the original player IP and source port. Private native ports are never advertised as ICE candidates and need no firewall or hosting allocation. Only a peer whose HTTP identity has been validated and whose SDP answer has been issued can create a relay. Relays close with the peer and expire when idle.

Existing configurations automatically receive `nethernet-shared-port: true`. Their old `nethernet-ice-port: 19134` value is ignored while shared mode is enabled. To retain separate media ports, set `nethernet-shared-port: false`; only in that mode must `nethernet-ice-port` differ from both RakNet UDP ports.

On a VPS with a public IP, WebRTC advertises the shared UDP listener. Behind NAT or a tunnel, set `nethernet-advertise-addresses` to the actual externally reachable main **UDP** endpoint, for example `["203.0.113.10:19132"]` (documentation address only). An IP literal in the client's HTTP Host header is also inferred automatically. Names can be configured explicitly and are resolved at startup. The UDP forward must preserve its advertised source endpoint. If you allocate a separate public signaling port, configure `nethernet-signaling-port` accordingly and open its TCP port.

NetherNet creates `nethernet-identity.pem` on first launch. **Preserve this file** across deployments: connected clients can pin its public key, so replacing it changes the server identity.

## Authentication and security

NetherNet uses Microsoft/Minecraft-issued identity assertions on the signaling channel (`TokenTrust.MINECRAFT_AUTH`). After a client opens a WebRTC data channel, Allay checks that the Bedrock login chain public key is the key bound to the validated signaling identity. The server must reject any missing or mismatched transport-to-login binding. DTLS encrypts NetherNet traffic, therefore it does not perform the legacy RakNet Bedrock AES handshake; RakNet behavior remains unchanged.

Allay's normal version negotiation, gameplay packet processing, compression negotiation and connection events are shared between transports.

## Verification

1. Check that TCP signaling and the shared UDP socket bind on the configured main port, and that the Linux WebRTC native loads.
2. Verify a real Bedrock 1.26.60 client can connect, receive StartGame and play; also verify an older RakNet client.
3. Check malformed / unauthenticated NetherNet logins are rejected and that world and inventory packets round-trip correctly.
4. Run `./gradlew build` and publish the shaded server JAR only when tests pass.

Automated CI validates registry imports, protocol codec tests, frame and recipe handling. The shared-port regression also connects two real native WebRTC peers, checks bidirectional data and sends legacy RakNet pings to that same UDP socket. **These tests do not substitute for connecting an actual Bedrock client through the full HTTP signaling, Xbox identity, ICE, DTLS and Bedrock login flow.**

Sources:
- CloudburstMC Network NetherNet implementation: https://github.com/CloudburstMC/Network/tree/nethernet
- Geyser NetherNet operational documentation: https://geysermc.org/blog/preparing-for-nethernet/

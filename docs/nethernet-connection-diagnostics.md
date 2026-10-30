# NetherNet connection diagnostics (Engine Linux)

Engine keeps TCP and UDP on the public Bedrock port (19132 by default) in shared mode. Its native UDP ICE listener binds a temporary **private loopback** port. A native PROXY relay warning mentioning this port does **not** mean that port must be publicly opened.

## Opt in for a short troubleshooting session

Add this value under `network-settings` in `server-settings.yml`, then restart Engine:

```yaml
network-settings:
  nethernet-connection-diagnostics: true
```

The setting defaults to **false**. It emits only metadata and counters, not player IPs, STUN usernames, ICE passwords, tokens, raw SDP or packet payloads. It can still generate additional lines under failed-connection traffic; switch it off after collecting a sample.

To see only these events in a console log:

```bash
grep -E 'NetherNet diagnostic|could not connect over NetherNet' server.log
```

## Understanding a join

Each authorized join receives a numeric ID in the log:

- `signed SDP answer ready`: the server prepared an ICE answer and added its announced endpoint candidates.
- `HTTP signaling answer dispatched`: the signed answer was passed to HTTP signaling. It **does not** guarantee delivery to the client.
- `first authorized UDP relay created` and `first STUN packet arrived`: valid STUN reached Engine's shared public UDP socket and was admitted to the native relay.
- `first native ICE/DTLS reply forwarded`: the private native listener returned at least one datagram to the client via the relay.
- `first DTLS packet arrived`: a subsequent encrypted-handshake datagram reached Engine's public UDP socket.
- `relay authorization ended`: counts for inbound STUN, inbound DTLS, native responses and local relay drops. A join that ends with all three packet counts at zero had no *authorized* traffic through the multiplexer. Correlate this with packet capture to distinguish outside packet loss from dropped packets.
- `discarded UDP datagrams`: **aggregated** per 30 seconds (unknown ICE fragments and missing/no-relay packets). These counts are not attributable to a player without external capture.

## Capture only if repeated failures continue

During a short, authorized test on the server host:

```bash
sudo tcpdump -ni any -tttt 'udp port 19132'
```

Use the actual configured public Bedrock port if different. Capture is sensitive (IP addresses and packet bytes): **do not publish a raw pcap**. Share only a redacted event summary. If the server is inside Docker, inspect forwarding both TCP and UDP of the public port to the container; the temporary loopback ICE port must not be forwarded publicly.

## Platform limitations

The CI exercises simultaneous real native WebRTC peers and legacy RakNet on Linux loopback (NIO and Epoll). It cannot prove PS4/PS5 Internet joins, console-specific discovery, NAT, or all Minecraft Bedrock versions. Test console joins from external networks separately. This feature does **not** change the connection protocol, authentication policy, advertised ports, or existing plugins.

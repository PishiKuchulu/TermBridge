# TermBridge — Advanced SSH: Tunnels, Jump Hosts, Proxies (Prompt 10)

## What is implemented, and on which sshj path

All paths below were verified against the actual sshj 0.40.0 jar
(javap class inspection, jar deleted after inspection — the jar
is not a project dependency change; sshj was already pinned).

| Feature | sshj 0.40.0 path (verified) | Status |
|---|---|---|
| Local port forwarding | `SSHClient.newLocalPortForwarder(Parameters, ServerSocket)`; `listen()` blocks until `close()` | Implemented |
| Remote port forwarding | `SSHClient.getRemotePortForwarder().bind(Forward, SocketForwardingConnectListener)`; `cancel(Forward)` | Implemented |
| Jump hosts | `jumpClient.newDirectConnection(host, port)` + `targetClient.connectVia(directConnection)` | Implemented |
| SOCKS proxy (unauthenticated) | `SSHClient.setSocketFactory(...)` + JDK `Socket(Proxy.Type.SOCKS, …)`; sshj's `connect()` uses `socketFactory.createSocket()` then connects with timeout (bytecode-verified) | Implemented, server-unproven |
| Dynamic / SOCKS forwarding | No SOCKS server class exists in the 0.40.0 jar | Blocked — not offered |
| HTTP CONNECT proxy | No sshj support; no standard-library CONNECT client | Blocked — not offered |
| ProxyCommand | No sshj support; would need a local-process stdio bridge | Blocked — not offered |
| Authenticated SOCKS proxy | Would need `java.net.Authenticator`, which is JVM-global | Blocked — not offered |

"Implemented" here means code scope (see PHASE_REPORT_10.md §6):
every one of these still needs the controlled-server fixture
before any end-to-end behaviour is claimed.

## Field meanings (the part users get wrong)

A tunnel has one bind side and one target side, and the kind
decides which side is local:

- LOCAL: the app listens on bindAddress:bindPort on this device;
  traffic goes through the SSH connection to targetHost:targetPort
  as seen FROM THE SERVER (usually "localhost" or an internal host).
- REMOTE: the server listens on bindAddress:bindPort; traffic
  comes back through the SSH connection to targetHost:targetPort
  on this device (usually "127.0.0.1" and a local service port).

## Bind-address default and the explicit opt-in

Default bind is loopback (`127.0.0.1`) for both kinds, and the
validator rejects any non-loopback or wildcard bind unless the
tunnel's `allowNonLoopback` flag is set — which the UI exposes
only as a checkbox whose label says, in full, that it exposes
the port to the local network and should be used only on trusted
networks. There is no quiet path to a network-wide bind: a tunnel
saved without the flag cannot bind beyond loopback, and a tunnel
with the flag says so in its stored configuration.

Remote forwarding deserves the same warning in the other
direction: a non-loopback remote bind exposes the port on the
SERVER's network, not the phone's. The same flag gates it, and
the server's own `GatewayPorts` setting can still refuse it —
a server rejection surfaces as a fixed error, not a silent
loopback fallback. Behaviour is never silently substituted.

## Jump chains

- Order is the route: hops are stored with sortOrder and returned
  in that order; the UI offers move-up/move-down and per-hop
  removal, and the repository replaces a chain atomically
  (Room transaction), so a failed save cannot leave a half chain.
- Every hop gets its own host-key verification: its own stored
  fingerprint, its own trust dialog, in chain order. Rejecting any
  hop fails the whole chain.
- Trust records for ALL hops are persisted only after the target
  authenticates — the prompt-05 rule, extended to chains. A
  failure anywhere persists nothing and disconnects every client
  opened so far, in reverse order.
- Hop credentials follow the host-credential rules exactly:
  loaded from HostRepository (Keystore-decrypted) at connect time
  only, never stored, wiped in a finally block, never logged.
  Key-hop passphrases are entered per hop in the tunnel-session
  section (key hops only) and are never stored or shared between
  hops. A hop with no stored credential fails the chain with a
  generic message, not a hop-specific secret error.
- The proxy, if configured, applies to the FIRST hop's TCP
  connection only. Later hops ride channels, so a per-hop proxy
  would be meaningless — and is not offered.
- Cap: at most 5 hops. Deeper chains are rejected at save time,
  not truncated silently.

## Proxies

- SOCKS is unauthenticated only. Authenticated SOCKS is blocked
  deliberately: `java.net.Authenticator` is JVM-global, so
  credentials set for one connection would apply to every
  connection in the process — including ones the user never
  meant to proxy. That is a credential-leak shape, not a missing
  feature to work around quietly.
- Proxy specifications parse as `socks://host:port`,
  `socks5://host:port`, or bare `host:port` (documented SOCKS
  default). An `http://` or `https://` specification is rejected
  with an explicit "not supported" message, never reinterpreted
  as SOCKS.

## Lifecycle and cleanup

- Tunnels run on a DEDICATED connection, separate from the
  terminal session. Starting tunnels here never touches a live
  terminal; stopping them disconnects only this connection.
- Stop order is fixed: forwards first (local forwarders closed,
  remote forwards cancelled on the server), then the client
  disconnects. The tunnel manager's `close()` does both and ends
  its jobs; after `close()` the manager is not reused.
- Partial start failure: if any enabled tunnel fails to start,
  the ones already started are stopped and the connection is
  closed — a half-started set is never reported as running.
- A tunnel session that is stopped, cancelled, or cleared
  (ViewModel cleared) leaves no forward running and no client
  connected. There is no background tunnel service in this phase:
  the Advanced ViewModel is destination-scoped (unlike
  SessionViewModel's activity scope, a deliberate choice for
  terminals), so popping the Advanced destination — back button
  or bottom-bar navigation — clears it and stops the tunnels.
  Pushing another screen on top (e.g. Add host) keeps the
  destination in the back stack, so tunnels keep running until
  the destination is popped or the user stops them; that is
  ordinary back-stack behaviour, not a background service.
- Host lifecycle: tunnels, jump chains, and proxies are
  non-secret configuration, so duplicating a host copies them
  (like env settings, unlike credentials), and deleting a host
  removes them (repository delete first, Room cascade second).
  Duplicating a jump host does NOT rewrite other hosts' chains
  to point at the copy — chains keep pointing at the original
  jump host, which is the correct route.

## What is not in this phase

- No dynamic forwarding, no HTTP/ProxyCommand/authenticated
  SOCKS (blocked above, with reasons, in the UI too).
- No background/persistent tunnel service, no auto-reconnect,
  no tunnel use from the terminal session (prompt 15 may unify
  connections — that is a decision for then, not a claim now).
- No per-tunnel bandwidth, logging, or traffic inspection: a
  tunnel carries bytes, and the app does not read them.

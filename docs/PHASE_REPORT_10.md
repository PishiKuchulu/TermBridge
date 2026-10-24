# TermBridge — Phase Report, Prompt 10

Date: 2026-10-24. Phase: 10 — Advanced SSH: Tunnels, Jump Hosts, and Proxying.
Process: autonomous continuation per the user's instruction
(no per-prompt widget).

## 1. Actual changes and touched files

No new dependency. Everything here is own code on top of Room
2.6.1 (prompt 04), sshj 0.40.0 (prompt 05), and the prompt-06
terminal / prompt-09 environment repositories.

Domain (pure Kotlin, no Android dependency, JVM-testable):
- `TunnelConfig.kt` — one model for LOCAL and REMOTE with
  kind-specific field meanings documented in the KDoc (LOCAL:
  app listens locally, target is server-side; REMOTE: server
  listens, target is local). `TunnelValidator`: port ranges,
  target-host shape, and the bind rule — a non-loopback or
  wildcard bind is rejected unless `allowNonLoopback` is set.
- `JumpChain.kt` — ordered jump-host ids for one target, plus
  `JumpChainValidator` (non-empty, no duplicates, target not its
  own jump, at most 5 hops).
- `ProxyConfig.kt` — `ProxyKind` NONE / SOCKS only, plus
  `ProxySpecParser` (`socks://`, `socks5://`, bare `host:port`
  as documented SOCKS default; `http://`/`https://` rejected with
  an explicit "not supported" message, never reinterpreted).

Data:
- `AdvancedEntity.kt` — three non-secret tables: `tunnels`
  (kind/bind/target/allowNonLoopback/enabled/label/sortOrder),
  `jump_hops` (targetHostId/jumpHostId/sortOrder, unique on
  (targetHostId, jumpHostId)), `proxies` (hostId PK, kind/host/
  port). No credential column exists in any of them.
- `AdvancedDao.kt` — tunnel CRUD + per-host queries, jump-hop
  queries + atomic `replaceChain` (Room transaction), proxy
  get/upsert/delete.
- `AdvancedRepository.kt` — validation before any write; host
  existence checks; tunnel cannot move to another host; NONE
  proxy deletes the row instead of writing a meaningless row;
  `copyForDuplicate` / `deleteForHost` for HostRepository.
- `TermBridgeDatabase.kt` — version 3 -> 4 with a real
  `MIGRATION_3_4` (CREATE TABLE + indices for all three tables,
  matching the entities exactly), NOT
  fallbackToDestructiveMigration. `TermBridgeApplication` adds
  the migration and exposes `advancedRepository`.
- `HostRepository.kt` — optional `advancedRepository` (same
  pattern as prompts 08/09): `duplicateHost` copies tunnels,
  jump chain, and proxy (non-secret, like env settings — unlike
  credentials, which are never copied); `deleteHost` removes
  them (repository first, Room cascade second).

Transport:
- `SshTransport.kt` — refactored so direct connect and chain
  connect share one `authenticateClient` (verify + connect +
  auth, pending trust returned, never persisted inside it).
  `connect()` gains SOCKS-proxy support (SocketFactory set
  before connect) and keeps its exact prompt-05 behaviour
  otherwise. New `connectChain()`: hops in order, each with its
  own stored fingerprint and trust dialog, reached via the
  previous hop's `newDirectConnection` + `connectVia`; the proxy
  applies to the first hop's TCP connection only; trust records
  for ALL hops persist only after the target authenticates; any
  failure disconnects every client opened so far, in reverse
  order. New `ChainedSshConnection` (disconnect closes all hop
  clients, reverse order).
- `SocksSocketFactory.kt` — JDK `Socket(Proxy.Type.SOCKS, …)`
  factory. sshj's `connect()` uses the no-arg `createSocket()`
  and then connects with timeout — verified against the 0.40.0
  bytecode, not assumed. Layered sockets are rejected, not
  silently downgraded.
- `TunnelManager.kt` — local forwarding via
  `newLocalPortForwarder(Parameters, ServerSocket)` with
  `listen()` on an IO job per tunnel and `close()` to end it;
  remote forwarding via `remotePortForwarder.bind(Forward,
  SocketForwardingConnectListener)` and `cancel()`; active
  tunnels exposed as a StateFlow; `stopAll` (forwards first,
  then the caller disconnects) and `close()` (stop + end jobs,
  manager not reusable after). The manager takes an
  `SshConnection` and resolves the sshj client internally, so
  ViewModel code never holds an SSHClient. Partial start
  failure is handled by the ViewModel (see §6), not hidden here.

UI:
- `AdvancedViewModel.kt` + factory — per-host config flows
  (tunnels, chain + hop profiles, proxy), config saves with
  per-field/per-section errors, and a dedicated tunnel session:
  credential loading per hop + target (Keystore-decrypted, wiped
  in finally), per-hop passphrase input (key hops only, never
  stored or shared between hops), trust dialogs per hop in chain
  order, enabled-tunnels-only start, active-tunnel state, and
  the fixed stop order (forwards, then client). Cancellation
  during connect cleans up silently instead of overwriting IDLE
  with FAILED.
- `AdvancedScreen.kt` — rewritten from shell to real screen:
  host radio picker (empty state → Add host), tunnel list with
  enabled switches and delete-with-confirmation, add-tunnel form
  with per-field errors and the non-loopback checkbox whose label
  states the exposure in full, jump-chain list with move-up /
  move-down / per-hop removal / candidate radios / clear-with-
  confirmation, proxy form (NONE/SOCKS radios, spec field with
  a real Parse button, host/port fields, save + clear), tunnel
  session section (target + per-hop passphrase fields, Start /
  Stop / Cancel, active-tunnel list, error text, shared trust
  dialog), and a kept "Not available" section with a reason per
  blocked item (dynamic forwarding, HTTP proxy, ProxyCommand,
  authenticated SOCKS). The old shell banner is gone from this
  screen because its implemented parts are real; the banners for
  the blocked parts stay.
- Navigation: `TermBridgeApp` now takes `advancedRepository`;
  `MainActivity` passes it; the Advanced destination builds the
  AdvancedViewModel and passes an Add-host callback.
- Strings: 268 total, 0 missing references in static check
  (only `app_name` unused in Kotlin, used by the manifest).

Documentation:
- `docs/ADVANCED_SSH.md` — the full decision record: verified
  sshj API table, field meanings, bind default + opt-in, jump
  rules, proxy limits, lifecycle/cleanup, host lifecycle, and
  what is not in this phase.

## 2. Commands executed and exact results

- sshj API verification (real evidence, not a claim): the 0.40.0
  jar was fetched from Maven Central and inspected with javap —
  `newLocalPortForwarder(Parameters, ServerSocket)` + `listen()`/
  `close()`, `getRemotePortForwarder().bind(Forward,
  SocketForwardingConnectListener(SocketAddress))` + `cancel()`,
  `newDirectConnection(host, port)` + `connectVia(...)`,
  `setSocketFactory(...)`, and SocketClient's `connect()` using
  `socketFactory.createSocket()` then `socket.connect` with
  timeout. Class-listing grep found NO dynamic/SOCKS-server,
  HTTP-proxy, or ProxyCommand class in the jar — that absence
  is the evidence for the three blocked features, not a guess.
  The jar was deleted after inspection; it is not a project
  dependency change (sshj was already pinned).
- Static verification (passed): string-reference check — 267
  Kotlin references, 0 missing; strings.xml and manifest parse
  as XML; grep — no PFP mention in any prompt-10 file, no AI
  wording, no cloud/sign-in claims, no logging call in new or
  touched code, no TODO markers in new files; brace/paren/
  bracket balance — 0 imbalance in all new and touched files
  under the corrected check (strings stripped before line
  comments; the first-pass flags on ProxyConfig and
  AdvancedValidationTest were false positives from `://`
  inside string literals, confirmed by re-check).
- No Gradle build, no test run: NOT RUN, same sandbox reasons
  as prompts 02–09 (daemon protocol + Java HTTPS). CI is still
  the first real build/test gate.

## 3. Tests added/run

- JVM unit tests (written, NOT RUN locally):
  `AdvancedValidationTest` (18 cases — loopback/IPv6-loopback/
  non-loopback/wildcard bind rules with and without the flag,
  port ranges, blank target, chain empty/duplicate/target-as-
  own-hop/5-vs-6 hops, proxy spec forms + HTTP rejection +
  missing/bad port + blank, format round-trip, proxy validation);
  `AdvancedRepositoryTest` (8 cases — tunnel round-trip,
  invalid tunnel not saved, tunnel cannot move hosts, enabled
  flag flip, chain order + atomic replace + clear, chain with
  missing host rejected, proxy save/get/clear, host duplicate
  copies advanced config but not the credential + host delete
  removes advanced config while the duplicate's survives).
  Test doubles: `FakeTunnelDao` + `FakeJumpHopDao` +
  `FakeProxyDao` added to `FakeDaos.kt` (the unique
  (targetHostId, jumpHostId) index is deliberately NOT emulated
  — duplicate rejection is a validator/repository rule and is
  tested there).
- Instrumented integration: five new ignored cases in
  `SshIntegrationTest` (local forward reaches a service + stop
  closes the listener; remote forward binds + cancel unbinds;
  jump chain reaches the target + reject fails cleanly; SOCKS
  proxy connection authenticates; partial tunnel failure stops
  started forwards), same fixture blocker as prompts 05–09.
- Run: none. Pass/fail: NOT RUN (unit) / IGNORED (integration).
  No real-server tunnel, jump, or proxy behaviour is claimed.

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet.
- Compile risks to watch on the first CI run: Room v4 schema
  vs `MIGRATION_3_4` SQL (column order, index names, and Boolean
  → INTEGER mapping must match the entities exactly);
  `JumpHopDao.replaceChain` as a Room `@Transaction` suspend
  function; the `TermBridgeApp` signature change (four
  repositories now required — MainActivity updated);
  `Icons.Filled.ArrowUpward/ArrowDownward` (icons-extended);
  `SshTransport.connectChain`'s `storedFingerprints.getValue`
  on data-class keys; `TunnelManager`'s internal-client access
  from the transport package; `AdvancedScreen`'s `key()` +
  early-return `HopAdder` composition.
- The migration itself is NOT RUN (no device, no room-testing
  dependency — same prompt-08/15 decision as the v1->v2 and
  v2->v3 migrations).
- Previews for the Advanced screen were removed with the shell:
  it now requires a ViewModel, and faking a repository in
  main-source preview code would be worse than no preview (same
  prompt-04 decision as Hosts/Form). Its states are covered by
  the repository/validator tests instead.
- ViewModel error/info messages remain hardcoded English,
  consistent with the existing ViewModels; full string-resource
  localisation of ViewModel messages is a prompt-14 pass.

## 5. Security properties: proven vs assumed

- Proven by construction: no plaintext credential column exists
  in the new tables (no secret column at all); the only
  credential paths are the prompt-05/04 ones, reused per hop;
  non-loopback binds require an explicit stored opt-in; no
  accept-all verifier exists in main code; trust persists only
  after whole-chain auth; no log call in new code; a tunnel
  carries bytes the app does not read or log.
- Proven by tests (once CI runs them): bind/port/chain/proxy
  validation, atomic chain replacement, duplicate/delete
  semantics for advanced config, proxy parsing (including the
  explicit HTTP rejection).
- Assumed, not proven: that sshj's forwarding/channel APIs
  behave on Android exactly as the jar inspection suggests
  (needs the fixture: OpenSSH server with `AllowTcpForwarding`
  and `GatewayPorts` variants, a bastion + target pair, and a
  SOCKS proxy); that the JDK SOCKS handshake works through
  sshj's socket path on device; that a remote-forward rejection
  surfaces as an exception from `bind()` (the manager maps any
  failure to a fixed message, so a different failure shape still
  fails safely, just with less specific wording).
- Documented non-claims and limits: dynamic forwarding, HTTP
  proxy, ProxyCommand, and authenticated SOCKS are blocked with
  reasons, not substituted; a server's `GatewayPorts` setting can
  still refuse a remote forward; the tunnel session is
  destination-scoped (popping Advanced stops it), not a
  background service; duplicating a jump host does not rewrite
  other hosts' chains to point at the copy.

## 6. Requirements implemented vs partial vs blocked

- Implemented (code scope): local port forwarding, remote port
  forwarding, jump-host chains (ordered, per-hop host-key
  verification, per-hop credentials, atomic chain saves,
  move/remove/clear in the UI), unauthenticated SOCKS proxy
  (config + spec parsing + SocketFactory path), per-host
  persistence (v3->v4 migration), active-tunnel state with
  start/stop, partial-failure cleanup, fixed stop order, host
  duplicate/delete lifecycle for advanced config, blocked-item
  honesty in the UI.
- Partial: persistence and connection behaviour are implemented
  by Room/sshj but not demonstrated by a run (no CI/device/
  fixture yet); the migration is written but not executed
  against a real v3 database.
- Blocked: build, test execution, real-server fixture (see
  §3/§4); dynamic/SOCKS forwarding, HTTP CONNECT proxy,
  ProxyCommand, authenticated SOCKS proxy (see §5 reasons —
  these are design blocks, not just missing runs).
- Not implemented, correctly: any background/persistent tunnel
  service, auto-reconnect, tunnel use from the terminal session
  (prompt-15 unification candidate), per-tunnel stop buttons
  (session-wide stop only — no per-tunnel stop method is
  offered, deliberately, so no dead API pretends otherwise),
  any traffic inspection or bandwidth accounting.

## 7. Evidence for claimed capabilities

- No real-server tunnel, jump, or proxy success is claimed.
  Claims with evidence are the sshj jar inspection (§2),
  static checks (§2), and the test list (§3). Everything needing
  a run is labelled NOT RUN / IGNORED.

## 8. Remaining issues and next prompt

- Prompt 11 — Import/Export and Backup: encrypted export (the
  migration path promised in prompt 04's lifecycle doc), import
  with preview + fail-closed parsing, and what happens to
  prompt-10 config (tunnels/chains/proxies are non-secret and
  exportable as config; credentials stay Keystore-bound and must
  not ride along in plaintext — that boundary is prompt 11's to
  define, not assumed here).
- Open decisions carried forward: repository + CI push,
  application ID confirmation, UI-language confirmation (before
  prompt 14), SSH/SFTP integration fixture (now also covering
  forwarding/GatewayPorts variants, a bastion + target pair,
  and a SOCKS proxy), connection unification (prompt 15),
  Room transaction for button-child replacement (prompt 08
  item), form-dialog-level save-error surfacing (prompt 08
  item), room-testing for the v2->v3 and v3->v4 migrations
  (same prompt-15/CI decision), ViewModel-message localisation
  (prompt 14), upload picker starting in the local folder
  (blocked on the OpenDocument contract, not invented here).
- Continuing autonomously to prompt 11 per the user's instruction.

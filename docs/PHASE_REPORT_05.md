# TermBridge — Phase Report, Prompt 05

Date: 2026-10-24. Phase: 05 — Real SSH Transport and Authentication.

Note on process: the user changed the gate rule during this
phase («نیازی نیست بگم بری سراغ پرامپت بعدی، خودت ادامه بده»):
from here on, prompts continue autonomously without a per-prompt
go-ahead message. Phase reports are still written per prompt, but
no approval widget is issued at a gate.

## 1. Actual changes and touched files

New dependency, verified against ADR-03 before adding: sshj 0.40.0
(`com.hierynomus:sshj`, Apache 2.0 — the version, group, and licence
match the prompt-01 record). No other library was added.

Transport (`transport/`, no sshj type leaks into UI):
- `SshError.kt` — fixed user-facing messages; `SshErrorMapper`
  (pure, unit-tested) maps exceptions to types without copying any
  exception message into the output.
- `HostKeyFingerprint.kt` — OpenSSH-style `SHA256:` fingerprint
  (SHA-256 digest, Base64 no padding) + SSH key-type label mapping
  (RSA/EC/EdDSA/DSA). The fingerprint is the identity; the label
  is display-only.
- `HostKeyVerificationPolicy.kt` — pure UNKNOWN / MATCH / CHANGED
  policy delegating to the prompt-04 verifier. Accept on UNKNOWN or
  CHANGED returns a pending trust record; accept on MATCH returns
  no pending record. There is no accept-all path.
- `SshConnection.kt` — `HostKeyTrustStore` (implemented by
  HostRepository), `HostKeyDecisionHandler` (suspend, UI-driven),
  `SshConnectionRequest` (profile, credential bytes, optional
  passphrase, connect/socket timeouts default 15s/30s),
  `SshConnection` (transport-only: profile, isConnected,
  disconnect). No shell channel, no SFTP subsystem is opened here.
- `SshTransport.kt` — sshj-backed connect on Dispatchers.IO:
  stored fingerprint pre-loaded before connect (the sshj verifier
  is synchronous); verifier delegates to the policy and blocks its
  thread in runBlocking until the UI decision answers; password
  auth via `authPassword`, key auth via `loadKeys` + `authPublickey`;
  trust record persisted ONLY after authentication succeeds, so a
  failed connection cannot overwrite a trusted fingerprint;
  deterministic cleanup (client.disconnect) on any failure,
  including rejected keys and cancellation. `SshjConnection` holds
  the sshj client as internal — UI code must not use it; prompt
  06/07 transport code in this package will.

Data/UI wiring:
- `HostRepository` now implements `HostKeyTrustStore` (its three
  trust methods gained `override`; behaviour unchanged).
- `SessionViewModel` — host list, host selection, connect/cancel/
  disconnect, trust-dialog state via CompletableDeferred (a cancel
  while the dialog is open completes the decision with false, so
  the connecting thread unblocks instead of hanging), credential
  bytes and passphrase chars wiped in finally blocks after connect
  returns. Documented limitation: the connection is tied to this
  ViewModel/screen — navigating away disconnects. Prompt 06 must
  decide explicitly whether sessions survive navigation.
- `SessionScreen` — rewritten from shell to connection screen:
  host radio list, optional passphrase field for key hosts, Connect
  / Connecting + Cancel / Connected + Disconnect states, trust
  dialog (unknown vs changed wording, endpoint, fingerprint, key
  type, previously-trusted fingerprint for CHANGED), error text
  from fixed mappings only. The dark box remains a placeholder
  with an explicit note: connected here means "transport
  authenticated", not "terminal ready".
- Navigation: session destination now builds the SessionViewModel
  and passes an Add-host navigation callback. Hosts screen's
  session button label changed from "Session shell preview" to
  "Open connection".

Documentation:
- `docs/SSH_ENDPOINTS.md` — Linux/OpenSSH and Windows/OpenSSH
  Server prerequisites, including the explicit warning not to
  assume Windows has SSH enabled.
- `docs/SSH_INTEGRATION_BLOCKER.md` — the integration-test
  blocker, stated plainly (see §3).

## 2. Commands executed and exact results

- Static verification (passed): string-reference check — 0 missing
  (only Kotlin-unused string is `app_name`, used by the manifest);
  strings.xml parses; no logging call in transport sources (the
  single grep hit was the word "dialog" in a comment); sshj
  present in catalog + app build file.
- No Gradle build, no test run: NOT RUN, same sandbox reasons as
  prompts 02–04. CI is still the first real build/test gate.

## 3. Tests added/run

- JVM unit tests (written, NOT RUN locally): `SshTransportTest`
  (7 cases — fingerprint format/determinism/uniqueness, policy
  unknown/match/changed accept+reject, error mapping including
  the secret-leak check).
- Instrumented tests (written, IGNORED by design):
  `SshIntegrationTest` with `@Ignore` and five fixture tests that
  throw until the fixture exists. This is the prompt's own
  fallback: unit tests + a clearly marked integration blocker, not
  a mock called end-to-end.
- Run: none. Pass/fail: NOT RUN (unit) / IGNORED (integration).

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet.
- Compile risks to watch on the first CI run: sshj API surface
  (`setConnectTimeout`, `setTimeout`, `loadKeys(String, String)`,
  `HostKeyVerifier.verify` signature), the `host_form/{hostId}`
  route pattern, and Compose StateFlow collection. If CI flags
  any, the fix belongs in this phase's files.
- Integration acceptance NOT passed: "a real SSH handshake/auth
  succeeds against a controlled endpoint" is marked implemented,
  unit-tested, integration-blocked — not passed. The first
  real-server run (user on device, or CI once the fixture exists)
  must be reported with server type and result.

## 5. Security properties: proven vs assumed

- Proven by construction: no accept-all verifier in main code;
  trust persisted only after successful auth; error messages are
  fixed strings, never exception messages; credential bytes wiped
  in finally blocks; no secret logging in transport code.
- Proven by tests (once CI runs them): fingerprint format,
  policy decisions, error-mapping secret-leak check.
- Assumed, not proven: sshj behaves as documented on Android
  (needs CI build + device run); Keystore + sshj interaction on
  real devices; cancellation during socket connect (timeout-bound,
  not instant — documented, not claimed otherwise).
- Documented residual: Compose/String copies of passwords cannot
  be wiped (same residual as prompt 04); the transport wipes the
  byte/CharArray copies it owns.

## 6. Requirements implemented vs partial vs blocked

- Implemented (code scope): password + private-key auth, host-key
  verification via prompt-04 trust model with visible unknown/
  changed decisions, configurable timeouts, cancellation,
  disconnect with deterministic cleanup, non-secret error mapping,
  narrow transport API for later terminal/SFTP use, endpoint
  prerequisites documented.
- Partial: connection lifecycle is screen-tied (see SessionViewModel
  limitation); session survival across navigation is prompt 06's
  explicit decision.
- Blocked: build, test execution, real-server integration (see §3/§4).
- Not implemented, correctly: terminal UI (prompt 06), SFTP
  (prompt 07), tunnels/jump/proxy (prompt 10), any shell channel.

## 7. Evidence for claimed capabilities

- No connection success is claimed. The only claims with evidence
  are static checks (§2) and the test list (§3). Everything needing
  a run is labelled NOT RUN / IGNORED / BLOCKED.

## 8. Remaining issues and next prompt

- Prompt 06 — Interactive Terminal: shell channel, VT engine
  (ADR-04: own scoped Kotlin VT engine, ConnectBot termlib NOT
  adopted), keyboard/keys, resize, scrollback, session-survival
  decision, and the terminal placeholder becoming real.
- Open decisions carried forward: repository + CI push, application
  ID confirmation, UI-language confirmation (before prompt 14),
  SSH integration fixture.
- Per the user's new autonomous-continuation instruction, prompt
  06 proceeds without a go-ahead widget.

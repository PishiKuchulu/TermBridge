# TermBridge — Capability Matrix (Prompt 17)

Date: 2026-10-24. Audit only: no production code was changed in
this prompt. This matrix traces every user-visible capability
from its UI entry point through the implementation path to the
evidence that exists for it, and gives it exactly one status.

## Status definitions (read before the table)

- `VERIFIED` — evidence sufficient for the claim type exists.
  For absence claims (no AI, no cloud SDK) a complete static
  audit of the dependency list + manifest + strings IS
  sufficient evidence, so those rows can be VERIFIED without
  a run. For behaviour claims, VERIFIED would require an
  executed test; none exists yet, so no behaviour row is
  VERIFIED.
- `IMPLEMENTED_NOT_VERIFIED` — the code path exists and was
  statically checked (read/grepped), but no test, build, or
  device/server run has ever executed it. This is the honest
  status for the whole behaviour surface: the app has never
  been built or run anywhere (no repo, no CI, sandbox Gradle
  daemon + Java HTTPS limits unchanged).
- `PARTIAL` — part of the claim holds in code, part does not,
  or the claim's own acceptance criteria are unmet.
- `MOCK_ONLY` — a demo/mock presented as behaviour. None
  found (see §"Negative checks" below).
- `UNAVAILABLE` — deliberately not offered, with the reason
  shown in the UI.
- `BLOCKED` — the pack's proof gate failed; no implementation
  is offered, and a blocker report exists instead.

Test counts recount by grep in this prompt: 258 JVM `@Test`
methods, 23 instrumented `@Test` methods (5 runnable in
`KeystoreAndRoomTest` + `MainActivitySmokeTest`, 18 in
`SshIntegrationTest` under a class-level `@Ignore` for the
missing SSH fixture). All are NOT RUN. Nothing in this
matrix counts an ignored test as passed, and no mock is
presented as an end-to-end test.

## Capability table

| Capability / claim | UI entry point | Implementation path | Protocol / library | Evidence / test ID | Status | Known limits |
|---|---|---|---|---|---|---|
| FR-01 Host profiles: create, edit, delete, list, no account or sign-in | Hosts screen, host form | `HostsScreen` → `HostRepository` → Room `hosts` table; validation before write | Room 2.6.1 | `HostRepositoryTest` (written, NOT RUN); strings 318/317, 0 missing | IMPLEMENTED_NOT_VERIFIED | No persistence-across-restart run exists yet; that is a CI/device run, not a code gap. |
| FR-02 Password + SSH-key auth, passphrase for encrypted keys | Session screen (passphrase field for key hosts only), Files screen | `SshTransport.connect` → sshj auth; passphrase as `CharArray`, wiped in `finally` | sshj 0.40.0 | `SshTransportTest` (written, NOT RUN); `SshIntegrationTest` @Ignore'd (fixture) | IMPLEMENTED_NOT_VERIFIED | Ed25519 + RSA success against a real sshd is UNPROVEN; wrong-passphrase behaviour is code-only. |
| FR-03 Host-key warning on unknown key; changed key blocks | Session screen trust dialog (stored vs presented fingerprint) | `HostKeyVerificationPolicy` (UNKNOWN/MATCH/CHANGED) → `TrustDialog`; trust persisted only after successful auth (prompt 05, re-confirmed in audit 16) | sshj host-key API, SHA256: fingerprint | Trust/policy JVM tests (written, NOT RUN); integration @Ignore'd | IMPLEMENTED_NOT_VERIFIED | Post-fix behaviour change (prompt-16 fix pass): deleteHost now deletes the trust record, so a re-added host re-verifies. The prompt-04 "trust survives deletion" rule is REVERSED — see `SECURITY_FIXES_16.md` M2. |
| FR-04 Real interactive SSH shell: type, output, resize, disconnect, reconnect | Session screen terminal | `SessionViewModel` (activity-scoped) → `TerminalSession` (sshj shell channel, PTY xterm-256color) → own `VtParser` | sshj shell channel; VT CSI/SGR subset | `VtParserTest` incl. prompt-14 alternate-screen tests (written, NOT RUN); interactive-shell integration case @Ignore'd | IMPLEMENTED_NOT_VERIFIED | Alternate screen ?1049 implemented (prompt 14); ?47 still ignored; no reflow; no wide-char alignment; process death loses the session (no background service, documented). |
| FR-05 SFTP browse, upload/download, progress, cancellation, errors | Files screen | `FilesViewModel` (its OWN SSH connection — prompt-15 decision: KEEP SEPARATE) → `SftpManager` streaming with counting wrappers | sshj SFTP | `RemotePathTest`; SFTP integration cases @Ignore'd (fixture) | IMPLEMENTED_NOT_VERIFIED | Size-only integrity (no digest claim); single-entry delete only; browser starts at "/" root, not home; upload picker takes no initial directory (local folder is downloads-only); SFTP subsystem required on server. |
| FR-06 Command buttons: insert-only / execute-one / sequence, parameters, risky confirmation | Commands screen (manage) + Session screen buttons area (execute) | `CommandTemplate.prepare` → `CommandRunner` (insert has no `sendEnter`, re-read in audits 16 and 17) → `TerminalSender` | Own code; no protocol | `CommandTemplateTest`, `RiskyAndSecretTest`, `CommandValidatorTest`, `CommandRunnerTest`, `CommandRepositoryTest`, `ShellQuotingTest` (written, NOT RUN) | IMPLEMENTED_NOT_VERIFIED | Quoting guarantee is PER DECLARED SHELL (post-fix): POSIX `'\''`, PowerShell `''` doubling, cmd.exe parameterised buttons REFUSED (gate). Residual: the shell is user-declared and defaults to POSIX — a wrongly declared shell silently voids the guarantee (see audit finding R1). Risky heuristic is a documented non-guarantee, not a classifier. |
| FR-07 Per-host env vars + working directories, scoped, limitations shown | Host environment screen (tune icon on host row) | `HostEnvironmentRepository` (values Keystore-encrypted, write-only) → sshj `setEnvVar` before `startShell`; one visible `cd` per shell; SFTP start directory (POSIX only) | sshj `Session.setEnvVar` | `EnvironmentValidatorTest`, `HostEnvironmentRepositoryTest`, `CommandButtonInteractionTest` (written, NOT RUN); env integration cases @Ignore'd | IMPLEMENTED_NOT_VERIFIED | OpenSSH default `AcceptEnv` (LANG/LC_* only) means most custom vars are rejected on default servers — reported by name, not hidden. Buttons NEVER modified by env/cd (grep-verified). Local folder covers downloads only. |
| FR-08a Local + remote port forwarding | Advanced screen, tunnel session | `TunnelManager` (sshj `newLocalPortForwarder` + listen; `RemotePortForwarder.bind` + cancel) on a dedicated destination-scoped connection | sshj forwarding | `AdvancedValidationTest`, `AdvancedRepositoryTest` (written, NOT RUN); forwarding integration cases @Ignore'd | IMPLEMENTED_NOT_VERIFIED | Bind default loopback 127.0.0.1; non-loopback needs stored `allowNonLoopback` flag with full-exposure label. No per-tunnel stop (session-wide stop only). Forwarding behaviour on Android unproven. |
| FR-08b Jump hosts (chains) | Advanced screen, chain editor + tunnel session | `SshTransport.connectChain` (`newDirectConnection` + `connectVia`); per-hop credentials loaded at connect time only, wiped; trust for ALL hops persists only after target auth | sshj direct-tcpip | `AdvancedValidationTest` chain cases (written, NOT RUN); jump integration case @Ignore'd | IMPLEMENTED_NOT_VERIFIED | Proxy applies to first hop only. Chain applies whole or not at all on import. |
| FR-08c Unauthenticated SOCKS proxy | Advanced screen, proxy row | `SocksSocketFactory` + JDK SOCKS socket, set before connect | JDK `Proxy.Type.SOCKS` via sshj socket factory | Validation tests (written, NOT RUN); SOCKS integration case @Ignore'd | IMPLEMENTED_NOT_VERIFIED | Proxy operator sees target host/port + timing (warned in UI, `proxy_metadata_note`). SOCKS handshake through sshj's socket path on device unproven. |
| FR-08d Dynamic/SOCKS forwarding, HTTP CONNECT proxy, ProxyCommand, authenticated SOCKS | Advanced screen "Not available" section | None — no code path | — | sshj 0.40.0 jar class-listing (prompt 10) | UNAVAILABLE | Reasons shown in UI: no SOCKS server class in sshj; no CONNECT client; no sshj ProxyCommand API (app spawns no processes); authenticated SOCKS would need JVM-global Authenticator (leaks across connections). Not offered as working. |
| FR-09 Import/export + encrypted backup, preview, round-trip | Settings → Backup screen (SAF CreateDocument/OpenDocument) | `BackupService` + `BackupCodec` (TermBridge backup JSON v1 only) + `ExportCrypto` (PBKDF2WithHmacSHA256 600k + AES-256-GCM, fresh salt/IV) | Own JSON format; JCA | `ExportCryptoTest`, `BackupCodecTest`, `BackupServiceTest` (written, NOT RUN) | IMPLEMENTED_NOT_VERIFIED | Default export omits credentials AND env values entirely (never loaded-and-stripped). Encrypted mode only carries secrets. Trust records, SAF URIs, key passphrases NEVER exported. Import conflicts SKIPPED by default; replace needs checkbox + confirmation and keeps the existing credential unless the backup carries one. Import replace is now transactional (fix pass M3). On-device SAF round-trip is MANUAL, not run. |
| FR-10 RDP to compatible Windows | Desktop screen, RDP row | None — no code, no native module, no dependency | FreeRDP 3.x is the CANDIDATE only, not a dependency | `docs/RDP_NOTES.md` blocker report (prompt 12) | BLOCKED | Gate failed on environment (no NDK, no CI, no Windows endpoint, no native infrastructure). UI shows the blocker + candidate + unblock conditions; NO connect button. Windows Home cannot host (Pro/Enterprise/Education only). Licence inventory for the pinned tree is OPEN (prompt-01 Fedora note UNCONFIRMED). |
| FR-11 VNC to compatible Linux | Desktop screen, VNC row | Pure-Kotlin RFB core ONLY: `RfbProtocol`, `RfbAuth` (DES challenge-response), `RfbFramebuffer`, `RfbMessages`, `RfbDecoder` (Raw/CopyRect/RRE/CoRRE/Hextile), `RfbConnection` — NOT wired to any screen, ViewModel, or route (grep-verified in prompts 13 and 17) | RFB 3.3/3.7/3.8 (RFC 6143) | `RfbAuthTest`, `RfbDecoderTest`, `RfbMessagesTest`, `RfbConnectionTest` (written, NOT RUN; loopback-peer tests are handshake-sequencing proofs only) | PARTIAL | Core IMPLEMENTED_NOT_VERIFIED; session BLOCKED on the controlled-server proof gate (no server/device/CI). UI string says exactly that ("never connected to a real VNC server"). VeNCrypt/TLS/Tight security and Tight/ZRLE encodings refused, never misrendered. VNC Auth weakness (DES + 8-char) disclosed in code, docs, and UI. Native LibVNCClient REJECTED (GPLv2 + NDK blocker). |
| FR-12 Phone-first UX: touch targets, keyboard, accessibility, portrait/landscape | All screens | Design tokens (8/16/24dp, 48dp touch), whole-row radio rows, NavHost IME padding, pinned auto-scroll, live-session top-bar action | Compose | `TerminalScrollTest`, `RoutesTest`, smoke test (written, NOT RUN) | PARTIAL | Statically implemented as far as checkable; the prompt-14 device checklist (rotation, keyboard, background/foreground, low-memory, TalkBack, dark theme + largest font) is DOCUMENTED, NOT RUN — the requirement's own acceptance criterion (checklist passes on a real device) is UNMET until a device run. |
| NFR-01 Secrets encrypted at rest, never plaintext/logged/hardcoded | Whole app | Keystore AES-256-GCM (alias `termbridge_master_key`, fresh IV), `SecretCrypto` for credentials + env values; passphrase never persisted; buffers wiped in `finally` | AndroidKeyStore; JCA | Secret-handling JVM tests (written, NOT RUN); grep: zero `Log.`/`println` in main sources (re-verified in this prompt) | IMPLEMENTED_NOT_VERIFIED | Keystore OEM variance unproven (device run needed). Residual documented since prompt 04: Compose text fields hold unwipable Strings (cleared on save, may linger until GC). `SftpException` still takes a cause (fix-pass residual, deferred). |
| NFR-02 No silent host-key/certificate bypass | Whole app | `HostKeyVerificationPolicy` requires a decision; no accept-all path in main sources (grep-verified, audits 16 and 17); no accept-all test double found | — | Policy tests (written, NOT RUN) | IMPLEMENTED_NOT_VERIFIED | None in code. The guarantee is code-inspection only until the fixture runs. |
| NFR-03 No mandatory cloud account/sync, no telemetry off-device | Whole app | No cloud SDK, no analytics SDK; only INTERNET + ACCESS_NETWORK_STATE; `allowBackup=false`; SAF-only file access | — | Dependency list + manifest audit in this prompt (complete: Compose, Navigation, Room, sshj, coroutines, kotlinx-serialization-json, Material Icons Extended — all Apache-2.0 except sshj's own Apache-2.0) | VERIFIED | Static evidence is sufficient for this absence claim. Note: "no telemetry" holds for MVP code; a future crash reporter would reopen it (audit 16 L7 class). |
| NFR-04 Terminal/SFTP stay responsive; no UI-thread blocking | Terminal, Files screens | Network/disk on `Dispatchers.IO`; streaming SFTP (no whole-file buffering); bounded 1000-line scrollback; lazy lists; StateFlow conflation | Coroutines | No UI-thread test exists (grep: no main-thread check in any test) | PARTIAL | Architecture supports the claim; the requirement's own evidence (UI-thread checks in tests) is ABSENT. No device measurement exists — performance claims in prompt 14 are structural only. |
| NFR-05 Protocol support conditional + documented | Docs + Desktop/Advanced screens | This matrix; per-protocol docs (`SSH_ENDPOINTS`, `SFTP_NOTES`, `ADVANCED_SSH`, `RDP_NOTES`, `VNC_NOTES`, `ENVIRONMENT_AND_PATHS`) | — | This document | PARTIAL | The matrix now exists, but every server-side prerequisite is still code/doc-level, not run-proven. |
| NFR-06 Timeouts, cancellation, reconnect, foreground/background | Transport, terminal, SFTP, tunnels | Connect 15s / socket 30s; cancellation checked per SFTP read/write; deterministic disconnect (reverse order for chains); tunnels stop on destination pop | sshj | Failure-path JVM tests (written, NOT RUN); integration cases @Ignore'd | PARTIAL | Reconnect is manual (re-tap connect); process death loses the session (documented, no background service); background/foreground transition behaviour on a real device is UNPROVEN. |
| NFR-07 No AI/LLM/agent feature, dependency, wording, or roadmap item | Whole app | None present | — | Dependency + string audit in this prompt (only false positive: `fillMaxWidth` matching case-insensitive "llm") | VERIFIED | Static evidence is sufficient for this absence claim. |
| NFR-08 Release hygiene: artifacts exclude caches, secrets, machine state, signing keys, governance internals | Repo-to-be, release step (prompt 18) | `.gitignore` blocks keystores/p12/pem/key, local.properties, build output, pfp/prompt-pack dirs; no secrets found by grep in tree; no signing config in this phase | — | `.gitignore` + grep audit in this prompt | PARTIAL | Release packaging itself is prompt 18, not done. Discrepancy: `docs/PROJECT_BRIEF.md` (Governance section) and `docs/BASELINE_REPORT_00.md` NAME the private governance framework — content is not copied, but the name appears in docs that would ship with a public repo. Prompt 18 must remove or rename those paragraphs (see audit finding R16). |

## Negative checks (pack §"inspect the full repository")

- TODO/FIXME/stub/no-op handlers: NONE in production code.
  The only "placeholder" hits are text-field hint parameters
  (`placeholder = ...`) and KDoc words, not stub behaviour.
  No empty `onClick = {}` handlers found.
- Fake success responses: NONE found. Error paths map to
  fixed user messages (`SshError`, `SftpError`,
  `TunnelException`, `ExportCryptoException`); partial
  downloads are deleted best-effort, never claimed.
- Swallowed exceptions: reviewed, acceptable. Remaining
  catches are (a) cleanup best-effort that must not mask
  the original error (`SshTransport` disconnect paths),
  (b) mapped to a fixed error type, or (c) the
  fixed-in-pass tunnel `stopAll` removal-before-cancel.
  The audit-16 L2 listen-loop swallow is FIXED.
- Tests asserting mocks while docs claim end-to-end: NONE.
  Every server-dependent test is class-level `@Ignore`d
  under the fixture blocker and is never counted as
  passed; loopback RFB peer tests are labelled
  handshake-sequencing proofs only, and the VNC-Auth
  peer check is circular by design (same auth code both
  sides) — the crypto rests on the FIPS DES vector and
  hand-computed reversal tests instead.
- Dead code: `RfbConnection`/`RfbSession` are intentionally
  unwired (the blocked session), documented in
  `VNC_NOTES.md` — not dead code by accident. No other
  dead code was found by grep; a compiler-level
  unused-code check is IMPOSSIBLE without a build and is
  recorded as a limit, not claimed.
- Misleading labels: three found, all for prompt 18 (see
  `CAPABILITY_CLAIM_AUDIT.md` R2, R3, R5): About licenses
  list only foundation libraries; `desktop_body` says
  proof is "in later phases" though prompts 12–13 already
  ran; the requirements matrix's prompt-15 status section
  predates the fix-pass behaviour changes (a dated
  prompt-17 section is appended by this prompt).
- PFP internals or secrets in the tree: NO governance
  content copied, NO secret material found (test PEM/token
  literals are placeholders). The two governance NAME
  mentions above (R16) are the only exception, and they
  name, not copy, the methodology.

## What "verified" does NOT mean here

No row in this matrix means "ran and passed". The only
rows marked VERIFIED are absence claims whose evidence is
a complete static audit. Every behaviour claim awaits the
same three gates, in order: (1) a repository + CI run
(first build and first executed test suite), (2) the
controlled SSH/SFTP fixture (which un-ignores 18
integration tests), (3) a device run (Keystore/OEM
behaviour, DES/PBKDF2 on API 26+, the prompt-14 device
checklist, SAF round-trip). Until then, "implemented"
and "verified" must never be interchanged in release
notes — that is the central discrepancy this audit
exists to prevent.

## Addendum, 2026-10-25 — remediation (rows above are history, not rewritten)

Gate 1 from the closing paragraph PASSED: CI run #13
(commit 0ce9772, 2026-10-25) executed the unit suite and
the emulator instrumented suite green. "NOT RUN" in the
rows above is therefore stale for the suites that ran —
but no row is upgraded here, because the matrix's own
rule reserves status changes for a re-audit, not an
append.

Test-count delta (by grep, this pass): JVM tests 267 +
13 new `ShellGateTest` + 2 new `SecretRedactorTest`
cases = 282. Instrumented methods still total 23, but
their composition changed: 5 runnable (as before) + 12
newly implemented `SshIntegrationTest` cases against the
controlled fixture + 6 still-@Ignore'd stubs with
specific reasons (see `docs/SSH_INTEGRATION_BLOCKER.md`).
Gate 2 (fixture) is therefore partially met and pending
CI proof; gate 3 (device run) is still fully open.

New behaviour since the audit: the shell-confirmation
gate (remediation phase 1) changes the FR-08-adjacent
command-button claim — parameterised buttons are now
refused at the execution boundary while the shell is
unconfirmed or stale. See `docs/REMEDIATION_REPORT.md`.

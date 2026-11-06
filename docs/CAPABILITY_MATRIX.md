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
  device/server run has ever executed it. (Correction,
  2026-11-06, audit #6 TB-015: the app HAS been built and run
  in CI since 2026-10-25 — GitHub Actions is green including
  the emulator fixture suite, and signed releases ship from
  tags. The sentence this replaces described the pre-repo
  handoff state.)
- `PARTIAL` — part of the claim holds in code, part does not,
  or the claim's own acceptance criteria are unmet.
- `MOCK_ONLY` — a demo/mock presented as behaviour. None
  found (see §"Negative checks" below).
- `UNAVAILABLE` — deliberately not offered, with the reason
  shown in the UI.
- `BLOCKED` — the pack's proof gate failed; no implementation
  is offered, and a blocker report exists instead.

Test counts recount by grep (2026-11-06, audit #6 fix wave): 516 JVM
`@Test` methods — all run and pass in CI and in the local
harness — and 30 instrumented `@Test` methods across
`KeystoreAndRoomTest`, `MainActivitySmokeTest`,
`CryptoProviderTest`, `RfbIntegrationTest` and
`SshIntegrationTest` (the tier-2 cases there remain
`@Ignore`'d; `RfbIntegrationTest` is skipped in CI). Nothing
in this matrix counts an ignored test as passed, and no
mock is presented as an end-to-end test.

## Capability table

| Capability / claim | UI entry point | Implementation path | Protocol / library | Evidence / test ID | Status | Known limits |
|---|---|---|---|---|---|---|
| FR-01 Host profiles: create, edit, delete, list, no account or sign-in | Hosts screen, host form | `HostsScreen` → `HostRepository` → Room `hosts` table; validation before write | Room 2.6.1 | `HostRepositoryTest` (JVM unit tests, run in CI and locally); strings 318/317, 0 missing | IMPLEMENTED_NOT_VERIFIED | No persistence-across-restart run exists yet; that is a CI/device run, not a code gap. |
| FR-02 Password + SSH-key auth, passphrase for encrypted keys | Session screen (passphrase field for key hosts only), Files screen | `SshTransport.connect` → sshj auth; passphrase as `CharArray`, wiped in `finally` | sshj 0.40.0 | `SshTransportTest` (JVM unit tests, run in CI and locally); `SshIntegrationTest` (13 tier-1 cases implemented, 6 tier-2 stubs @Ignore'd; key-auth case added after review #2) | IMPLEMENTED_NOT_VERIFIED | Ed25519 + RSA success against a real sshd is UNPROVEN; wrong-passphrase behaviour is code-only. |
| FR-03 Host-key warning on unknown key; changed key blocks | Session screen trust dialog (stored vs presented fingerprint) | `HostKeyVerificationPolicy` (UNKNOWN/MATCH/CHANGED) → `TrustDialog`; trust persisted only after successful auth (prompt 05, re-confirmed in audit 16) | sshj host-key API, SHA256: fingerprint | Trust/policy JVM tests (JVM unit tests, run in CI and locally); unknown-host integration case implemented, changed-host-key case still @Ignore'd | IMPLEMENTED_NOT_VERIFIED | Post-fix behaviour change (prompt-16 fix pass): deleteHost now deletes the trust record, so a re-added host re-verifies. The prompt-04 "trust survives deletion" rule is REVERSED — see `SECURITY_FIXES_16.md` M2. |
| FR-04 Real interactive SSH shell: type, output, resize, disconnect, reconnect | Session screen terminal | `SessionViewModel` (activity-scoped) → `TerminalSession` (sshj shell channel, PTY xterm-256color) → own `VtParser` | sshj shell channel; VT CSI/SGR subset | `VtParserTest` incl. prompt-14 alternate-screen tests (JVM unit tests, run in CI and locally); interactive-shell integration case @Ignore'd | IMPLEMENTED_NOT_VERIFIED | Alternate screen ?1049 implemented (prompt 14); ?47 still ignored; no reflow; no wide-char alignment; process death loses the session (no background service, documented). |
| FR-05 SFTP browse, upload/download, progress, cancellation, errors | Files screen | `FilesViewModel` (its OWN SSH connection — prompt-15 decision: KEEP SEPARATE) → `SftpManager` streaming with counting wrappers | sshj SFTP | `RemotePathTest`; SFTP integration cases @Ignore'd (fixture) | IMPLEMENTED_NOT_VERIFIED | Size-only integrity (no digest claim); single-entry delete only; browser starts at "/" root, not home; upload picker takes no initial directory (local folder is downloads-only); SFTP subsystem required on server. |
| FR-06 Command buttons: insert-only / execute-one / sequence, parameters, risky confirmation | Commands screen (manage) + Session screen buttons area (execute) | `CommandTemplate.prepare` → `CommandRunner` (insert has no `sendEnter`, re-read in audits 16 and 17) → `TerminalSender` | Own code; no protocol | `CommandTemplateTest`, `RiskyAndSecretTest`, `CommandValidatorTest`, `CommandRunnerTest`, `CommandRepositoryTest`, `ShellQuotingTest` (JVM unit tests, run in CI and locally) | IMPLEMENTED_NOT_VERIFIED | Quoting guarantee is PER DECLARED SHELL (post-fix): POSIX `'\''`, PowerShell `''` doubling, cmd.exe parameterised buttons REFUSED (gate). Residual: the shell is user-declared and defaults to POSIX — a wrongly declared shell silently voids the guarantee (see audit finding R1). Risky heuristic is a documented non-guarantee, not a classifier. |
| FR-07 Per-host env vars + working directories, scoped, limitations shown | Host environment screen (tune icon on host row) | `HostEnvironmentRepository` (values Keystore-encrypted, write-only) → sshj `setEnvVar` before `startShell`; one visible `cd` per shell; SFTP start directory (POSIX only) | sshj `Session.setEnvVar` | `EnvironmentValidatorTest`, `HostEnvironmentRepositoryTest`, `CommandButtonInteractionTest` (JVM unit tests, run in CI and locally); env integration cases @Ignore'd | IMPLEMENTED_NOT_VERIFIED | OpenSSH default `AcceptEnv` (LANG/LC_* only) means most custom vars are rejected on default servers — reported by name, not hidden. Buttons NEVER modified by env/cd (grep-verified). Local folder covers downloads only. |
| FR-08a Local + remote port forwarding | Advanced screen, tunnel session | `TunnelManager` (sshj `newLocalPortForwarder` + listen; `RemotePortForwarder.bind` + cancel) on a dedicated destination-scoped connection | sshj forwarding | `AdvancedValidationTest`, `AdvancedRepositoryTest` (JVM unit tests, run in CI and locally); forwarding integration cases @Ignore'd | IMPLEMENTED_NOT_VERIFIED | Bind default loopback 127.0.0.1; non-loopback needs stored `allowNonLoopback` flag with full-exposure label. No per-tunnel stop (session-wide stop only). Forwarding behaviour on Android unproven. |
| FR-08b Jump hosts (chains) | Advanced screen, chain editor + tunnel session | `SshTransport.connectChain` (`newDirectConnection` + `connectVia`); per-hop credentials loaded at connect time only, wiped; trust for ALL hops persists only after target auth | sshj direct-tcpip | `AdvancedValidationTest` chain cases (JVM unit tests, run in CI and locally); jump integration case @Ignore'd | IMPLEMENTED_NOT_VERIFIED | Proxy applies to first hop only. Chain applies whole or not at all on import. |
| FR-08c Unauthenticated SOCKS proxy | Advanced screen, proxy row | `SocksSocketFactory` + JDK SOCKS socket, set before connect | JDK `Proxy.Type.SOCKS` via sshj socket factory | Validation tests (JVM unit tests, run in CI and locally); SOCKS integration case @Ignore'd | IMPLEMENTED_NOT_VERIFIED | Proxy operator sees target host/port + timing (warned in UI, `proxy_metadata_note`). SOCKS handshake through sshj's socket path on device unproven. |
| FR-08d Dynamic/SOCKS forwarding, HTTP CONNECT proxy, ProxyCommand, authenticated SOCKS | Advanced screen "Not available" section | None — no code path | — | sshj 0.40.0 jar class-listing (prompt 10) | UNAVAILABLE | Reasons shown in UI: no SOCKS server class in sshj; no CONNECT client; no sshj ProxyCommand API (app spawns no processes); authenticated SOCKS would need JVM-global Authenticator (leaks across connections). Not offered as working. |
| FR-09 Import/export + encrypted backup, preview, round-trip | Settings → Backup screen (SAF CreateDocument/OpenDocument) | `BackupService` + `BackupCodec` (TermBridge backup JSON v1 only) + `ExportCrypto` (PBKDF2WithHmacSHA256 600k + AES-256-GCM, fresh salt/IV) | Own JSON format; JCA | `ExportCryptoTest`, `BackupCodecTest`, `BackupServiceTest` (JVM unit tests, run in CI and locally) | IMPLEMENTED_NOT_VERIFIED | Default export omits credentials AND env values entirely (never loaded-and-stripped). Encrypted mode only carries secrets. Trust records, SAF URIs, key passphrases NEVER exported. Import conflicts SKIPPED by default; replace needs checkbox + confirmation and keeps the existing credential unless the backup carries one. Import replace is now transactional (fix pass M3). On-device SAF round-trip is MANUAL, not run. |
| FR-10 RDP to compatible Windows | Desktop screen, RDP row | None — no code, no native module, no dependency | FreeRDP 3.x is the CANDIDATE only, not a dependency | `docs/RDP_NOTES.md` blocker report (prompt 12) | BLOCKED | Gate failed on environment (no NDK, no CI, no Windows endpoint, no native infrastructure). UI shows the blocker + candidate + unblock conditions; NO connect button. Windows Home cannot host (Pro/Enterprise/Education only). Licence inventory for the pinned tree is OPEN (prompt-01 Fedora note UNCONFIRMED). |
| FR-11 VNC to compatible Linux | Desktop screen, VNC row | Pure-Kotlin RFB core: `RfbProtocol`, `RfbAuth` (DES challenge-response), `RfbFramebuffer`, `RfbMessages`, `RfbDecoder` (Raw/CopyRect/RRE/CoRRE/Hextile), `RfbConnection` — wired to `RemoteDesktopScreen` / `RemoteDesktopViewModel` since parity phase 4 (touch-as-mouse, key row, Unicode typing) | RFB 3.3/3.7/3.8 (RFC 6143) | `RfbAuthTest`, `RfbDecoderTest`, `RfbMessagesTest`, `RfbConnectionTest` (JVM unit tests, run in CI and locally — 316 passing 2026-11-04; loopback-peer tests are handshake-sequencing proofs only); `RfbIntegrationTest` against a real server exists but is skipped in CI | PARTIAL | Core + session UI IMPLEMENTED; real-server proof still OPEN (item F-03a in `docs/PARITY_TRACEABILITY.md`). VeNCrypt/TLS/Tight security and Tight/ZRLE encodings refused, never misrendered. VNC Auth weakness (DES + 8-char) disclosed in code, docs, and UI. Native LibVNCClient REJECTED (GPLv2 + NDK blocker). |
| FR-12 Phone-first UX: touch targets, keyboard, accessibility, portrait/landscape | All screens | Design tokens (8/16/24dp, 48dp touch), whole-row radio rows, NavHost IME padding, pinned auto-scroll, live-session top-bar action | Compose | `TerminalScrollTest`, `RoutesTest` (JVM unit tests, run in CI and locally), smoke test (instrumented, runs in the CI emulator job) | PARTIAL | Statically implemented as far as checkable; the prompt-14 device checklist (rotation, keyboard, background/foreground, low-memory, TalkBack, dark theme + largest font) is DOCUMENTED, NOT RUN — the requirement's own acceptance criterion (checklist passes on a real device) is UNMET until a device run. |
| NFR-01 Secrets encrypted at rest, never plaintext/logged/hardcoded | Whole app | Keystore AES-256-GCM (alias `termbridge_master_key`, fresh IV), `SecretCrypto` for credentials + env values; passphrase never persisted; buffers wiped in `finally` | AndroidKeyStore; JCA | Secret-handling JVM tests (run in CI and locally); grep: zero `Log.`/`println` in main sources (re-verified in this prompt) | IMPLEMENTED_NOT_VERIFIED | Keystore OEM variance unproven (device run needed). Residual documented since prompt 04: Compose text fields hold unwipable Strings (cleared on save, may linger until GC). `SftpException` still takes a cause (fix-pass residual, deferred). |
| NFR-02 No silent host-key/certificate bypass | Whole app | `HostKeyVerificationPolicy` requires a decision; no accept-all path in main sources (grep-verified, audits 16 and 17); no accept-all test double found | — | Policy tests (JVM unit tests, run in CI and locally) | IMPLEMENTED_NOT_VERIFIED | None in code. The guarantee is code-inspection only until the fixture runs. |
| NFR-03 No mandatory cloud account/sync, no telemetry off-device | Whole app | No cloud SDK, no analytics SDK; the app manifest declares only INTERNET + ACCESS_NETWORK_STATE (the merged manifest adds USE_BIOMETRIC + USE_FINGERPRINT from the AndroidX biometric library); `allowBackup=false`; SAF-only file access | — | Dependency list + manifest audit in this prompt (complete: Compose, Navigation, AndroidX Biometric, Room, sshj, coroutines, kotlinx-serialization-json, Material Icons Extended — all Apache-2.0 except sshj's own Apache-2.0) | VERIFIED | Static evidence is sufficient for this absence claim. Note: "no telemetry" holds for MVP code; a future crash reporter would reopen it (audit 16 L7 class). |
| NFR-04 Terminal/SFTP stay responsive; no UI-thread blocking | Terminal, Files screens | Network/disk on `Dispatchers.IO`; streaming SFTP (no whole-file buffering); bounded 1000-line scrollback; lazy lists; StateFlow conflation | Coroutines | No UI-thread test exists (grep: no main-thread check in any test) | PARTIAL | Architecture supports the claim; the requirement's own evidence (UI-thread checks in tests) is ABSENT. No device measurement exists — performance claims in prompt 14 are structural only. |
| NFR-05 Protocol support conditional + documented | Docs + Desktop/Advanced screens | This matrix; per-protocol docs (`SSH_ENDPOINTS`, `SFTP_NOTES`, `ADVANCED_SSH`, `RDP_NOTES`, `VNC_NOTES`, `ENVIRONMENT_AND_PATHS`) | — | This document | PARTIAL | The matrix now exists, but every server-side prerequisite is still code/doc-level, not run-proven. |
| NFR-06 Timeouts, cancellation, reconnect, foreground/background | Transport, terminal, SFTP, tunnels | Connect 15s / socket 30s; cancellation checked per SFTP read/write; deterministic disconnect (reverse order for chains); tunnels stop on destination pop | sshj | Failure-path JVM tests (JVM unit tests, run in CI and locally); integration cases @Ignore'd | PARTIAL | Reconnect is manual (re-tap connect); process death loses the session (documented, no background service); background/foreground transition behaviour on a real device is UNPROVEN. |
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
their composition changed: 5 runnable (as before) + 13
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

## Addendum, 2026-11-04 — R-04 documentation accuracy pass

Under the owner's R-04 order, factually wrong statements in
this matrix were corrected IN PLACE (superseding, for factual
errors only, the no-rewrite convention of the 2026-10-25
addendum): the NFR-03 permission statement now includes the
biometric library's merged-manifest permissions; the FR-11
row now records that the RFB core is wired to a remote
desktop screen (parity phase 4) with the real-server proof
still open; JVM test rows no longer say "NOT RUN" — the unit
suite runs in CI and locally (316 tests passing, 2026-11-04).
Historical addenda above still cite `docs/REMEDIATION_REPORT.md`,
which does not exist on this branch; the current remediation
record is `docs/REVIEW_2_REMEDIATION.md`, and remaining work
is tracked in `docs/PARITY_TRACEABILITY.md`. A matching
refresh of `docs/TEST_MATRIX.md` is still pending.

## Addendum, 2026-11-04 — items T-01 + F-01 (keychain, credential files)

Implemented locally on top of remote main 7267dd0; CI proof
pending the first push. A keychain stores named passwords and
private keys Keystore-encrypted (new `keychain_entries` table,
database v6, MIGRATION_5_6); a host either stores a credential
inline or links to one entry (`hosts.keychainEntryId`,
repository-enforced integrity: deleting a referenced entry is
refused unless forced, which detaches the hosts). Every
credential consumer resolves through
`HostRepository.loadCredential`, so linked hosts work in
sessions, files, tunnels and backups with no consumer changes.
Keys can be typed, pasted, generated (Ed25519 via the bundled
Bouncy Castle lightweight API in OpenSSH format; RSA via JCA
as PKCS#8), or imported from a file (SAF picker; classifier
caps at 64 KB and rejects binary input; an imported key is
saved only after a real sshj load proves it, with the OpenSSH
SHA256 fingerprint shown first; a key passphrase is used for
that proof only and never stored). Encrypted backups carry
keychain material in a dedicated section (hosts reference
entries by label, never duplicated material); non-secret
exports carry entry metadata only, and import reports entries
it could not restore. Local evidence: harness 355 JVM tests
passing, including KeychainRepositoryTest (11),
KeyFileParserTest (12), KeyGenerationTest (5) and the backup
round-trip tests. Compose UI compilation is proven by CI, not
locally.

## Addendum, 2026-11-04 — item T-02 (global snippets)

Implemented locally on top of the T-01 state above; CI proof
pending the first push. Correction to the addendum above:
since group G2, Compose UI compilation is ALSO proven locally
by `tests/local/ui-check.sh` (whole-app type-check with the
real Compose compiler), and every group since has passed it.
Global snippets are command buttons that belong to no host:
they live in their own tables (`snippets`, `snippet_steps`,
`snippet_variables`; database v7, MIGRATION_6_7) mirroring the
button tables, because the `command_buttons` host foreign key
is load-bearing and a sentinel host row would have corrupted
host deletion. SnippetRepository applies the same validator
(including the secret scan) as CommandRepository. The Commands
editor manages them under a "Global snippets" entry; every
session — including quick-connect — offers the host's own
buttons followed by the globals. Backups carry them in an
optional "snippets" section (old files still decode) and
import is additive with per-snippet failure warnings. Local
evidence (corrected 2026-11-04): the first harness run FAILED
— 408 tests, 1 failure — because the snippet round-trip test
put sequence steps on an EXECUTE_ONE button, which the shared
validator correctly rejects (a test bug, not a product bug);
the ui-check passed. The test was fixed during item T-22
(mode SEQUENCE, as the format requires) and the combined
harness then passed: 419 JVM tests OK, including
SnippetRepositoryTest (4) and the backup codec/service
round-trips, migration audit chain to v8.

## Addendum, 2026-11-04 — item T-22 (hosts list organization)

Implemented locally; CI proof pending the first push. The
hosts list now has: search (name, hostname, username, group,
tags), tag filter chips, three sort orders (name, last
connected, recently added) with favorites pinned first under
every order, group sections (named groups A→Z, ungrouped
last), a per-row favorite star, the last-connected time, and
a multi-select mode with confirmed bulk delete. Database v8
(MIGRATION_7_8) adds favorite, groupName and lastConnectedAt
columns to hosts; the list rules are pure functions in
domain/HostListLogic with JVM tests (HostListLogicTest, 9).
The session layer stamps lastConnectedAt on a successful
connect; backups export favorite and groupName (optional
keys, old files still decode) but deliberately not
lastConnectedAt, which is device-local history. Local
evidence: harness 419 JVM tests passing.

## Addendum, 2026-11-04 — item T-18 (pattern lock, auto-lock options)

The app lock now has two credential methods, one active at a
time: the existing PIN and a new 3x3 pattern. The pattern's
rules live in domain/PatternLock.kt (at least 4 dots after
Android-style jumped-midpoint normalization); its canonical
string is hashed through the same salted PBKDF2 path as the
PIN, so verification and the failed-attempt backoff are a
single shared code path in AppLockStore and a pattern is
never stored. Setting one method replaces the other; turning
the lock off verifies the ACTIVE method first (PIN field or
pattern grid). The auto-lock delay is now a choice —
immediately, 30 seconds (the previous fixed behaviour, still
the default), 1 minute, 5 minutes — held by the store and
read by MainActivity instead of a hard-coded constant.
Settings also gained lock-now and a cover-in-recents
preference (default on): while the lock is enabled and the
preference is on, FLAG_SECURE blanks the app's recents card,
independently of the block-screenshots setting. Evidence:
426 JVM tests passing locally (PatternLockTest 7), Compose
UI type-check passing locally; CI pending the first push of
this batch.

## Addendum, 2026-11-04 — item T-19 (volume keys, shake)

Both hardware actions are off by default and, while off,
change nothing: the volume keys keep their system behaviour
and no sensor listener is registered. Volume keys can be set
to font size, the Ctrl latch, page scroll, or the arrow keys;
they are intercepted only while the session screen is
showing and a session is connected, and the activity falls
back to system volume whenever the session layer does not
consume a press. Shake can clear the screen (a purely local
parser clear — nothing is typed into the shell; on the
alternate screen only the visible grid blanks) or disconnect.
Detection is a pure, unit-tested threshold over the
accelerometer (two strong jolts within a second, with a
refractory period so one shake cannot fire twice). The
mapping tables and the detector are covered by
DeviceActionsTest; the parser clear by VtParserTest. Local
evidence: harness 433 JVM tests passing, Compose UI
type-check passing; CI pending the first push of this batch,
and the physical gestures themselves are not device-proven.

## Addendum, 2026-11-04 — item F-02 (env export fallback)

Environment variables the server refuses through the SSH
env channel (its sshd has no matching AcceptEnv — a
server-admin setting) can now be applied by an opt-in,
per-host fallback: one visible export line per refused
variable, typed into the fresh shell before the initial cd,
rendered with the shell's own quoting and refused outright
when a value cannot be typed safely. The toggle is off by
default, requires the confirmed shell, is device-local
(not part of backups), and the session screen names the
fallback-applied variables separately. A grep audit found
no flow that assumes or needs root. Evidence: 437 JVM
tests passing locally (ExportCommandTest 4, migration
audit extended to DB v9), Compose UI type-check passing;
CI pending the first push of this batch. A live server's
rejection driving the fallback end-to-end is not yet
proven on a device or the fixture.

## Addendum, 2026-11-04 — item T-21 (SFTP polish)

The file manager gained: multi-select with bulk delete and
bulk download; folder download (tree recreated in the host's
local folder) and folder upload (SAF tree walked, remote
directories created, files queued); a sequential transfer
queue with per-file progress and a remaining count; cancel
that stops the queue as well; resume in both directions —
downloads append to the kept partial file from the exact
byte offset, uploads continue into the retained temporary
file after its size is re-verified, and the whole-file size
check still proves the finished upload; listing search,
Name/Size/Type sorting and a hidden-files toggle; and
Open / Share actions on a completed download. Evidence:
442 JVM tests passing locally (RemoteListLogicTest 5),
Compose UI type-check passing; CI pending the first push
of this batch. Live queue/folder/resume behaviour against
a real server is not yet proven.

## Addendum, 2026-11-04 — item T-20 (port forwarding proven)

Port forwarding is now covered end to end. The app gained
per-forward stop (one forward can be stopped from the
Advanced screen while the session and other forwards stay
up; the active-forwards status list already existed). The
test fixture gained a bastion sshd, a TCP echo service and
a no-auth SOCKS5 proxy, with AllowTcpForwarding stated
explicitly, and the five previously stubbed tier-2
integration tests are implemented against them: local
forward, remote forward, jump chain (including the
wrong-password reject), SOCKS (proven by dialling a name
only the proxy can reach), and partial failure. Evidence:
local harness + Compose UI type-check passing, fixture
scripts syntax-checked; the tests' first live run is the
CI instrumented job of the push carrying them — they are
not claimed green until that run says so.

## Addendum, 2026-11-04 — item F-03 (remote desktop)

(a) VNC proof: a JVM live test (RfbLiveTest) now exercises
the real RFB stack against a real server, gated on
TB_VNC_* environment variables that only a dedicated `vnc`
CI job sets (Xvfb + x11vnc, 800x600, on that job's own
runner). The emulator job is untouched — the desktop stack
that hung it twice stays out of it. The instrumented
RfbIntegrationTest remains the on-device twin.
(b) VNC polish: pinch zoom/pan with a shared geometry
transform, middle click and wheel buttons, sticky
modifiers, clipboard send, last-endpoint reconnect refill,
connect through a saved SSH host (the app's own local
forward; host keys decided by the stored trust record
only), and saved desktop profiles that never store the
VNC password.
(c) RDP: re-evaluated in docs/RDP_FEASIBILITY.md — feasible
via FreeRDP JNI; a costed minimal spike is defined. No RDP
code exists; the spike and any implementation await the
owner's written approval.

## Addendum, 2026-11-04 — item T-03 (session transcripts)

A host can opt in to a local session transcript (off by
default, per host). While a session runs, lines that scroll
into history are scrubbed with SecretRedactor and appended
to a private on-device file, capped at 256 KB with a single
rotated generation. The transcript never leaves the device
except inside an ENCRYPTED backup, and only when the host
also opted into that; the backup codec rejects a transcript
found in a plain file. Restoring a backup re-enables the
host's opt-ins. Capture is line-count based: the final
screenful and lines trimmed by the parser's own scrollback
cap are not recorded.

## Addendum, 2026-11-04 — item T-11 (autocomplete, history import)

Command autocomplete is opt-in and off by default; while
off, typed commands are not remembered at all. Remembered
commands live in an on-device store (capped, never backed
up) and every candidate line — typed or imported — must
pass an admission gate: plausible length, no control
characters, and nothing the SecretScanner flags. Suggestions
are whole-line prefix matches ranked by use and recency;
the typed-line tracker resets whenever the shell-side line
becomes unknowable, so the app never suggests from a stale
prefix. Shell history import is a separate, explicitly
consented per-session action: it reads the bash/zsh/fish
history files over a dedicated exec channel on the live
connection — never typed into the interactive shell — and
the same admission gate filters the result.

## Addendum, 2026-11-04 — item T-13 (IME composition)

The terminal capture field now honours IME composition:
while a keyboard is composing (CJK candidate selection,
RTL shaping, gesture typing, voice dictation) the draft is
held in the field and nothing is sent; when the composition
commits, the text is forwarded as typing. Clipboard pastes
never pass through a composition, so they still stop at
the paste-confirmation dialog. Supporting this required the
field to become a text (not password) field — voice and
composition are unavailable in password fields; the field
itself still retains nothing once a commit is forwarded.

## Addendum, 2026-11-04 — item T-16 (detect OS)

The session screen can detect the remote operating system.
The probe runs over a separate exec channel on the live
connection — it is never typed into the interactive shell —
and reads `uname` plus `/etc/os-release` on POSIX servers
(with a Windows `ver` fallback). The result is display
only. Automatic probing at connect time is an opt-in
setting and is off by default; the manual action is always
available in a live session.

## Addendum, 2026-11-04 — G7 hardening & release wave

- **R-01 (was F-13):** the host-key dialog race is PROVEN
  and fixed — sshj's kex wait uses the 30 s transport
  timeout while the verifier blocks on the user's decision;
  the handshake phase now runs with a 300 s timeout that is
  restored to the session value right after auth.
- **R-02:** stored secret blobs are v2: a version byte plus
  the owner-row context as GCM AAD (host credential,
  keychain entry, env value). Legacy blobs decrypt once and
  are re-stored bound on first load.
- **R-05:** the ci-logs triage push steps are removed from
  CI; failure triage uses job logs and artifacts only.
- **R-06:** release signing is wired (env/secret-driven,
  R8 on with documented keeps, separate signed-release
  workflow) — see docs/RELEASE_SIGNING.md.
- **R-07:** full OSV scan in docs/DEPENDENCY_SECURITY.md;
  all direct dependencies clean; the Bouncy Castle
  transitive advisories are documented with exposure and
  the owner's upgrade options.
- **R-10 + R-03:** the fixture grew an RSA client key and a
  bastion with its own host keys; all six tier-2 tests are
  implemented (21 SshIntegrationTest cases, no @Ignore).
- **Q-04:** UI audit in docs/UI_AUDIT.md — all text
  contrast ≥ 4.5:1 measured from the tokens; the dark
  outline token was raised to clear 3:1.
- **Q-05:** Persian localization is complete: values-fa
  carries all 632 strings with machine-checked
  format-placeholder parity.
- **Q-06:** first-run onboarding (3 pages, skippable,
  shown once) — see docs/DEVICE_TEST_PLAN.md section 1.
- **F-04/R-08:** docs/DEVICE_TEST_PLAN.md is the manual
  device verification script for two-device release runs.

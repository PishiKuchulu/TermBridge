# TermBridge — Independent Security Audit (Prompt 16)

Date: 2026-10-24. Auditor role: independent of feature implementation
(prompts 01–15). Method: line-by-line source inspection of the
security-critical paths, plus targeted greps over the whole tree.
This is a STATIC audit only.

## 0. Status of this audit, stated first

- NO build, NO test run, NO device run, NO penetration test, NO
  dynamic analysis was performed. The sandbox limits that blocked
  builds in prompts 02–15 are unchanged (Gradle daemon protocol
  failure + Java HTTPS proxy failure), and no repository or CI run
  exists. Every finding below is therefore marked CONFIRMED IN CODE
  (the code was read) or VERIFICATION GAP (the claim cannot be
  settled from source alone). Nothing here is presented as
  dynamically proven.
- NO production code was changed in this prompt. This audit is
  deliberately independent of any fix pass: findings carry a
  recommended fix and a release blocker decision, but the fixes
  themselves are NOT applied here. (Note on scope: the pack's
  prompt-16 text asks for fixing verified critical/high findings
  within the prompt; the tasking for this phase overrode that to
  audit-only, so that the audit record is not written by the same
  hand that changes the code it judges. The fix pass is a later,
  separate step, and must re-verify every finding it touches.)
- NO critical finding was confirmed in code. ONE high finding was
  confirmed (H1). Four medium findings and eight low findings
  follow. No unresolved critical issue is hidden; the high finding
  has an explicit blocker decision in §5.

## 1. Method and scope

Inspected directly (full read):

- `security/AndroidKeystoreSecretCrypto.kt`,
  `security/ExportCrypto.kt`, `security/SecretCrypto.kt`
- `transport/SshTransport.kt`,
  `transport/HostKeyVerificationPolicy.kt`,
  `transport/HostKeyFingerprint.kt`, `transport/SshError.kt`,
  `transport/SshConnection.kt`, `transport/SftpManager.kt`,
  `transport/RemotePath.kt`, `transport/TunnelManager.kt`,
  `transport/SocksSocketFactory.kt`, `transport/RfbConnection.kt`
- `domain/CommandRunner.kt`, `domain/CommandTemplate.kt`,
  `domain/CommandValidator.kt`, `domain/RiskyCommand.kt`,
  `domain/SecretScanner.kt`, `domain/SecretRedactor.kt`,
  `domain/CdCommand.kt`, `domain/EnvironmentValidator.kt`,
  `domain/HostValidator.kt`, `domain/TunnelConfig.kt`,
  `domain/JumpChain.kt`, `domain/ProxyConfig.kt`,
  `domain/RfbAuth.kt`, `domain/RfbDecoder.kt`,
  `domain/RfbFramebuffer.kt`, `domain/RfbMessages.kt`,
  `domain/RfbProtocol.kt`
- `data/HostRepository.kt`, `data/BackupService.kt`,
  `data/BackupCodec.kt` (validation/error paths),
  `data/HostEnvironmentRepository.kt` (encryption/validation
  paths), `data/AdvancedRepository.kt` (validation calls),
  `data/CommandRepository.kt` (save path)
- `ui/viewmodel/SessionViewModel.kt` (connect/wipe paths),
  `ui/viewmodel/FilesViewModel.kt` (credential wipe),
  `ui/screens/SessionScreen.kt` (paste paths),
  `ui/screens/BackupScreen.kt` (preview rendering),
  `ui/screens/DiagnosticsScreen.kt`
- `AndroidManifest.xml`, `res/xml/network_security_config.xml`,
  `gradle/libs.versions.toml`, `app/build.gradle.kts`
  (build types only)

Grep-verified over the whole tree:

- Zero `android.util.Log` / `println` calls in main sources.
- Zero WebView uses, zero intent filters besides MAIN/LAUNCHER,
  one exported component (MainActivity, launcher only).
- Zero PFP content, zero AI wording, zero cloud/sign-in claims.
- String-reference check: 316 defined / 315 used / 0 missing
  (only `app_name` unused in Kotlin, used by the manifest);
  strings.xml and manifest parse as XML.

Scope covered (pack §16 list, each mapped): credential
storage/encryption/Keystore lifecycle, backups, import/export,
host-key verification and changed-key handling,
password/private-key/passphrase handling and memory/log
exposure, network security, command-button injection and
variable interpolation, accidental execution, dangerous
actions, local/remote path traversal, unsafe file operations,
permissions, clipboard, screenshots/recents, crash logs,
diagnostics, sensitive UI, exported components, intent/deep
link handling, WebView, cleartext, dependency posture,
licences, native libraries/ABIs, RDP/VNC downgrade and auth
weaknesses, secrets in source/test fixtures/build outputs.

Out of scope / not auditable here (see §6): no git history
exists (no repository has been created), so no history audit
was possible; no dependency-vulnerability scan was run (see
M4); no dynamic behaviour (handshakes, forwarding, decoding
against real servers) was observed.

## 2. Findings summary

| ID | Severity | Title |
|----|----------|-------|
| H1 | HIGH | Command-button quoting guarantee fails on non-POSIX shells |
| M1 | MEDIUM | No FLAG_SECURE: terminal content visible in Recents/screenshots |
| M2 | MEDIUM | Trust records survive host deletion |
| M3 | MEDIUM | Import replace is not atomic across repositories |
| M4 | MEDIUM | sshj security posture unverified (no scan, no explicit strict-kex) |
| L1 | LOW | Keyboard single-line paste bypasses the paste dialog |
| L2 | LOW | TunnelManager keeps a dead tunnel listed as active |
| L3 | LOW | SftpManager.rename does not normalise oldPath |
| L4 | LOW | HostRepository.deleteHost is not transactional |
| L5 | LOW | Backup passphrase guidance omits offline brute-force risk |
| L6 | LOW | SOCKS proxy metadata exposure is unwarned |
| L7 | LOW | SshTransportException retains the sshj cause chain |
| L8 | LOW | ImportPreview holds decrypted credentials as Strings in memory |

Counts: critical 0, high 1, medium 4, low 8. Total 13.

## 3. Findings in detail

### H1 — HIGH — Command-button quoting guarantee fails on non-POSIX shells

Evidence (confirmed in code):

- `domain/CommandTemplate.kt`: `shellQuote()` is documented as
  "Single-quote escape for a POSIX shell word", and the file's
  KDoc states "the rendered text is submitted to a POSIX-style
  interactive shell". The escape itself (`'` -> `'\''`) is
  correct for POSIX only.
- `domain/CommandTemplate.kt` `prepare()` / `render()` and
  `ui/viewmodel/SessionViewModel.kt` button execution
  (`actOnPrepared` -> `CommandRunner`) contain NO check of the
  host's `remoteShell`. Grep confirms `remoteShell` is used in
  exactly three places: the environment screen, the `cd`
  rendering (`CdCommand`), and the environment ViewModel —
  never in the button path. Prompt 09 lets a host declare
  `RemoteShell.POSIX / POWERSHELL / CMD`, so buttons are
  executed against cmd.exe and PowerShell hosts with POSIX
  quoting.
- On cmd.exe, single quotes have NO quoting meaning at all:
  a parameter value such as `a & del /q file` inside
  `'...'` is still split at `&` by cmd, and the second part
  executes. On PowerShell, the POSIX `'\''` escape is not
  PowerShell quoting (PowerShell doubles a quote: `''`), so
  a value containing a single quote can break out of, or
  corrupt, the quoted word.
- The same shell blindness affects the second confirmation:
  `domain/RiskyCommand.kt` patterns are POSIX commands only
  (`rm`, `sudo`, `mkfs`, `shutdown`, ...). Windows-dangerous
  commands (`del /f /s`, `format`, `Remove-Item -Recurse`,
  `Stop-Computer`) are not flagged on any shell, and on a
  cmd/PowerShell host the POSIX patterns are largely inert.

Impact: the preview the user is shown — the prompt-08
guarantee that "preview == exactly what is inserted/sent" —
looks safe on a cmd host while the quoting is void. A
parameter value influenced by outside data (a filename, text
copied from a page, a value suggested by a colleague) can
inject a second command. Exploitability needs user
interaction (the user supplies/confirms the value), so this
is not remote code execution by itself — but it defeats a
documented security guarantee, which is why it is HIGH
rather than medium.

Recommended fix (not applied here): gate button execution by
shell — either block EXECUTE_ONE/SEQUENCE on CMD hosts with
a clear warning, or implement per-shell quoting (cmd double
quotes with `"` excluded by `WindowsPath` validation, plus
`^`/`%`/`&` handling; PowerShell `''` doubling) and add
Windows dangerous-command patterns to the heuristic (or a
shell-aware warning when the heuristic does not cover the
host's shell). Regression tests: parameter values containing
`&`, `|`, `;`, `%VAR%`, and `'` rendered per shell; risky
classification of `del`, `format`, `Remove-Item -Recurse`.

Release decision: BLOCKER. H1 must be fixed (or button
execution on non-POSIX hosts disabled with an honest UI
note) before any release that advertises command buttons
for Windows-shell hosts. POSIX-only hosts are unaffected.

### M1 — MEDIUM — No FLAG_SECURE: terminal content in Recents/screenshots

Evidence: grep over main sources and the manifest finds no
`FLAG_SECURE` anywhere. MainActivity is a normal activity,
so the live terminal — hostnames, usernames, command output,
potentially secrets printed by the server — appears in the
Recents overview and in user screenshots, and is capturable
by screen-recording apps the user has granted.

Impact: information disclosure of session content to anyone
with the unlocked device in hand, and to any app with
media-projection permission. This is the pack §16
"screenshots/recents" item, and it is currently unmitigated.

Recommended fix: set `FLAG_SECURE` on MainActivity (whole
app, simplest and consistent) or at minimum on the session
screen's window. Note the tradeoff honestly in the UI: it
also blocks the user's own screenshots of their terminal.

Release decision: fix before release; acceptable as a
documented known issue for a pre-release build only.

### M2 — MEDIUM — Trust records survive host deletion

Evidence (confirmed in code): `data/HostRepository.kt`
`deleteHost()` deletes buttons, env vars, env settings, and
advanced config, then the host row — but never touches
`trustedHostKeyDao`. The class KDoc states this is
intentional ("Trust records are endpoint-scoped and are NOT
deleted with a profile"). `trustHostKey`/`getTrustedFingerprint`
are endpoint-keyed, so deleting a host and re-adding the
same hostname:port:username silently MATCHes the old
fingerprint with no re-verification dialog.

Impact: the exact scenario where a user deletes a host
because they suspect compromise or a server rebuild is the
scenario where the old trust record is most dangerous: the
re-added host is trusted without the user ever seeing a
fingerprint again. An attacker who has replaced the server
key keeps the benefit of the old trust record across a
delete-and-re-add cycle.

Recommended fix: delete the endpoint's trust record with
the host (or offer it explicitly in the delete confirmation
dialog, defaulting to delete). If endpoint-scoped trust is
kept deliberately, the delete dialog must say so in plain
words. Regression test: delete host -> trust record gone.

Release decision: fix before release (either behaviour
change or explicit dialog wording); do not ship silently.

### M3 — MEDIUM — Import replace is not atomic across repositories

Evidence (confirmed in code): `data/BackupService.kt`
`applyImport()`, replace path: `saveHost()` first (profile
+ credential replaced), then `deleteForHost()` on the three
child repositories, then `applyChildren()` item-by-item,
each failure becoming a warning. There is no transaction
spanning the host row and its children (different
repositories, different DAOs), and no staged copy: a crash
or failure between the child delete and the child writes
leaves the host with its new profile/credential but
partial or no buttons, env vars, tunnels, or proxy — and
the old configuration is already deleted, so it cannot be
recovered from the app.

Impact: data loss (configuration, not secrets) during an
explicit user action, with no recovery path except
re-importing. The per-item warnings are honest, but they
only cover failures the code catches, not a crash
mid-replace.

Recommended fix: stage the imported children and apply
them before deleting the old ones where the repository
allows it, or snapshot the old configuration and restore
it on failure; at minimum, warn in the replace
confirmation that a failed replace can leave partial
configuration. Regression test: injected child-save failure
during replace -> old children intact or fully replaced,
never half.

Release decision: fix or explicit warning before release;
not a pre-release blocker if the warning is added.

### M4 — MEDIUM — sshj security posture unverified (verification gap)

Evidence (gap, not a confirmed vulnerability): no
dependency-vulnerability scan output exists for this tree
(no OWASP Dependency-Check, no Dependabot, no Gradle
dependency report was runnable in the sandbox — Java HTTPS
is broken here, and no CI run exists). `transport/
SshTransport.kt` sets timeouts and the host-key verifier
but contains NO explicit strict-key-exchange (Terrapin,
CVE-2023-48795) configuration, and no comment states
whether sshj 0.40.0 enables strict key exchange by
default. The prompt-01 record claims sshj 0.40.0 has a
"Terrapin mitigation"; this audit could NOT reproduce that
claim from the code in the tree (no configuration, no scan,
no release-note citation in the docs).

Impact: unknown. If sshj 0.40.0 negotiates strict key
exchange by default, there is no issue; if it does not, a
network attacker in a man-in-the-middle position could
attempt prefix truncation against connections to servers
that also lack the mitigation. This is classified MEDIUM
as a verification gap with a plausible protocol-level
impact, NOT as a confirmed vulnerability — inventing one
would violate this audit's own evidence rule.

Recommended fix: before release, (a) run a dependency
scan in CI and record the result, (b) verify strict-kex
behaviour from sshj 0.40.0's release notes/source and,
if it is not default, configure it explicitly, (c) record
the outcome in `docs/SSH_INTEGRATION_BLOCKER.md` or the
transport KDoc. Regression test: none available on JVM
without a peer; the fixture run in prompt 15's plan is the
proof point.

Release decision: verification required before release;
a release note stating "dependency scan not run" is not
acceptable for a security-facing app.

### L1 — LOW — Keyboard single-line paste bypasses the paste dialog

Evidence: `ui/screens/SessionScreen.kt` keyboard-capture
`onValueChange`: pasted text containing `\n` or `\r` goes
to the paste dialog; pasted text WITHOUT a newline is sent
to the shell immediately (`viewModel.sendText(added)`).
The paste BUTTON always routes through the dialog. The
prompt-06 claim "paste confirmation dialog for both paste
paths" is therefore not literally true for single-line
keyboard pastes. Control characters other than newline in
pasted text are not filtered on either path.

Impact: low. Sending text without Enter does not execute
a command at a shell prompt, so this is not pastejacking
by itself; but the two paths behave differently, and a
single-line paste containing escape/control sequences is
delivered to the shell unreviewed. The dialog's purpose —
"look before you send" — is partially bypassed.

Recommended fix: route ALL keyboard pastes through the
dialog, or filter control characters from keyboard-pasted
text. Regression test: single-line keyboard paste ->
dialog shown, nothing sent before confirm.

### L2 — LOW — TunnelManager keeps a dead tunnel listed as active

Evidence: `transport/TunnelManager.kt` `startLocal()`: the
per-tunnel job runs `forwarder.listen()` and swallows ALL
exceptions with a comment that close() ends the loop. If
the loop ends for any other reason (connection death, SSH
channel failure), the exception is swallowed and the
tunnel stays in `activeMap`, so the UI keeps showing it
as active while nothing is forwarded.

Impact: low. Misleading status only; no secret or network
exposure beyond the tunnel's own (already authorised)
forwarding, and disconnect/stopAll still cleans up.

Recommended fix: on listen-loop exit for a reason other
than close, remove the tunnel from `activeMap` (and
surface a "tunnel stopped" state). Regression test: fake
forwarder whose listen() throws -> tunnel removed.

### L3 — LOW — SftpManager.rename does not normalise oldPath

Evidence: `transport/SftpManager.kt`: `list`, `mkdir`
(via `RemotePath.join`), `delete`, `statSize`, `download`,
and `upload` all pass paths through `RemotePath.normalize`
or `join`; `rename()` normalises only the NEW path
(`RemotePath.join(parent, newName)`) and passes `oldPath`
raw to `sshj.rename`. `RemotePath.parent()` also falls
back to "/" on an invalid path instead of failing, so a
malformed oldPath yields a rename from the raw path to
"/<newName>".

Impact: low. In practice oldPath always comes from a
server listing (already normalised), so this is a
defence-in-depth gap, not an exploitable traversal today.

Recommended fix: normalise oldPath in `rename()` and fail
on null, like every other method. Regression test:
rename with a `..`-containing oldPath -> rejected.

### L4 — LOW — HostRepository.deleteHost is not transactional

Evidence: `data/HostRepository.kt` `deleteHost()` performs
five sequential DAO/repository deletes (buttons, env vars,
env settings, advanced config, host row) with no
`@Transaction` spanning them. A crash mid-delete leaves a
partially deleted host (e.g. host row gone but tunnels
still present, or vice versa).

Impact: low. Orphaned non-secret rows for a deleted host;
no secret exposure (credential blob is deleted with the
row or by the same call). The prompt-15 transaction fix
covered button children only; this cross-repository gap
remains.

Recommended fix: wrap the delete sequence in a single
Room transaction where the DAOs allow it, or delete in
an order where leftovers are harmless and reaped on next
delete. Regression test: injected failure mid-delete ->
no orphan rows.

### L5 — LOW — Backup passphrase guidance omits offline brute-force risk

Evidence: `strings.xml` `backup_passphrase_note` says the
passphrase is never stored, loss means no recovery, and
the minimum is 8 characters. It does not say that an
encrypted backup file is offline-attackable: anyone with
the file can brute-force a weak passphrase without the
app's rate limiting. PBKDF2 at 600,000 iterations
(`security/ExportCrypto.kt`, OWASP-recommended) slows
but does not prevent this for short or common
passphrases.

Impact: low. The construction is correct; only the user
guidance is incomplete. A user choosing "password123"
believes 8 characters is "enough" because the UI says so.

Recommended fix: add one sentence to the passphrase note
("anyone with this file can try passphrases offline —
use a long, unique passphrase") and consider a strength
hint. No construction change needed.

### L6 — LOW — SOCKS proxy metadata exposure is unwarned

Evidence: `transport/SocksSocketFactory.kt` routes the
initial TCP connection through an unauthenticated SOCKS
proxy (SSH content stays encrypted — verified: the proxy
sees a TCP stream, not plaintext). No UI string warns
that the proxy operator learns the target hostname and
port and the timing/volume of the connection. The proxy
strings (`proxy_socks`, `proxy_spec_hint`, etc.) contain
no such note.

Impact: low. Metadata disclosure to a proxy the user
chose; the risk is only material with an untrusted proxy.

Recommended fix: one sentence in the proxy section of
the Advanced screen. No code change to the proxy path.

### L7 — LOW — SshTransportException retains the sshj cause chain

Evidence: `transport/SshTransport.kt`: `SshTransportException`
carries the original `Throwable` as `cause`. Today this
is safe: the ViewModels display only `error.userMessage`
(fixed strings, `transport/SshError.kt`), and there are
zero logging calls in main sources, so the cause never
leaves the process. But the cause's message comes from
sshj and could in principle echo user input (hostnames
are fine; a crafted server message is less fine), and any
future crash reporter would ship the whole chain.

Impact: low today, latent tomorrow.

Recommended fix: strip the cause before the exception
crosses the ViewModel boundary, or drop the cause field
and keep only the mapped error. Regression test: a
transport failure's surfaced exception has no cause
message containing input.

### L8 — LOW — ImportPreview holds decrypted credentials as Strings in memory

Evidence: `data/BackupService.kt` `parseForPreview()` /
`applyImport()`: the parsed document — including
`ExportCredential.value` as a `String` — lives in
`ImportPreview`, which the Backup ViewModel holds across
the preview and apply screens. The preview UI itself was
verified to show only names/counts and the
`hasCredentials` flag (BackupScreen line ~217), never a
value. But the plaintext credential Strings cannot be
wiped (same immutable-String residual as prompt 04's
Compose fields) and linger until GC, longer than the
decrypt-and-re-encrypt window the export path uses.

Impact: low. Same threat class as the documented prompt-04
residual; the window is the import flow only, and no
logging or display path touches the values.

Recommended fix: hold credentials as CharArray/ByteArray
in the preview document and wipe after apply, or apply
the import immediately from the parsed bytes without
retaining the preview document. Regression test: after
apply, no credential bytes remain in the ViewModel state.

## 4. Standing security claims: confirmed, qualified, refuted

Each claim from prompts 01–15 was re-checked against code
read in this audit (not against prior reports):

| Claim | Verdict | Evidence |
|---|---|---|
| Insert-only never submits | CONFIRMED | `CommandRunner.insert()` contains no `sendEnter` call; re-read in this audit. |
| No accept-all host-key path | CONFIRMED | `HostKeyVerificationPolicy` requires a decision for UNKNOWN and CHANGED; no accept-all in main sources; no accept-all test double found by grep. |
| Trust persisted only after successful auth | CONFIRMED | `SshTransport.connect` and `connectChain` persist `pendingTrust` only after authentication (chain: after the target authenticates). |
| Default export contains no plaintext secret | CONFIRMED | `BackupService.buildExportHost` non-secret branch never loads a credential or env value (`null` / empty list); codec validates credential types and rejects malformed files (see `BackupCodec` credential checks). |
| No logging of secrets | CONFIRMED | Zero `Log.`/`println` calls in main sources; all error mappers use fixed strings (`SshError`, `SftpError`, `TunnelException`, `ExportCryptoException`). |
| Env values encrypted at rest | CONFIRMED | `HostEnvironmentRepository` encrypts via `SecretCrypto` (Keystore AES-256-GCM); write-only, never displayed/logged. |
| Buttons never modified by env/cd | CONFIRMED | Grep: no env/cd/`remoteShell` reference in command-button domain, repository, or management ViewModel code; `CdCommand` KDoc states the exception is terminal-only. |
| Paste never auto-executes | QUALIFIED | Paste dialog sends text only (no Enter) on confirm; but L1: single-line keyboard paste bypasses the dialog (still no Enter, so no execution — the confirmation claim, not the execution claim, is what fails). |
| Parameter quoting always safe | REFUTED for non-POSIX shells | See H1. Holds for POSIX hosts only. |
| Risky heuristic catches dangerous commands | QUALIFIED | POSIX patterns only; Windows-dangerous commands unflagged; the heuristic itself is honestly documented as non-guarantee, so this is a coverage gap, not a false claim — except where it is presented as the safety net for Windows-shell hosts (see H1). |
| allowBackup=false, no cleartext, minimal permissions | CONFIRMED | Manifest: INTERNET + ACCESS_NETWORK_STATE only; `allowBackup=false`; `usesCleartextTraffic=false`; network security config: system anchors, cleartext forbidden. |
| No WebView, no deep links, one exported activity | CONFIRMED | Manifest: single exported MainActivity with MAIN/LAUNCHER only; no WebView import in main sources. |
| SFTP traversal blocked | CONFIRMED with L3 | `RemotePath` normalises, clamps `..` at root, rejects NUL and `/` in names; `rename` oldPath gap is L3. |
| Import never trusts a host key, never executes a command | CONFIRMED | `BackupService` contains no trust code path; buttons are stored as data via `saveButton` (validated + secret-scanned). |
| RFB: no silent downgrade | CONFIRMED | `RfbConnection.chooseSecurity`: VNC Auth only with a password, None only on explicit opt-in; VeNCrypt/TLS/Tight refused; unsupported encodings not advertised. |
| Tunnel loopback default | CONFIRMED | `TunnelValidator`: non-loopback bind rejected unless `allowNonLoopback` is set; loopback set is conservative (127.0.0.2 is NOT treated as loopback). |
| VNC Auth weakness disclosed | CONFIRMED | `RfbAuth` KDoc, VNC_NOTES, and UI text all state DES + 8-char weakness and the trusted-network/tunnel condition. |
| Secrets in source/test fixtures | CONFIRMED clean | Test PEM/token literals are placeholders (`AAAA`, `token=<redacted>` pattern checks); no real credential material found by grep. No git history exists to audit (no repository created). |

## 5. Release blockers and risk decisions

- H1: BLOCKER — fix or disable button execution on
  non-POSIX hosts before release (see H1).
- M1: fix before release (FLAG_SECURE); pre-release only
  with a documented known issue.
- M2: fix before release (delete trust with host, or
  explicit dialog wording).
- M3: fix or explicit replace warning before release.
- M4: verification required before release (dependency
  scan + strict-kex confirmation); "not run" is not an
  acceptable release note.
- L1–L8: fix or document before release; none blocks a
  pre-release build on its own, but L1 and L5 are cheap
  and should ride with the H1 fix.
- Accepted risks (not findings, documented tradeoffs):
  Keystore key without user-auth binding (prompt 04:
  device lock screen is the boundary; a fully compromised
  device is out of scope); immutable-String residual
  (Compose fields, PEM Strings, transport `String`
  conversions — wiped buffers cover the copies the app
  owns); VNC Auth weakness (session blocked, prompt 13);
  release signing and minification (prompt 18 owns them);
  no key rotation yet (prompt 04 decision).

## 6. Untested areas (explicit, not hidden)

- Dynamic behaviour of every kind: real SSH handshakes,
  host-key change flows against a live server, SFTP
  transfers, tunnel forwarding, jump chains, RFB
  handshakes/decoding against a real VNC server, backup
  round-trips on device, Keystore behaviour across OEMs
  and Android versions (including DES availability on
  API 26+ for VNC Auth and PBKDF2 cost on low-end
  phones). All are BLOCKED on the fixture/device/CI
  items carried since prompt 05.
- Dependency vulnerabilities: no scan run (M4). No
  licence audit beyond the pinned, well-known
  dependencies (sshj Apache-2.0, kotlinx-serialization
  Apache-2.0, Room/Compose Apache-2.0); no native
  libraries are bundled, so there is no ABI/native
  audit surface in this tree.
- Static-analysis tooling: no lint, detekt, or
  dependency-check run was possible in the sandbox
  (same Gradle/Java-HTTPS limits); this audit is manual
  source inspection, and tool output must be added by
  the first CI run.
- Git history: no repository exists, so there is no
  history to audit for leaked secrets. The first push
  must be reviewed for accidental inclusion (the
  `.gitignore` already blocks keystores, p12/pem/key
  files, local.properties, and build output — verify
  that file is honoured at push time).
- Penetration testing: NOT performed, NOT claimed.

## 7. Residual risk statement

After this audit, the app's strongest properties are the
ones enforced in repositories and pure domain code rather
than in UI: host-key policy with post-auth trust
persistence, Keystore encryption with fresh IVs, fixed
error messages, fail-closed backup parsing, and the
POSIX button guarantees (mode separation, quoting,
secret-scan blocking). The weakest points are the ones
where a guarantee assumed a context that a later prompt
widened: buttons assumed a POSIX shell, then prompt 09
added cmd/PowerShell hosts without gating them (H1); and
the areas where the app depends on something it cannot
observe from here — the sshj client's exact handshake
behaviour (M4) and all real-server behaviour. Those, plus
the absence of any executed test or build, are the
reasons this audit does not recommend a release on the
strength of code inspection alone: the code reads well
in the audited paths, but the release gate is still the
first CI build, the fixture runs, and the H1/M1/M2/M3/M4
fixes.

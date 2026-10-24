# Security fixes for the prompt-16 audit — disposition

The audit itself is `docs/SECURITY_AUDIT.md` and is NOT
edited here; it stays the independent record. This document
records what was changed in response, per finding, with the
files and tests for each. Where the audit's severity or
premise was qualified, that is stated, not smoothed over.

Status of everything below: code written, tests written,
NOT RUN. Nothing in this pass has been built, unit-tested,
or device-run (sandbox Gradle daemon + Java HTTPS limits,
same as every prompt since 02; first real proof is CI,
which does not exist yet). Static verification only:
string references, XML parses, balance, and forbidden-
wording/secret/logging greps (see §13).

## 1. H1 — HIGH, release blocker: button quoting on non-POSIX shells — FIXED (option b, with a cmd.exe gate)

Choice and justification: per-shell quoting + shell-aware
risky patterns (option b), NOT hand-rolled cmd.exe
quoting, because cmd.exe quoting cannot be proven safe:
double quotes do not stop `%VAR%` expansion, and a value
containing a quote breaks out of any quoted region. So
cmd.exe gets a GATE, not a quoting rule: a button WITH
parameters cannot be prepared for a cmd.exe host — the
parameter dialog shows the reason and the action stays
disabled. A parameter-free button needs no quoting
(rendered text is exactly the author text) and still
works on cmd.exe. Gating only the case that cannot be
made safe, while keeping POSIX and PowerShell quoting
exact, is strictly safer than pretending cmd.exe quoting
exists.

Changes:
- `domain/CommandTemplate.kt`: `shellQuote(value, shell)`
  (POSIX `'\''` escaping unchanged; PowerShell `''`
  doubling; CMD throws IllegalArgumentException with a
  fixed message). `render(..., shell)` and
  `prepare(..., shell)` thread the shell through. The
  single-arg `shellQuote(value)` still defaults to POSIX
  so existing callers/tests keep working. The class KDoc
  no longer claims a POSIX shell unconditionally.
- `domain/RiskyCommand.kt`: patterns split into common /
  POSIX-only / Windows-only sets. `classify(command,
  shell)` and `classifySteps(steps, shell)` apply POSIX →
  common + POSIX, POWERSHELL → all three (PowerShell
  aliases `rm` to Remove-Item, so POSIX patterns still
  bite), CMD → common + Windows. Windows set (9
  patterns): del/erase with /f /s /q or wildcard, rd/
  rmdir with /s /q, Remove-Item -Recurse, format/
  diskpart, Stop-/Restart-Computer, reg delete, vssadmin
  delete shadows, netsh advfirewall state off, cipher /w.
  `systemctl poweroff/reboot` moved to the POSIX set
  (label now "shutdown, reboot, or poweroff (systemd)").
- `domain/CdCommand.kt`: POSIX/PowerShell startup `cd`
  now uses `shellQuote(directory, shell)` instead of
  POSIX quoting for both (the old code POSIX-quoted a
  PowerShell path, wrong for a path containing a quote).
  CMD unchanged (`cd /d` + double quotes; Windows-path
  validation already excludes `"`).
- Shell threading: `ui/viewmodel/SessionViewModel.kt`
  gained `connectedShell: StateFlow<RemoteShell>`
  (default POSIX, set from the host's settings at connect
  time, cleared to POSIX on disconnect);
  `ui/components/CommandDialogs.kt` `ParameterDialog`
  gained a `shell` param passed to both `prepare()`
  calls; `ui/screens/SessionScreen.kt` collects
  `connectedShell` and passes it in. The dialog already
  catches IllegalArgumentException from `prepare()` and
  shows the message with the action disabled, so the
  cmd.exe gate surfaces exactly there with no new UI.
- Docs: `docs/COMMAND_BUTTONS.md` "Parameters and shell
  quoting" rewritten with the exact per-shell scope.

Tests (new file `ShellQuotingTest.kt`, 11 cases):
POSIX quoting unchanged; PowerShell `''` doubling;
PowerShell metachar value becomes one quoted word; CMD
parameterised prepare throws; CMD parameter-free prepare
works; CMD sequence with a variable step throws; Windows
commands flagged on CMD/PowerShell (del /f /s,
Remove-Item -Recurse, format, Stop-Computer, reg
delete, vssadmin delete shadows); `rm -rf /` flagged on
POWERSHELL (alias) but NOT on CMD (inert there);
`del /f` NOT flagged on POSIX; POSIX risky behaviour
unchanged; CdCommand PowerShell quote-doubling.

Residual: the risky heuristic remains a heuristic on
every shell (aliases, encoding, indirection can still
hide dangerous commands); a non-flagged command is never
claimed safe. The audit's H1 premise was confirmed
(`remoteShell` was never consulted in the button path).

## 2. M1 — no FLAG_SECURE — FIXED

- `MainActivity.kt`: `window.addFlags(WindowManager.
  LayoutParams.FLAG_SECURE)` in `onCreate`. Scope stated
  honestly in the code comment: this is the app's only
  activity, so the flag covers the whole app, not just
  the terminal — the user cannot screenshot host lists
  or settings either. That is the architecture's
  constraint, not a claim of session-only scoping.
- New string `settings_secure_note` in `strings.xml`,
  shown as a footer note in `SettingsScreen.kt`, so the
  user is told screenshots/recordings are blocked rather
  than discovering it by surprise.

No test: FLAG_SECURE is a window flag, not JVM-testable
behaviour; device verification only.

## 3. M2 — trust records survive host deletion — FIXED (prompt-04 rule REVERSED)

- `data/HostRepository.kt`: `deleteHost(id)` loads the
  stored row, derives the endpoint exactly as
  `HostProfile.endpoint` does (`"$hostname:$port"`),
  deletes that endpoint's trust record via
  `trustedHostKeyDao.deleteByEndpoint(endpoint)`, then
  deletes buttons/env/advanced config + host row. If the
  row is already gone, the remaining deletes still run
  (idempotent cleanup). The class KDoc explicitly
  records the reversal and the reason: the old rule meant
  a delete-and-re-add cycle silently re-trusted a
  possibly compromised host without ever showing a
  fingerprint again — exactly the scenario where a user
  deletes a host because they suspect compromise. A
  re-added host now always re-verifies with a dialog.
- Docs: `docs/SECURITY_KEY_LIFECYCLE.md` trust section
  updated with the behaviour change.

Tests (in `HostRepositoryTest.kt`): seed host + trust
record via `FakeTrustedHostKeyDao`, deleteHost → trust
record gone; deleteHost runs inside the supplied
transaction runner (see §4, L4).

## 4. M3 — import replace not atomic — FIXED

- New `data/TransactionRunner.kt`: `fun interface
  TransactionRunner { suspend fun <T> run(block: suspend
  () -> T): T }` with a documented contract (if the
  block throws, none of its writes are visible; Room's
  `withTransaction` provides this; a direct test double
  does not, and tests must say so).
- `data/BackupService.kt`: new optional param
  `transactionRunner` (default runs the block directly;
  existing construction compiles unchanged). The replace
  path now runs profile save + old-child deletes +
  new-child apply inside ONE `transactionRunner.run`
  call, and child failures THROW (`{ throw
  BackupException(it) }`) instead of warning — so a
  crash or a rejected child rolls the host back to its
  old configuration; the host is recorded as replaced
  only when the transaction commits. Fresh imports keep
  the old warning-per-child behaviour (additive, not
  half-replaced). `applyChildren` was refactored to take
  `onFailure: (String) -> Unit` instead of a warnings
  list to make the two paths' failure semantics explicit.
- `TermBridgeApplication.kt`: production runner is
  `TransactionRunner { block -> database.withTransaction(block) }`,
  passed to HostRepository and BackupService. Every
  repository shares this one database, so
  `withTransaction` covers all their DAOs.

Tests (new file `BackupReplaceAtomicityTest.kt`, 2
cases): a `SnapshotRestoreRunner` test double snapshots
the seeded host's config through the repositories and
restores it on failure — the rollback semantics a real
transaction runner provides. (1) A child failure during
replace (via the prompt-15 `failNextReplaceChildren`
hook) aborts the replace: host NOT reported as
replaced, failure warned, old name/buttons/env
value/credential all intact, exactly one runner call.
(2) Successful replace runs inside exactly one runner
call, new name/button applied, existing credential kept
(non-secret replace). Caveat, stated as in prompt 15:
real Room atomicity is Room's `withTransaction`
guarantee, NOT JVM-executable here; the test proves the
service's contract with the runner, and the
fixture/device run must confirm the production runner.

## 5. M4 — sshj posture (Terrapin / strict key exchange) — VERIFIED, no code change for strict kex; dependency scan remains a release gate

Evidence from the real sshj 0.40.0 jar (downloaded from
Maven Central, group `com.hierynomus`, artifact `sshj`;
an initial attempt under `net.schmizz` 404'd — the
published group is `com.hierynomus`). Jar SHA-256:
`a6a5533f6580e0418dfacbb5396680186698585c325fa950c788e749f24d1e2`
(jar deleted from /tmp after inspection).

Bytecode evidence: `KeyExchanger` carries a `strictKex`
state machine ("Enabling strict key exchange extension",
KEXINIT-first enforcement, `isStrictKex()`,
`gotStrictKexInfo()`); `Proposal`'s client constructor
appends `kex-strict-c-v00@openssh.com` on the initial
exchange (confirmed in `KeyExchanger.sendKexInit()`,
where the third constructor argument is
`initialKex.get()`), with no app configuration. So
strict key exchange IS default-on in sshj 0.40.0: the
prompt-01 claim ("Terrapin mitigation claimed") is now
reproduced from the jar, and no API call is needed.

Caveat preserved: strict kex protects only when the
SERVER also supports it (protocol property, not app
property) — a legacy server that does not advertise
strict kex gets no Terrapin protection from either side.

The other half of M4 — a dependency-vulnerability scan
has still never run (no CI) — remains an explicit
RELEASE GATE, not a claim: add a dependency scan to CI
before release and re-check sshj against the CVE
database then. A KDoc note recording the strict-kex
evidence was added to `SshTransport`.

## 6. L1 — single-line paste bypasses the dialog — FIXED

- `ui/screens/SessionScreen.kt` keyboard-capture
  `onValueChange`: the old code sent single-line
  keyboard pastes straight to the shell (only
  multi-line pastes went to the dialog). New rule: any
  multi-character insert OR any insert containing `\n`
  or `\r` goes to the paste dialog; only single
  characters are sent directly. Documented tradeoff in
  the code comment: an IME autocomplete insertion of a
  whole word also triggers the dialog — the price of
  never sending pasted text unreviewed.

No JVM test (Compose `onValueChange` is UI behaviour);
device verification only.

## 7. L2 — tunnel listen-loop swallows exceptions — FIXED

- `transport/TunnelManager.kt`: `stopAll()` now removes
  the forwarder BEFORE cancelling the job, so a
  still-registered forwarder in the catch means an
  unexpected exit (e.g. connection death): the fix
  removes forwarder/job/active entry + refreshes. A
  stop() exit finds nothing registered and is ignored.
  Race analysis in the code comment: either order
  leaves `activeMap` cleaned. Previously a tunnel
  killed by connection death stayed listed as active.

No JVM test (needs a live sshj connection); fixture
verification only.

## 8. L3 — SFTP rename normalisation — FIXED

- `transport/SftpManager.kt`: the old `rename()`
  normalised only the NEW path and passed `oldPath`
  raw to sshj, while `RemotePath.parent()` falls back
  to "/" on an invalid path — so a malformed oldPath
  renamed from a raw path to "/<newName>". New
  `internal fun renamePaths(oldPath, newName)` in the
  companion object (callable without an SshjConnection
  instance) normalises the old path (null → fail) and
  joins the new name; `rename()` throws
  `SftpError.NotFound` when it returns null.

Tests (new file `SftpManagerRenameTest.kt`, 4 cases):
normal pair resolves; traversal in oldPath is clamped
before rename; invalid/NUL oldPath → null (never a
root fallback); invalid new name (`../evil`, `a/b`) →
null. Live rename against a server is a fixture item,
not claimed here.

## 9. L4 — deleteHost not transactional — FIXED (with M2)

`deleteHost` now runs inside the `TransactionRunner`
(see §3/§4): same runner, same database, one
transaction covering trust delete + child deletes +
host delete. Test: `deleteHostRunsInsideTransactionRunner`
in `HostRepositoryTest.kt` (recording runner, exactly
one call). Existing callers/tests keep compiling: the
runner param defaults to a direct pass-through.

## 10. L5 — backup passphrase brute-force note missing — FIXED (string only)

`backup_passphrase_note` extended with: "Anyone with
this file can try passphrases offline, without the
app's protection — use a long, unique passphrase."
No behaviour change; the note is the fix the audit
asked for.

## 11. L6 — SOCKS metadata unwarned — FIXED (string + UI)

New string `proxy_metadata_note` ("A SOCKS proxy
operator can see the target hostname and port and the
timing of the connection, though not the encrypted SSH
content. Only use a proxy you trust.") wired as a note
Text under the current-proxy line in
`AdvancedScreen.kt` `ProxyForm`.

## 12. L7 — exception cause chain retained — FIXED

- `SshTransportException` no longer takes a cause at
  all: `class SshTransportException(val error: SshError)
  : Exception(error.userMessage)`, with a KDoc note
  explaining the removal (rather than defaulting) makes
  re-attaching a cause a compile error. All three
  creation sites updated (including the jump-chain site
  that previously passed a fresh IllegalStateException
  as cause). `SshTransportTest` has no cause assertions
  (grep-verified).
- DELIBERATELY NOT TOUCHED: `SftpException` keeps its
  cause parameter. Same latent class, different surface
  — silently extending scope would hide a second change
  inside this fix pass. Recorded here as a known
  residual for a future pass, not fixed by omission.

## 13. L8 — ImportPreview holds String credentials — NO CODE CHANGE (already bounded, verified)

`BackupViewModel.applyPreview()` sets `preview = null`
after `applyImport` returns, and `ImportPreview` is held
only in that ViewModel state — so the plaintext-
credential window is preview → apply only, then
GC-able. That is the audit's own mitigation ("bounded
lifetime"), already present. Recorded as mitigated-in-
part / documented residual, NOT as fully fixed: the
credential strings still live in memory for the
preview's lifetime by design (the preview must carry
them to apply them), and a future pass could narrow
this further (e.g. re-parse at apply time) if the
lifetime proves to matter.

## 14. Static verification performed

- String references: new strings `proxy_metadata_note`,
  `settings_secure_note`; `backup_passphrase_note`
  edited, not added. The full 0-missing check is
  re-run at the end of this pass and its count is in
  the pass report (this doc is written before that
  check runs, so the count is not asserted here).
- `strings.xml` and `AndroidManifest.xml` parse as XML.
- Brace/paren/bracket balance on new/touched files.
- Greps: no PFP mentions in the product tree, no AI
  wording, no cloud/sign-in claims, no `Log.`/`println`
  in new code, no hardcoded secrets, no logging of
  sensitive values, no remaining `SshTransportException`
  cause arguments, no remaining POSIX-only
  `shellQuote(value)` call in the button path.
- Compile risks identifiable by reading (cannot run
  Gradle — never claimed): `RemoteShell` references in
  SessionViewModel/ParameterDialog, `TransactionRunner`
  SAM wiring in TermBridgeApplication, `database.
  withTransaction` import, `WindowManager.LayoutParams.
  FLAG_SECURE` import in MainActivity, companion-object
  `renamePaths` visibility to tests (same-module
  internal access), `BackupService` constructor
  positional-arg order with the new trailing param,
  `HostRepository` constructor positional-arg order with
  the new trailing param.

## 15. What was NOT done, and why

- `docs/SECURITY_AUDIT.md` untouched (audit record).
- `docs/PHASE_REPORT_16.md` untouched.
- No repo created, nothing pushed, no CI run claimed.
- No RDP/VNC session work: both stay blocked.
- No UI-language change: English v1 unchanged.
- No build, unit test, instrumented test, or device
  run claimed anywhere in this pass.

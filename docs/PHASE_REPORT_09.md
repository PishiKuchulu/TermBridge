# TermBridge — Phase Report, Prompt 09

Date: 2026-10-24. Phase: 09 — Environment Variables and Working Paths.
Process: autonomous continuation per the user's instruction
(no per-prompt widget).

## 1. Actual changes and touched files

No new dependency. Everything here is own code on top of Room
2.6.1 (prompt 04), sshj 0.40.0 (prompt 05), and the prompt-06
terminal.

Domain (pure Kotlin, no Android dependency, JVM-testable):
- `HostEnvironment.kt` — per-host settings model (non-secret
  fields only), `RemoteShell` (POSIX / POWERSHELL / CMD, chosen
  per host, never auto-detected), `EnvVarInfo` (name only),
  `EnvVarValue` (decrypted, caller-wiped, never reaches the UI),
  `EnvApplyResult` (applied/rejected NAMES only).
- `EnvironmentValidator.kt` — env-name regex + loader-injection
  blocklist (`LD_PRELOAD`, `LD_LIBRARY_PATH`, `LD_AUDIT`,
  `LD_DEBUG`, `DYLD_*`), value shape rules (NUL, 4096-char cap),
  remote-directory validation per shell dialect, local-URI
  validation (SAF `content://` only; filesystem paths rejected).
- `WindowsPath.kt` — lexical Windows path normalisation
  (drive `C:\...` or UNC `\\server\share\...`, separators
  canonicalised, `.`/`..` resolved, clamped at drive/share
  root, illegal Windows name characters rejected).
- `CdCommand.kt` — the single terminal-startup `cd` rendering
  (single-quoted for POSIX/PowerShell, `cd /d "..."` for cmd).
  It is the ONLY place a working directory becomes shell text,
  and it is never called from button code.

Data:
- `HostEnvironmentEntity.kt` — two tables: `host_settings`
  (hostId PK, remoteWorkingDirectory, localDirectoryUri,
  remoteShell — no secret column exists) and `host_env_vars`
  (hostId FK cascade, name, valueBlob, sortOrder; UNIQUE index
  on (hostId, name) enforces one value per name per host).
- `HostEnvironmentDao.kt` — settings upsert/get/delete, env-var
  CRUD + per-host queries.
- `HostEnvironmentRepository.kt` — validation before any write;
  directories stored in canonical form; env VALUES encrypted
  with the existing Keystore crypto (one blob per var) and
  plaintext buffers wiped in finally blocks; values are
  write-only (no "keep existing value" path, no form ever reads
  a value back); load-for-connection returns values plus
  undecryptable NAMES (one bad var never blocks a session);
  duplicate copies settings but never vars (see §5).
- `TermBridgeDatabase.kt` — version 2 -> 3 with a real
  `MIGRATION_2_3` (CREATE TABLE + indices for both tables,
  matching the entities exactly), NOT
  fallbackToDestructiveMigration. `TermBridgeApplication` adds
  the migration and exposes `environmentRepository`.
- `HostRepository.kt` — optional env DAOs (same pattern as
  prompt 08's commandButtonDao): `deleteHost` also deletes the
  host's settings + vars; `duplicateHost` copies settings
  (non-secret) but not env vars (secret-bearing) and not the
  credential.

Transport / terminal:
- `TerminalSession.kt` — `start()` now takes env vars and an
  env-result callback: after `allocatePTY`, before
  `startShell` (the only order the protocol allows), each var
  is sent with sshj `Session.setEnvVar`. A rejection raises
  and is recorded by NAME; rejection is never fatal. No
  `export`-injection fallback exists anywhere. The sshj API
  was verified against the actual 0.40.0 jar (javap:
  `setEnvVar(String, String)` exists; bytecode shows the
  request sent with want-reply and awaited), not assumed from
  memory.
- `SessionViewModel.kt` — loads env settings + decrypted vars
  at connect; requests env with the shell; sends the working
  directory as one visible `cd` immediately after shell start
  and before CONNECTED (terminal input is disabled until then,
  so user typing cannot interleave); exposes `EnvStatus`
  (names + path only); wipes decrypted buffers in finally;
  clears env state on disconnect. Also fixed here: the missing
  `kotlinx.coroutines.flow.collect` import for the terminal
  snapshot and buttons flows — a latent compile error since
  prompt 06 that no run had ever surfaced.

Files:
- `FilesViewModel.kt` — SFTP starts in the host's working
  directory ONLY for POSIX-style paths (a Windows-style path is
  not guessed into an SFTP path; browsing starts at root);
  a POSIX start directory that cannot be listed falls back to
  root once with a note; when the host has a local SAF folder,
  downloads write straight into it via
  `DocumentsContract.createDocument` into the picked tree
  (picker path untouched as fallback); a new `infoMessage`
  channel carries non-error notes.
- `FilesScreen.kt` — download button uses the local folder
  when available (with an upfront note saying so), otherwise
  the picker; start-path fallback note shown.

UI:
- `HostEnvironmentViewModel.kt` + `HostEnvironmentScreen.kt`
  (new) — per-host screen reached from the Hosts list (tune
  icon): remote-shell radios, remote working directory field
  with per-field errors, SAF folder picker (permission is
  persistently taken BEFORE the URI is stored; if permission
  cannot be kept, the URI is not stored at all) + clear with
  permission release, settings save with confirmation, env-var
  list (names + masked marker only) with add/edit dialog
  (value field password-masked; edit always requires re-typing
  the value) and delete with confirmation. No decorative
  controls: every field saves something real.
- `HostsScreen.kt` — tune-icon action per host row; stale
  prompt-04 comment corrected.
- `SessionScreen.kt` — environment section in the CONNECTED
  branch only, shown only when something is configured:
  working directory, server-accepted names, server-rejected
  names, and the server-may-override note. Values never appear.
- Navigation: new route `host_environment/{hostId}` with
  title; `TermBridgeApp` now takes `environmentRepository`;
  `MainActivity` passes it. `SessionViewModelFactory` and
  `FilesViewModelFactory` signatures extended accordingly.
- Strings: 214 total, 0 missing references in static check
  (only `app_name` unused in Kotlin, used by the manifest).

Documentation:
- `docs/ENVIRONMENT_AND_PATHS.md` — the full decision record:
  mechanism table (terminal / SFTP / buttons / app), why
  `setEnvVar` and what "accepted" means (including OpenSSH's
  default `AcceptEnv` behaviour), server overrides the app
  cannot control, the `cd` exception and its narrow bounds,
  POSIX/Windows/local path rules, the encryption decision and
  its consequences, host lifecycle, and what is not in this
  phase.
- `docs/SFTP_NOTES.md` + `docs/COMMAND_BUTTONS.md` — short
  prompt-09 notes each (SFTP start path + local-folder
  downloads; buttons-never-modified interaction rule).

## 2. Commands executed and exact results

- sshj API verification (real evidence, not a claim): the
  0.40.0 jar was fetched from Maven Central and inspected with
  javap — `Session.setEnvVar(String, String)` exists, and its
  bytecode sends the `env` channel request with want-reply and
  awaits the reply. The jar was deleted after inspection; it is
  not a project dependency change (sshj was already pinned).
- Static verification (passed): string-reference check — 213
  Kotlin references, 0 missing; strings.xml and manifest parse
  as XML; grep — no PFP mention in the product tree, no AI
  wording, no cloud/sign-in claims, no logging call in new or
  touched code, no broad storage permission, no env/cd
  reference in command-button code (buttons provably
  untouched), no TODO markers in new files, `allowBackup`
  still false; brace/paren/bracket balance — 0 imbalance in
  all new and touched files under an improved check (block
  comments stripped first; the first-pass flag on WindowsPath
  was a false positive from quote characters inside its KDoc,
  confirmed by re-check and by reading the file).
- No Gradle build, no test run: NOT RUN, same sandbox reasons
  as prompts 02–08 (daemon protocol + Java HTTPS). CI is still
  the first real build/test gate.

## 3. Tests added/run

- JVM unit tests (written, NOT RUN locally):
  `EnvironmentValidatorTest` (16 cases — env-name regex,
  loader blocklist, value NUL/length, POSIX normalisation,
  Windows drive/UNC normalisation + relative/illegal
  rejection, shell-specific directory validation, SAF-URI
  rule, `cd` rendering for all three shells);
  `HostEnvironmentRepositoryTest` (10 cases — settings
  round-trip + canonical storage, invalid directory/URI
  rejection without saving, env encryption round-trip with
  blob != plaintext, duplicate-name rejection, blocked-name
  rejection without leaking the value, edit-replaces-blob,
  delete/host-delete cleanup, cross-host move rejection,
  `EnvApplyResult` names-only);
  `CommandButtonInteractionTest` (4 cases — button preparation
  byte-identical with env + working dir configured, for all
  three modes; `cd` rendering is a separate terminal-only
  path; duplicate copies settings but not vars; delete removes
  settings + vars). Test doubles: `FakeHostSettingsDao` +
  `FakeHostEnvVarDao` added to `FakeDaos.kt` (the unique
  (hostId, name) index is deliberately NOT emulated — duplicate
  rejection is a repository rule and is tested there).
- Instrumented integration: three new ignored cases in
  `SshIntegrationTest` (env accepted/rejected reporting;
  terminal starts in the working directory, missing directory
  leaves a usable shell; SFTP start directory + local-folder
  download), same fixture blocker as prompts 05–08.
- Run: none. Pass/fail: NOT RUN (unit) / IGNORED (integration).
  No real-server env behaviour is claimed.

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet.
- Compile risks to watch on the first CI run: Room v3 schema
  vs `MIGRATION_2_3` SQL (column order and index names must
  match the entities exactly, including the composite unique
  index); the new DAO methods in `TermBridgeDatabase`;
  `RemoteShell.entries` (Kotlin enum entries, fine on Kotlin
  2.0.21 but worth a glance); `DocumentsContract.
  buildDocumentUriUsingTree` / `getTreeDocumentId` (API 21+,
  minSdk is 26); `OpenDocumentTree` contract; `Icons.Filled.
  Tune` (icons-extended); the `NavGraph` signature change
  (three repositories now required — MainActivity updated);
  smart-cast of `settings` in `SessionViewModel.connect`
  after the null check.
- The migration itself is NOT RUN (no device, no room-testing
  dependency — same prompt-08/15 decision as the v1->v2
  migration).
- ViewModel error/info messages remain hardcoded English,
  consistent with the existing ViewModels (Session/Files
  already hardcode theirs); full string-resource localisation
  of ViewModel messages is a prompt-14 pass, not a silent
  exception invented here.

## 5. Security properties: proven vs assumed

- Proven by construction: no plaintext env-value column exists
  in the schema; the only value path is encrypt-on-save /
  decrypt-at-shell-start with wiping; the UI has no API that
  returns a value (list carries names only); loader-injection
  names are blocked at save; duplicate/delete rules are
  enforced in repositories, not the UI; no log call in new
  code; command-button text is never modified by env/path
  settings (grep + interaction tests).
- Proven by tests (once CI runs them): validation, Windows /
  POSIX normalisation, encryption round-trip, duplicate /
  delete / cross-host semantics, button non-interference.
- Assumed, not proven: that a server rejection surfaces via
  sshj's awaited reply exactly as the bytecode suggests
  (needs the fixture: default OpenSSH config, one accepted var
  e.g. `LC_TEST` vs one rejected custom var); sshj SFTP path
  behaviour on Windows servers (hence the deliberate
  POSIX-only SFTP start path, not a claim); SAF tree-URI
  permission behaviour across DocumentsProviders (hence the
  permission-before-store rule and the picker fallback).
- Documented non-claims and limits: env acceptance is per the
  server's reply and login profiles can still override a value;
  lexical path normalisation may differ from server-side
  symlink resolution; uploads do not yet use the local folder
  as a picker starting point (OpenDocument takes no initial
  directory); a String copy of a value (Compose text fields,
  the `String(value)` conversion) cannot be wiped — the same
  documented residual as host credentials in prompt 04.

## 6. Requirements implemented vs partial vs blocked

- Implemented (code scope): per-host env vars (validated,
  encrypted, write-only, blocklisted, per-host scoped) and
  working directories (remote per-shell-style, local SAF-only)
  with canonical storage; SSH env-request mechanism with
  per-var applied/rejected reporting; one visible terminal
  `cd` per shell; SFTP POSIX start directory + root fallback;
  local-folder downloads; per-host environment screen with
  real saves; host duplicate/delete lifecycle for settings
  and vars; explicit non-interference with command buttons.
- Partial: persistence and application are implemented by
  Room/sshj but not demonstrated by a run (no CI/device yet);
  the migration is written but not executed against a real v2
  database; local-folder use covers downloads only (uploads
  still use the picker — stated gap).
- Blocked: build, test execution, real-server fixture (see
  §3/§4).
- Not implemented, correctly: any `export` fallback, any
  per-button env/directory, any exec-channel env, any env-var
  export (prompt 11), any remote-OS auto-detection, any app
  environment change.

## 7. Evidence for claimed capabilities

- No real-server env application is claimed. Claims with
  evidence are the sshj jar inspection (§2), static checks
  (§2), and the test list (§3). Everything needing a run is
  labelled NOT RUN / IGNORED.

## 8. Remaining issues and next prompt

- Prompt 10 — Advanced SSH: tunnels, jump hosts, proxy
  options, only where proven (the pack's own qualifier).
- Open decisions carried forward: repository + CI push,
  application ID confirmation, UI-language confirmation (before
  prompt 14), SSH/SFTP integration fixture (now also covering
  env acceptance on default OpenSSH), connection unification
  (prompt 15), Room transaction for button-child replacement
  (prompt 08 item), form-dialog-level save-error surfacing
  (prompt 08 item), room-testing for the v2->v3 migration
  (same prompt-15/CI decision as v1->v2), ViewModel-message
  localisation (prompt 14), upload picker starting in the
  local folder (blocked on the OpenDocument contract, not
  invented here).
- Continuing autonomously to prompt 10 per the user's instruction.

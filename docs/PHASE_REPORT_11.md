# TermBridge — Phase Report, Prompt 11

Date: 2026-10-24. Phase: 11 — Validated Import/Export and Backup.
Process: autonomous continuation per the user's instruction
(no per-prompt widget).

## 1. Actual changes and touched files

One new dependency, justified by this phase: kotlinx-serialization
1.7.3 (`kotlinx-serialization-json` only). It is used for its JSON
tree API (JsonObject/JsonArray builders and parsers) — no
@Serializable plugin, no generated serializer — because the codec
walks the tree explicitly so unknown fields can be rejected
fail-closed. `org.json` was deliberately NOT used: its Android
stubs throw on the JVM, which would have made the codec
untestable in unit tests. Apache 2.0, same family as
kotlinx-coroutines.

Domain (pure Kotlin, no Android dependency, JVM-testable):
- `BackupFormat.kt` — format constants (format string, version 1,
  5 MB file cap, per-host caps, 8-character minimum passphrase)
  and the decoded models: `ExportHost` (with file-local
  `exportId` for jump chains), `ExportButton`, `ExportSettings`
  (working directory + shell only — no local folder URI),
  `ExportTunnel`, `ExportProxy`, `ExportCredential` (password
  text or PEM, encrypted-payload only), `ExportEnvVar`,
  `ImportConflict` (matched by endpoint, not by name),
  `ImportPreview` (names and counts only), `ImportResult`.

Security:
- `ExportCrypto.kt` — passphrase-based export encryption, no
  custom cryptography: PBKDF2WithHmacSHA256, 600,000 iterations,
  256-bit key, AES-256-GCM with fresh 16-byte salt and 12-byte
  IV per export, 128-bit tag. Wrong passphrase and tampering
  fail identically. Passphrases passed in are consumed and
  wiped. The Keystore key is deliberately NOT used for exports
  (it does not migrate between devices — that is the whole
  reason this feature exists).

Data:
- `BackupCodec.kt` — the ONLY import/export format (TermBridge
  backup JSON v1), encode + fail-closed decode. Envelope and
  document layouts, strict key sets at every level, re-use of
  the existing host/button/env/tunnel/proxy validators, and the
  two hard rules that a non-encrypted file containing a
  credential or an env value is rejected outright. Jump-hop
  references to missing hosts are the one forgiving case
  (chain dropped whole, with a warning — a partial chain is a
  different route). No trust field exists anywhere in the
  format, so import cannot create trust, by construction.
- `BackupService.kt` — orchestration. Export collects from the
  existing repositories (non-secret mode never even loads a
  credential or env value; encrypted mode Keystore-decrypts
  them on the source device and re-encrypts under the
  passphrase — Keystore blobs themselves are never exported,
  because they would be unusable on another device; a credential
  or env value that cannot be decrypted on the source device
  fails the whole export instead of being silently omitted, so a
  backup is never believed complete when it is not). Import
  previews (names/counts/conflicts/warnings, never values),
  then applies in two passes (hosts + children, then jump
  chains only when every hop imported). Conflict rule: same
  hostname (case-insensitive) + port + username; default is
  SKIP, replace requires checkbox + confirmation dialog, and
  even then a credential is replaced only when the backup
  carries one (a non-secret replace keeps the existing
  credential). Child save failures during apply become
  warnings, not silent drops.

UI:
- `BackupViewModel.kt` + factory — SAF file I/O through the
  ContentResolver (read capped at the 5 MB limit), export
  preparation (passphrase match + length checks), preview,
  apply, and message state. Passphrase field strings are
  cleared after use.
- `BackupScreen.kt` — new screen reached from Settings:
  export mode radios (without secrets / encrypted backup),
  passphrase + confirmation fields (encrypted mode only),
  CreateDocument export picker, OpenDocument import picker,
  import passphrase field, preview (counts, host names with
  `username@hostname:port`, conflicts, warnings), replace
  checkbox + confirmation dialog, and the import result
  (imported / replaced / skipped names + warnings).
- Navigation: new `backup` route + title; Settings gains an
  "Import / Export" entry; `TermBridgeApp` takes the new
  `backupService`; `MainActivity` passes it; `TermBridgeApplication`
  exposes the lazy `backupService`. No other screen changed
  behaviour.
- Strings: 268 + 33 new backup strings = 301 total, 0 missing
  references in static check (only `app_name` unused in Kotlin,
  used by the manifest).

Documentation:
- `docs/IMPORT_EXPORT.md` — the full decision record: envelope
  + document layouts with a worked example, encryption
  parameters, the exported/excluded table with reasons
  (trust keys never, local folder never, Keystore blobs never,
  key passphrases never), import rules 1–6, backup vs
  auto-backup vs migration, and SAF-only file access.

## 2. Commands executed and exact results

- Static verification (passed): string-reference check — 300
  Kotlin references, 0 missing (301 strings defined; the only
  Kotlin-unused one is `app_name`, used by the manifest);
  strings.xml and manifest parse as XML; grep — no PFP mention
  in any prompt-11 file and no PFP content copied anywhere, no
  AI wording, no cloud/sign-in claims,
  no `Log.` call and no `println` in new or touched code, no
  TODO markers in new files, no broad storage permission,
  `allowBackup` still false; brace/paren/bracket balance — 0
  imbalance in all new and touched files under the corrected
  check (strings stripped before line comments).
- Secret-leak greps on the new code: the only credential/value
  flows are `BackupService` (load → inner payload → GCM
  encryption, buffers wiped) and `BackupCodec` (decoded
  credential/env values, never logged, never previewed — the
  preview model carries names/counts only). No export path
  writes a plaintext secret: non-secret export never loads
  one, and encrypted export writes only the GCM ciphertext.
- Two review fixes made during writing (not after a run):
  a jump-hop remap placeholder in `BackupService` was replaced
  with a real host-id→export-id map built before collection,
  and a no-op extension-property hack for chain-drop flags in
  `BackupCodec` was removed in favour of warnings carried in
  the decode return value.
- No Gradle build, no test run: NOT RUN, same sandbox reasons
  as prompts 02–10 (daemon protocol + Java HTTPS). CI is still
  the first real build/test gate.

## 3. Tests added/run

- JVM unit tests (written, NOT RUN locally):
  `ExportCryptoTest` (6 cases — round-trip, wrong passphrase,
  tampered ciphertext, salt/IV uniqueness, passphrase wiped
  after use, documented parameter values);
  `BackupCodecTest` (14 cases — plain round-trip, encrypted
  round-trip, wrong passphrase, tampered payload, malformed
  JSON, wrong format, wrong version, unknown field, plaintext
  credential in a plain file, bad host port, 501-host cap,
  missing jump hop → warning + dropped chain, short
  passphrase for encrypted encode, encrypted file without a
  passphrase, settings/shell round-trip);
  `BackupServiceTest` (8 cases — plain export contains no
  credential or env value, plain export flags credential as
  not included, encrypted round-trip restores hosts +
  credential + env value + button + tunnel + proxy + jump
  chain, import never touches trust records, conflict skipped
  by default with the existing host untouched, plain replace
  keeps the existing credential while replacing buttons,
  encrypted replace replaces the credential, chain skipped
  when its hop host was skipped). Test doubles: the existing
  `FakeDaos` + `FakeSecretCrypto` (real AES-GCM, in-memory
  key) — no new fakes were needed.
- Instrumented tests: none added. No new Android-only code
  path needs one: `ExportCrypto` is pure JCA (JVM-tested),
  SAF file I/O is the same contract pattern as the prompt-09
  pickers, and the screen itself is covered by the service
  tests' behaviour contract. A device run of the actual
  pickers + a real encrypted round-trip on device remains an
  open verification item (see §8), not a claim.
- Run: none. Pass/fail: NOT RUN (unit). No import/export
  behaviour on a device is claimed.

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet.
- Compile risks to watch on the first CI run:
  kotlinx-serialization-json tree API usage (`Json.
  parseToJsonElement`, `buildJsonObject`/`buildJsonArray`,
  `jsonObject`/`jsonArray`/`jsonPrimitive` accessors — no
  serialization plugin is applied, which is correct for tree
  use but worth a glance if the build complains about a
  missing plugin); `java.util.Base64` (API 26+, minSdk is 26);
  `PBKDF2WithHmacSHA256` availability on Android API 26+;
  `ActivityResultContracts.OpenDocument()` launched with a
  mime-type array (same pattern as FilesScreen);
  `TermBridgeApp` signature change (five repositories/services
  now required — MainActivity updated);
  `BackupScreen`'s `LaunchedEffect` on `pendingExportText`
  (picker launches once when the text appears, and the text is
  cleared on write or cancel — if the effect ever double-
  fires, the picker would open twice, which is annoying but
  not a data risk).
- No database migration in this phase: no schema changed, so
  the database stays at version 4 with MIGRATION_1_2/2_3/3_4
  untouched.
- Previews for the Backup screen were not added: it requires
  a ViewModel, and faking a service in main-source preview
  code would be worse than no preview (same prompt-04
  decision as Hosts/Form). Its states are covered by the
  service/codec tests instead.
- ViewModel error/info messages remain hardcoded English,
  consistent with the existing ViewModels; full string-resource
  localisation of ViewModel messages is a prompt-14 pass.

## 5. Security properties: proven vs assumed

- Proven by construction: no plaintext credential or env-value
  column or file field exists in a non-secret export (the
  values are never loaded in that mode); no trust field exists
  in the format; no command is executed by decoding; no log
  call exists in new code; passphrases are wiped by the crypto
  layer and cleared in the UI; replace cannot silently
  overwrite a credential (codec + service rules, §1).
- Proven by tests (once CI runs them): encryption round-trip
  and tamper/wrong-passphrase failure, fail-closed parsing
  (unknown field, wrong version, plaintext secret in a plain
  file, caps), conflict skip/replace semantics including
  credential preservation, trust untouched by import, chain
  skip on skipped hop.
- Assumed, not proven: that PBKDF2WithHmacSHA256 + AES/GCM
  behave on the user's device exactly as on the JVM (needs a
  device run of an encrypted round-trip); that SAF pickers
  behave across DocumentsProviders (same assumption as the
  prompt-09 pickers); that 600,000 PBKDF2 iterations cost
  roughly a second on a low-end phone (an estimate, not a
  measurement — if it proves slow, the iteration count is a
  format-version decision, not a silent tweak, because the
  codec pins it at 600,000 for v1).
- Documented non-claims and limits: no automatic/scheduled
  backup (explicit export only — an automatic secret copy on
  a schedule is NOT faked); no PuTTY or other import format;
  no archive format (so no zip-slip/bomb surface); a lost
  export passphrase means the backup is unopenable, with no
  recovery; restoring a non-secret export requires re-entering
  credentials and env values; trust and local folders must be
  re-established/re-picked after migration.

## 6. Requirements implemented vs partial vs blocked

- Implemented (code scope): versioned JSON export/import for
  hosts, command buttons, working directories + shells,
  tunnels, proxies, and jump chains; explicit non-secret export
  (default) vs passphrase-encrypted backup (opt-in, PBKDF2 +
  AES-GCM, documented parameters); preview before import
  (names/counts/conflicts/warnings, no values); fail-closed
  parsing (unknown fields, caps, validators, plaintext-secret
  rejection); non-destructive default import (skip on
  conflict) with explicit confirmed replace; SAF-only file
  access; secret redaction (no secret in any message, log, or
  preview); backup/migration documentation (auto-backup stays
  off, export is the migration path, as promised in prompt 04).
- Partial: behaviour is implemented in code but not
  demonstrated by a run (no CI/device yet); the on-device
  picker + encrypted round-trip check is open (see §3/§8).
- Blocked: build, test execution, device verification (see
  §2/§4).
- Not implemented, correctly: any automatic/scheduled backup,
  any PuTTY or other import format, any archive format, any
  import of trusted host keys, any execution of imported
  commands, any export of the local SAF folder or of a key
  passphrase, any passphrase recovery.

## 7. Evidence for claimed capabilities

- No on-device import/export success is claimed. Claims with
  evidence are the static checks (§2), the secret-leak greps
  (§2), and the test list (§3). Everything needing a run is
  labelled NOT RUN.

## 8. Remaining issues and next prompt

- Prompt 12 — RDP (Windows remote desktop): FreeRDP POC gate
  first, per the pack — an evidence-based blocker report
  instead of a fake feature if the POC gate fails. Prompt 11
  does not prejudge it: no remote-desktop state is exported
  (there is none yet to export).
- Open verification item carried forward: on-device SAF picker
  + encrypted round-trip run (export on device, import on the
  same device with the passphrase, then connect once per host
  to re-establish trust).
- Open decisions carried forward: repository + CI push,
  application ID confirmation, UI-language confirmation (before
  prompt 14), SSH/SFTP integration fixture, connection
  unification (prompt 15), Room transaction for button-child
  replacement (prompt 08 item), form-dialog-level save-error
  surfacing (prompt 08 item), room-testing for the v2->v3 and
  v3->v4 migrations, ViewModel-message localisation (prompt
  14), upload picker starting in the local folder (blocked on
  the OpenDocument contract).
- Continuing autonomously to prompt 12 per the user's instruction.

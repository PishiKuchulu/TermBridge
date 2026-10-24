# TermBridge — Phase Report, Prompt 18

Date: 2026-10-24. Phase: 18 — Release & Handoff (FINAL
prompt of the pack).
Process: autonomous continuation per the user's instruction
(no per-prompt widget).

## 1. Actual changes and touched files

This is a release-preparation phase, NOT a publication:
no repository was created, nothing was pushed, no APK
or source ZIP was produced (see §4 for why that is
correct, not an omission).

Code changes (one feature fix + one schema change):

- `domain/HostEnvironment.kt` — `HostEnvironmentSettings`
  gains `shellConfirmed: Boolean = false`, with the
  definition documented on the field: true only once the
  environment screen has been saved for that host.
- `data/HostEnvironmentEntity.kt` — `HostSettingsEntity`
  gains `shellConfirmed: Boolean = false`.
- `data/HostEnvironmentRepository.kt` — the flag maps
  through `toSettings()` and `saveSettings()`.
- `data/TermBridgeDatabase.kt` — version 4→5, plus
  `MIGRATION_4_5` (`ALTER TABLE host_settings ADD
  COLUMN shellConfirmed INTEGER NOT NULL DEFAULT 0`):
  every pre-existing row migrates to UNCONFIRMED.
- `TermBridgeApplication.kt` — `MIGRATION_4_5` wired
  into `addMigrations`.
- `ui/viewmodel/HostEnvironmentViewModel.kt` — saving
  the environment screen sets `shellConfirmed = true`
  (the shell picker is displayed and editable there).
- `ui/viewmodel/SessionViewModel.kt` — new
  `connectedShellConfirmed` flow, set from settings at
  connect, cleared on disconnect and on cleared.
- `ui/screens/SessionScreen.kt` — passes the flag to
  the parameter dialog.
- `ui/components/CommandDialogs.kt` — `ParameterDialog`
  gains `shellConfirmed: Boolean = false` (secure
  default: a caller that forgets to pass it warns
  rather than staying silent) and shows the
  `shell_unconfirmed_warning` text above the preview
  when a parameterised button is prepared for an
  unconfirmed shell. The warning does not block.
- `domain/CommandTemplate.kt` — pure predicate
  `warnUnconfirmedShell(hasParameters, shellConfirmed)`
  so the rule is unit-testable without a Compose test.
- `data/HostRepository.kt` — duplicate copies the
  shell VALUE but forces `shellConfirmed = false`:
  confirmation attaches to the host row the user
  reviewed, and the duplicate is a new row.
- `app/src/main/res/values/strings.xml` — +1 string
  (`shell_unconfirmed_warning`); `about_licenses_title`
  "Foundation libraries" → "Libraries" and
  `about_licenses_body` rewritten to list every shipped
  runtime dependency with version and licence (all
  Apache 2.0); `desktop_body` rewritten to the
  post-12/13 truth (RDP blocked, VNC core-only).

Docs changed/added:

- `docs/PROJECT_BRIEF.md` — Governance section
  replaced with a generic "Process" section (R16).
- `docs/BASELINE_REPORT_00.md` — private-directory
  path, private-upload hash/description, and
  version-label note removed or made generic (R16);
  remaining items renumbered. No methodology content
  was ever copied into either document; only names
  changed, and both edits say so in a dated note.
- `docs/COMMAND_BUTTONS.md` — unconfirmed-shell
  warning documented in the quoting section.
- `docs/ENVIRONMENT_AND_PATHS.md` — shell-confirmation
  semantics, duplicate/import behaviour documented.
- `docs/REQUIREMENTS_MATRIX.md`, `docs/TEST_MATRIX.md`
  — dated prompt-18 sections appended; original text
  untouched (the pattern prompts 15/17 established).
- `README.md` (project root, NEW) — product summary,
  build commands, server prerequisites, blocked
  features, permissions/privacy, licences, limitations,
  docs map. States the handoff-not-release status
  up front.
- `docs/RELEASE_NOTES.md` (NEW) — the handoff record:
  release status (BLOCKED/PROVISIONAL), prompt-18
  changes, capability summary, security summary, test
  totals, six release gates with their proof, open
  decisions, clean-tree checklist.
- `docs/PHASE_REPORT_18.md` — this report.

Deliberately NOT changed: no repository, no push, no
APK/ZIP, no signing configuration (no key exists or
is committed), no backup format change (v1 unchanged —
confirmation is device-local and is not exported),
no UI-language change (English v1; decision still
Farzad's), no RDP/VNC session work, no phase-report
original text rewritten, no test weakened.

## 2. Commands executed and exact results

- Static verification (passed): string-reference check
  — 319 strings defined (318 + the new warning string),
  318 Kotlin references, 0 missing (only `app_name`
  unused in Kotlin, used by the manifest);
  `strings.xml` and manifest parse as XML; CI YAML
  parses (jobs: build, instrumented); brace/paren/
  bracket balance clean on every prompt-18 touched
  file.
- Greps: framework NAME hits in `PROJECT_BRIEF.md`
  and `BASELINE_REPORT_00.md` = 0 after the edit
  (the phrase "Project Governance Framework" = 0
  tree-wide); `Log.`/`println` in main sources = 0;
  `TODO()`/`FIXME` in production code = 0; empty
  `onClick = {}` = 0; AI/cloud/sign-in wording = 0;
  `RfbConnector`/`RfbSession` in UI/navigation = 0;
  permissions = INTERNET + ACCESS_NETWORK_STATE only;
  `allowBackup=false`; `usesCleartextTraffic=false`;
  one exported activity (the launcher).
- Clean-tree greps: no `build/` directories, no
  `*.apk`/`*.aab`/`*.zip` in the tree; no keystores
  (`*.jks`/`*.keystore`/`*.p12`/`*.pem`/`*.key`), no
  `local.properties`, no `google-services.json`. The
  three "private key material" grep hits are test
  fixtures with dummy bodies (`-----BEGIN OPENSSH
  PRIVATE KEY-----` + `AAAA`), two used to test
  validation and one to test the secret scanner —
  none is real key material.
- Known counter artifact (NOT a prompt-18 regression):
  a naive brace counter flags
  `domain/WindowsPath.kt`, untouched in this prompt.
  Prompt 09 already recorded this as a false positive
  from quote characters in its KDoc and regex strings;
  this prompt re-verified it by direct read — the
  file's braces are balanced.
- No Gradle build, no test run, no device run, no
  server run, no dependency-vulnerability scan: NOT
  RUN / NOT PERFORMED, same sandbox reasons as
  prompts 02–17.

## 3. Tests added/run

- Added (all written, NOT RUN):
  - `ShellDefaultWarningTest` (4): predicate truth
    table — warns iff parameters AND unconfirmed.
  - `HostEnvironmentRepositoryTest` (+2):
    shellConfirmed round-trips through save;
    defaults to false when not supplied.
  - `HostRepositoryTest` (+1): duplicate copies the
    shell value and working directory but NOT its
    confirmation.
  - `MigrationSqlAuditTest` (+2): ALTER ADD COLUMN
    statements match the expected definition
    (table, column, type, NOT NULL, DEFAULT 0);
    final host_settings schema after 1→2→3→4→5 is
    exactly {hostId, remoteWorkingDirectory,
    localDirectoryUri, remoteShell, shellConfirmed}.
  - The audit harness rule changed deliberately and
    is documented in the file: ALTER is now allowed
    ONLY as ADD COLUMN (SQLite has no IF NOT EXISTS
    for ADD COLUMN; Room runs each migration once per
    version step). DROP/DELETE/PRAGMA and other ALTER
    forms are still forbidden.
- Run: none. Pass/fail: NOT RUN (all).
- Recount by grep: 267 JVM `@Test` methods in 35
  files (258 + 9 prompt-18), 23 instrumented `@Test`
  methods (5 runnable + 18 class-level-`@Ignore`d
  under the fixture blocker). Total written: 290.
  Executed: 0.

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet (same as
  prompts 02–17 for the whole app).
- Release status: BLOCKED / PROVISIONAL. The pack
  requires a clean build, executed tests, static
  analysis, dependency/license checks, and a source
  ZIP + APK with SHA-256 hashes — and requires that
  a release be marked blocked when any gate cannot
  run. Every one of those gates needs a repository +
  CI run, a device/emulator, or a fixture, none of
  which exists here. Producing an APK or ZIP locally
  is impossible (no working Gradle build in this
  sandbox) and producing one elsewhere without the
  gates would violate the pack. So this prompt
  produces the handoff documents and NO artifact —
  and reports no hash, because there is no
  deliverable to hash. Inventing one would be the
  exact error this project exists to avoid.
- Signing: NOT configured. No signing key, store, or
  CI secret exists in this tree (deliberately — keys
  are never committed). A signed release artifact
  needs a key + store + CI-secrets decision first.
- Previews: none changed. No preview signature changed.
- Compile risks for the first CI run (by reading
  only): `MIGRATION_4_5` SQL vs Room's v5 schema
  comparison; the audit-test ALTER parser against the
  real migration string; `shellConfirmed` threading
  through `SessionViewModel` → `SessionScreen` →
  `ParameterDialog`; the new string reference;
  `MaterialTheme.colorScheme.tertiary` availability
  (standard Material 3 — low risk).

## 5. Security properties: proven vs assumed

- Proven by code inspection (not by execution):
  unconfirmed-shell warning rule (predicate + dialog
  wiring + ViewModel flag, all read); duplicate and
  import both yield unconfirmed (code paths read);
  migration DEFAULT 0 (SQL read); About licences
  complete against the build files (versions and
  licences checked: AndroidX/Compose/Navigation/
  Coroutines/Room/Material Icons/serialization all
  Apache 2.0; sshj Apache 2.0 per ADR-03); desktop
  body matches the post-12/13 rows.
- Proven partial / qualified: the warning closes the
  R1 residual as defined — "confirmed" means the
  environment screen was saved, not that the user
  consciously changed the picker. A user who saves
  the screen without touching the picker confirms the
  displayed value; that boundary is documented on the
  field, in `COMMAND_BUTTONS.md`, and in
  `ENVIRONMENT_AND_PATHS.md`, not hidden.
- Assumed, not proven: that Room generates the v5
  schema matching `MIGRATION_4_5`; that the v4→v5
  upgrade preserves existing rows on a real device;
  that the dialog warning renders (no Compose test
  harness on JVM); everything else carried from
  prompts 16/17 (handshakes, forwarding, RFB, DES/
  PBKDF2 on API 26+, SAF round-trip).
- Documented non-claims: no penetration test; no
  dependency scan; no build/test/device run; no
  release artifact; no claim this tree is releasable.

## 6. Requirements implemented vs partial vs blocked

- Implemented (this prompt's scope): R1 warning +
  flag + migration + tests; R2/R3 string corrections;
  R16 name removal from the two shippable docs;
  README + RELEASE_NOTES handoff package; final
  static verification sweep.
- Partial, explicitly: the R16 residual — phase-report
  check sentences still contain the acronym as the
  name of the check (no methodology content). A
  zero-mention public tree needs those sentences
  scrubbed; that is a pre-push decision for Farzad,
  recorded in `RELEASE_NOTES.md`, not silently done
  here (phase reports are history).
- Blocked: build, test execution, fixture, device
  checks, dependency scan, signing, repository + CI
  (same standing limits; no device/emulator here).
- Not implemented, correctly: any APK/ZIP artifact;
  any release signing; any backup-format change; any
  UI-language switch or `values-fa`; any RDP/VNC
  session; any weakening of a security property; any
  edit to a phase report's original text.

## 7. Evidence for claimed capabilities

- No capability requiring a run is claimed. Evidence
  in this phase: the static checks (§2), the test
  recount (§3), the code reads behind the warning
  wiring and the duplicate/import semantics (§1),
  the grep counts for the R16 removal (0 hits in
  both docs) and the clean-tree checklist
  (`RELEASE_NOTES.md`).
- The central evidence is negative and checkable:
  zero missing strings, zero logging calls, zero
  stub TODOs, zero empty handlers, zero RFB/UI
  wiring, zero framework-name hits in the two
  shippable docs, zero build artifacts, zero
  keystores, zero real key material in the tree.

## 8. Remaining issues and next steps (handoff)

This is the final prompt of the pack. There is no
prompt 19. What remains is Farzad's, in this order:

1. **Decisions** (all still his): repository creation
   + CI push authorisation; application ID
   confirmation (`com.pishi.termbridge`, working);
   UI language (English-first vs Persian-first);
   schema-export enablement for future migration
   tests; zero-mention scrub of phase-report check
   sentences, if wanted.
2. **Gates** (proof each needs, in
   `RELEASE_NOTES.md`): first CI build + executed
   suite; dependency-vulnerability scan; SSH/SFTP
   fixture run (un-ignores 18 tests); device run
   (prompt-14 checklist, SAF round-trip, API-26+
   crypto, v4→v5 upgrade); release signing setup.
3. **Then**: the first real release artifact (signed
   APK/AAB + source ZIP with SHA-256 hashes) can be
   produced — and only then can any claim of
   "tested", "verified", or "production-compliant"
   replace the NOT RUN labels that honestly cover
   this entire tree today.

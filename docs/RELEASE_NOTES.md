# TermBridge — Release Notes & Handoff (Prompt 18)

Date: 2026-10-24. Version: `0.1.0-foundation`
(versionCode 1, working application ID
`com.pishi.termbridge`, debug suffix `.debug`).
Database: v10. UI language: English + Persian (complete).
see below).

## Release status: BLOCKED / PROVISIONAL — NOT a release

> **Superseded (2026-11-06, audit #6 TB-015):** this document is
> the prompt-18 handoff record. The project has since shipped
> signed releases v0.1.0 and v0.1.1 (GitHub Releases, tag builds
> verified against the release keystore); read the status below
> as the state at handoff time, not the current state.

This tree is a **handoff, not a release**. No APK, no
source ZIP, and no SHA-256 release hash is produced
here, because the pack's release gates cannot run in
this environment (see "Gates" below). The pack's rule
applies: a release is complete only if all required
gates pass; if any gate cannot run, the release is
marked blocked or provisional with the exact reason.
**No gate is bypassed, weakened, or implied.**

What this prompt DID do (the shippable part):

- Closed audit-17 R1 in code (shell confirmation +
  warning — see below).
- Corrected audit-17 R2/R3 user-visible strings
  (About licences, desktop body).
- Removed/renamed audit-17 R16 governance-name
  paragraphs from `PROJECT_BRIEF.md` and
  `BASELINE_REPORT_00.md` (names only; no methodology
  content was ever copied).
- Wrote `README.md` (project root) and this handoff.

## What changed in prompt 18

### R1 — shell-default warning (CLOSED)

- New `shellConfirmed` flag on per-host environment
  settings: true only once the environment screen has
  been saved for that host, where the shell picker is
  displayed and editable. `DB v4→v5` (`MIGRATION_4_5`,
  `ALTER TABLE host_settings ADD COLUMN shellConfirmed
  INTEGER NOT NULL DEFAULT 0`): every pre-existing row
  migrates to UNCONFIRMED, because its stored POSIX
  value was never presented as a choice.
- The parameter dialog warns (does not block) when a
  parameterised button is prepared for an unconfirmed
  shell: values are quoted for a POSIX shell, and if
  the host actually runs cmd.exe that quoting does not
  protect values. The warning names the fix: set the
  host's shell in Environment and save. Parameter-free
  buttons never warn (no quoting happens).
- Duplicate copies the shell VALUE but not its
  confirmation; backup import yields unconfirmed
  (confirmation is device-local and is not part of the
  backup format v1, which is unchanged).
- Tests (written, NOT RUN): `ShellDefaultWarningTest`
  (4, predicate truth table), `HostEnvironmentRepositoryTest`
  (+2: round-trip, defaults-false), `HostRepositoryTest`
  (+1: duplicate semantics), `MigrationSqlAuditTest`
  (+2: ALTER matching, final host_settings schema).
  The audit harness rule changed deliberately and is
  documented in that file: ALTER is now allowed ONLY
  as ADD COLUMN (SQLite has no IF NOT EXISTS for ADD
  COLUMN; Room runs each migration once per version
  step). DROP/DELETE/PRAGMA and other ALTER forms are
  still forbidden.

### R2 — About licences (CORRECTED)

`about_licenses_title` is now "Libraries" and
`about_licenses_body` lists every shipped runtime
dependency with version and licence (all Apache 2.0):
Jetpack Compose (BOM 2024.09.00), Navigation Compose
2.8.0, Kotlin Coroutines 1.8.1, Room 2.6.1, sshj
0.40.0, kotlinx-serialization-json 1.7.3, Material
Icons Extended (Compose BOM 2024.09.00), AndroidX
core/lifecycle/activity. Test-only dependencies are
stated as not shipped.

### R3 — desktop body (CORRECTED)

`desktop_body` now states the post-12/13 truth: RDP
blocked by the prompt-12 gate, VNC core-only with no
session. The per-row texts were already correct and
are unchanged.

### R16 — governance name (REMOVED/RENAMED)

- `PROJECT_BRIEF.md`: the Governance section is now a
  generic "Process" section; the methodology name is
  gone.
- `BASELINE_REPORT_00.md`: the private-directory path,
  the private-upload hash and description, and the
  version-label note are removed or made generic.
- Verified by grep after the edit: zero framework-name
  hits in either document.
- Residual, stated honestly: phase-report check
  sentences (e.g. "no PFP content in prompt-14 files")
  still contain the acronym as the name of the check
  itself. They name no methodology content, but a
  zero-mention public tree would need those sentences
  scrubbed too. That is a pre-push decision for Farzad,
  not something this prompt silently rewrote — phase
  reports are history and are not rewritten.

## Capability summary (from docs/CAPABILITY_MATRIX.md)

- VERIFIED (static evidence sufficient): 2 — no
  cloud/telemetry, no AI (absence claims).
- IMPLEMENTED_NOT_VERIFIED: 13 — code exists and was
  statically checked; nothing has ever been built or
  run. This is the bulk of the app and the reason the
  release is blocked, not a reason to call it done.
- PARTIAL: 6 (incl. VNC core-vs-session, device
  checklist unrun, no UI-thread tests).
- UNAVAILABLE: 1 (FR-08d: dynamic forwarding, HTTP
  CONNECT, ProxyCommand, authenticated SOCKS).
- BLOCKED: 1 (FR-10 RDP). MOCK_ONLY: 0.

## Security summary

- Prompt-16 audit: 13 findings (0 critical, 1 high,
  4 medium, 8 low) — `docs/SECURITY_AUDIT.md`.
- Fix pass: H1 (per-shell quoting + cmd gate),
  M1 (FLAG_SECURE), M2 (trust deleted with host),
  M3 (import transaction), M4 verified (sshj 0.40.0
  strict-kex default-on, jar-verified; server support
  still required) — `docs/SECURITY_FIXES_16.md`.
  Lows L1–L7 fixed, L8 mitigated-in-part.
- Residuals (explicit, not hidden): `SftpException`
  still takes a cause (latent class, deferred);
  dependency-vulnerability scan never run (gate below);
  no penetration test performed or claimed.

## Tests written, NOT RUN

- JVM: 267 `@Test` methods in 35 files.
- Instrumented: 23 `@Test` methods (5 runnable + 18
  class-level-`@Ignore`d under the SSH/SFTP fixture
  blocker — never counted as passed, no mock presented
  as end-to-end).
- Total written: 290. Executed: 0. The sandbox Gradle
  daemon failure + Java HTTPS proxy failure (reproduced
  in prompts 02–17) are the standing reasons; the first
  real proof is the CI run in the gates below.

## Release gates (ALL UNMET here — each names its proof)

1. **Repository + CI run**: create the repository and
   push `project/` (Farzad's authorisation required —
   never done here), then a green CI run of
   `.github/workflows/android-ci.yml` (unit-test +
   debug-assemble job, emulator instrumented job).
   Proof: CI run number + test XML artifacts.
2. **Executed test suite**: all 267 JVM tests pass in
   CI; the 5 runnable instrumented tests pass on the
   emulator; the 18 ignored integration tests are
   un-ignored only against a real fixture (below).
3. **Dependency-vulnerability scan**: never run (audit
   M4 residual). Proof: scan report with zero
   unaddressed critical/high, or waivers recorded.
4. **SSH/SFTP fixture run**: a controlled OpenSSH
   endpoint (test user, known host key, key-rotation
   step) proving handshake, SFTP round-trip, tunnel,
   jump-chain, and SOCKS paths end to end.
5. **Device run**: prompt-14 manual checklist
   (rotation, keyboard, background/foreground,
   low-memory, TalkBack, dark theme + largest font)
   passing on a real device; SAF export/import
   round-trip on device; Keystore/DES/PBKDF2 behaviour
   on API 26+; v4→v5 upgrade on a real database.
6. **Signing**: release signing is NOT configured
   (deliberately — prompt 02 left it to prompt 18, and
   no signing key exists or is committed here). A
   signed release needs a key + store + CI secrets
   decision before any installable release artifact.

## Open decisions (Farzad's, unchanged by this prompt)

- Repository creation + CI push authorisation.
- Application ID confirmation (`com.pishi.termbridge`
  is working, pre-release).
- UI language: English-first vs Persian-first (must be
  decided before any localisation work; no `values-fa`
  exists and none was created here).
- Schema-export enablement for future migration tests
  (prompt-15 decision, still open).
- Zero-mention scrub of phase-report check sentences
  (R16 residual above) if a public tree must contain
  no acronym at all.

## Clean-tree checklist (verified in this prompt)

- No `build/` directories, no `*.apk`/`*.aab`,
  no `*.zip` in the tree.
- No keystores (`*.jks`/`*.keystore`/`*.p12`/`*.pem`/
  `*.key`), no `local.properties`, no `google-services.json`.
- No hardcoded secrets, no key material, no private
  keys in code or docs (grep-verified).
- No logging of secrets (`Log.`/`println` absent from
  main sources); no AI/cloud/sign-in wording.
- No framework content copied anywhere (content grep);
  framework NAME gone from `PROJECT_BRIEF.md` and
  `BASELINE_REPORT_00.md` (grep-verified).
- `.gitignore` covers: Gradle/build output,
  `local.properties`, IDE files, all keystore/credential
  extensions, `*.apk`, and the outside-tree private
  directories (`pfp/`, `pfp-private/`, `prompt-pack/`).
- Manifest final state: INTERNET + ACCESS_NETWORK_STATE
  only; `allowBackup=false`; `usesCleartextTraffic=false`;
  one exported activity (the launcher).
- Strings: 319 defined / 318 used / 0 missing (only
  `app_name` unused in Kotlin, used by the manifest);
  `strings.xml` and manifest parse as XML.
- No source ZIP / APK is produced by this prompt, so
  there is no deliverable hash to report — and none is
  invented. When the gates pass, the source ZIP must
  exclude `build/`, `.gradle/`, `local.properties`,
  keystores, and anything outside `project/`.

## Known limitations (pointers, not repeats)

Full list: `docs/CAPABILITY_MATRIX.md` (limits column)
and the per-feature notes (`TERMINAL_LIMITS.md`,
`SFTP_NOTES.md`, `ADVANCED_SSH.md`, `RDP_NOTES.md`,
`VNC_NOTES.md`, `MOBILE_UX_NOTES.md`,
`INTEGRATION_NOTES.md`). Headliners: RDP blocked, VNC
session blocked, no background service, tunnel session
destination-scoped, SFTP/terminal connections separate,
VNC Auth weak by design, no UI-thread tests, no device
measurements. None of these is presented as a feature.

## Addendum, 2026-10-25 — remediation (not a release)

CI is green for the first time: run #13 (commit 0ce9772,
2026-10-25) passed the build job (267 unit tests + debug
assemble) and the instrumented job (emulator tests). That
closes gate 1 only. The 9-phase remediation that followed
is documented in `docs/REMEDIATION_REPORT.md`,
`docs/TEST_EVIDENCE.md`, `docs/SECURITY_STATUS.md`, and
`docs/RELEASE_READINESS.md` (decision: NO-GO — see that
file for the checklist).

What changed since the handoff above:

- Shell-confirmation gate (phase 1): parameterised
  buttons are refused at the execution boundary while the
  shell is unconfirmed or stale, in every mode.
- Exception-message scrubbing (phase 2): import warnings
  withhold messages matching obvious-secret patterns.
- SSH fixture (phase 3): `tests/fixture/setup-sshd-fixture.sh`
  + 13 implemented instrumented integration tests; 6
  remain @Ignore'd with specific reasons.
- Dependency spot audit (phase 5): OSV queries over direct
  deps (clean) + 4 Bouncy Castle advisories on the sshj
  transitive line, assessed as low exposure, upgrade
  evaluation left open (sshj 0.41.2 exists).
- Device runs, release signing, and tier-2 integration
  tests are still open. Nothing in this addendum makes
  this a release.

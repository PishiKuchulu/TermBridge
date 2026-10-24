# TermBridge — Phase Report, Prompt 17

Date: 2026-10-24. Phase: 17 — Capability & Claim Audit.
Process: autonomous continuation per the user's instruction
(no per-prompt widget).

## 1. Actual changes and touched files

This is an audit phase, not a feature phase and not a fix
pass: NO production code was changed, no new capability,
no new permission, no new dependency, no database version
change (DB stays v4), no RDP/VNC session work, no
UI-language change, no string value changed. Documents
added:

- `docs/CAPABILITY_MATRIX.md` — the pack-required matrix:
  every user-visible capability with UI entry point,
  implementation path, protocol/library, evidence/test ID,
  status (`VERIFIED` / `IMPLEMENTED_NOT_VERIFIED` /
  `PARTIAL` / `MOCK_ONLY` / `UNAVAILABLE` / `BLOCKED`),
  and known limits, plus the negative checks (TODO/stub,
  fake success, swallowed exceptions, mock-as-E2E,
  dead code, misleading labels, PFP/secrets in tree).
- `docs/CAPABILITY_CLAIM_AUDIT.md` — the verdict record:
  counts, findings R1–R18 with file evidence, the two
  user-visible strings needing correction in prompt 18,
  and what was deliberately not changed.
- `docs/PHASE_REPORT_17.md` — this report.

Documents appended to (additions only, original text
intact):

- `docs/REQUIREMENTS_MATRIX.md` — a dated prompt-17
  status section, in the pattern prompt 15 established.
  It records the fix-pass behaviour changes (M2 trust
  deletion, H1 per-shell quoting) that the prompt-15
  section predates.
- `docs/PHASE_REPORT_16.md` — a dated addendum noting
  that the fix pass ran BEFORE prompt 17, superseding
  that report's §8 ("fix pass after prompt 17"). The
  original §8 text is left intact.

Deliberately NOT changed: no production file, no test
file, no build file, no string, no manifest entry, no
Room schema, no RDP/VNC code or text, no UI-language
default (English v1; decision still Farzad's), no
phase-report original text rewritten, no test weakened.

## 2. Commands executed and exact results

- Static verification (passed): string-reference check —
  318 strings defined (unchanged from the fix pass), 317
  Kotlin references, 0 missing (only `app_name` unused
  in Kotlin, used by the manifest); strings.xml and
  manifest parse as XML; grep — no PFP content copied
  anywhere (two governance NAME mentions only, see §6),
  no AI wording (only the `fillMaxWidth` false positive
  on case-insensitive "llm"), no cloud/sign-in claims,
  no `Log.`/`println` in main sources, no empty
  `onClick = {}`, no `TODO()`/`FIXME` in production
  code, no hardcoded success returns, `allowBackup`
  still false, permissions still INTERNET +
  ACCESS_NETWORK_STATE only, cleartext still forbidden,
  one exported activity (MainActivity, MAIN/LAUNCHER).
- Test recount by grep: 258 JVM `@Test` methods (239 at
  prompt 15 + 19 from the fix pass), 23 instrumented
  `@Test` methods (5 runnable + 18 class-level
  `@Ignore`d in `SshIntegrationTest` under the fixture
  blocker). Total written: 281. All NOT RUN.
- Claim re-checks against FIXED code (not pre-fix code):
  per-shell quoting + cmd gate (no production caller
  uses the single-argument `shellQuote` default);
  `CdCommand` cmd.exe rendering rests on `WindowsPath`
  rejecting `"` (verified by read); FLAG_SECURE scope is
  the whole single-activity app (matches the settings
  note); trust deletion in `deleteHost` (fix-pass M2);
  import transaction via `TransactionRunner` /
  `database.withTransaction` (fix-pass M3); RFB core
  still unwired (no UI/navigation reference to
  `RfbConnector`/`RfbSession`); RDP row still
  `UnavailableRow`-only with no connect button.
- No Gradle build, no test run, no device run, no
  server run, no dependency-vulnerability scan: NOT RUN
  / NOT PERFORMED, same sandbox reasons as prompts
  02–16.

## 3. Tests added/run

- Added: none. This phase adds no tests, because it
  changes no code; the regression tests for the two
  flagged strings (R2, R3) and the R1 shell-default
  warning belong to prompt 18, not to this audit.
- Run: none. Pass/fail: NOT RUN (all). No audit claim
  rests on an executed test — every verdict cites code
  that was read or grep output that was counted.

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet (same as prompts
  02–16 for the whole app).
- Audit-specific limitations: without a build, this
  audit cannot confirm that any file compiles
  (accumulated CI compile risks from prompts 08–16 still
  stand, plus the fix-pass risks); without a device,
  Keystore/OEM behaviour, DES availability on API 26+,
  and PBKDF2 cost are still assumed; without a server
  fixture, handshake and forwarding behaviour are still
  assumed; without a compiler, dead-code verdicts are
  grep-level only. These are recorded as limits in the
  matrix and the audit, not as findings with invented
  evidence.
- Previews: none changed. No preview signature changed.

## 5. Security properties: proven vs assumed

- Proven by code inspection (not by execution), after
  the fix pass: per-shell button quoting with a cmd.exe
  gate for parameterised buttons; shell-aware risky
  classification (common/POSIX/Windows sets); FLAG_SECURE
  on the single activity; trust deleted with the host;
  import replace inside one Room transaction; strict-kex
  default-on in sshj 0.40.0 (fix-pass M4, jar-verified);
  the prompt-16 standing claims that still hold
  (insert-never-submits, no accept-all, post-auth trust
  persistence, no plaintext export, no secret logging,
  env encryption, buttons never modified by env/cd,
  RFB no-silent-downgrade, tunnel loopback default).
- Proven partial / qualified by code inspection: the
  quoting guarantee is conditional on the DECLARED shell
  (R1 — default POSIX silently stands in for a shell the
  app explicitly does not detect); the risky heuristic
  remains a documented non-guarantee; the About licence
  list is incomplete (R2); the desktop body text is
  stale (R3).
- Assumed, not proven: that sshj 0.40.0's strict-kex
  default protects a given connection (server support
  still required); that Room generates the expected
  wrappers and schemas; that DES/ECB/NoPadding resolves
  on API 26+; that the emulator/CI jobs run; that no
  file has a compile error (whole-app build never run).
- Documented non-claims and limits: no penetration
  testing performed or claimed; no dependency scan run
  (release gate for prompt 18); no git history audited
  (none exists); no dynamic behaviour observed; no
  finding invented without a code location; no
  `MOCK_ONLY` capability found.

## 6. Requirements implemented vs partial vs blocked

- Implemented (docs scope): the capability matrix, the
  claim audit with findings R1–R18, this phase report,
  and the two dated appends — the pack's acceptance
  criteria for the audit documents themselves.
- Matrix verdicts (23 rows): VERIFIED 2 (NFR-03, NFR-07
  — absence claims with complete static evidence),
  IMPLEMENTED_NOT_VERIFIED 13, PARTIAL 6, UNAVAILABLE 1
  (FR-08d), BLOCKED 1 (FR-10 RDP). MOCK_ONLY 0.
- Partial, explicitly: FR-11 VNC (core implemented,
  session blocked), FR-12 (device checklist documented,
  never run), NFR-04 (no UI-thread tests), NFR-05
  (matrix exists, prerequisites unproven), NFR-06
  (manual reconnect, process death), NFR-08 (release
  packaging is prompt 18; plus the governance-name
  mentions in `PROJECT_BRIEF.md` and
  `BASELINE_REPORT_00.md`, which name but do not copy
  the private methodology — prompt 18 must remove or
  rename them before any public push).
- Blocked: build, test execution, fixture, device
  checks, dependency scan, dynamic verification (same
  standing sandbox limits; no device/emulator here).
- Not implemented, correctly: any production code
  change; any RDP/VNC session UI; any language default
  switch or values-fa (UI-language decision STILL OPEN
  and still Farzad's — prompt 17 assumes no answer, as
  instructed); any test weakening; any claim of a
  penetration test; any edit to a flagged user-visible
  string (R2, R3 are reported for prompt 18, not fixed
  here, so the audit stays independent).

## 7. Evidence for claimed capabilities

- No capability requiring a run is claimed. Claims with
  evidence are: the static checks (§2), the test recount
  (§2), the matrix rows (each tied to a file path and a
  test ID), and the findings R1–R18 (each tied to code
  read in this prompt). Everything needing a run is
  labelled NOT RUN, NOT PERFORMED, or UNPROVEN.
- The central evidence in this phase is negative and
  checkable: zero `MOCK_ONLY` rows, zero fake successes,
  zero empty handlers, zero stub TODOs, zero logging
  calls, zero accept-all paths, zero RFB/UI wiring, zero
  missing strings — each verified by grep over the tree,
  with the commands and outcomes in §2.

## 8. Remaining issues and next prompt

- Prompt 18 — Release & Handoff: it inherits from this
  audit (a) the two user-visible strings to correct (R2
  About licences, R3 desktop body), (b) the R1
  shell-default warning (warn when a parameterised
  button is prepared for a host whose shell is still
  the untouched default), (c) the R16 governance-name
  paragraphs to remove/rename before any public push,
  (d) the release gates that no audit can close here:
  first CI build + executed test suite, dependency
  scan, SSH/SFTP fixture run (un-ignores 18 tests),
  device run (prompt-14 checklist, SAF round-trip,
  Keystore/DES/PBKDF2 on API 26+), and the still-open
  decisions (repo + CI push, application ID
  confirmation, UI-language decision, schema-export
  enablement for future migration tests).
- Open decisions carried forward (unchanged):
  repository + CI push, application ID confirmation,
  UI-language decision (still Farzad's), SSH/SFTP
  integration fixture, schema-export enablement.
- Continuing autonomously to prompt 18 per the user's instruction.

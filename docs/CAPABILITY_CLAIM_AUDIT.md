# TermBridge — Capability & Claim Audit (Prompt 17)

Date: 2026-10-24. Audit only: no production code was changed.
The claim-by-claim matrix is `docs/CAPABILITY_MATRIX.md`;
this document is the verdict record: counts, the refuted and
partial claims with evidence, the user-visible strings that
need correction, and what was deliberately NOT changed.

## Method

Every requirement in `docs/REQUIREMENTS_MATRIX.md` (FR-01..12,
NFR-01..08), every feature claim in `docs/PROJECT_BRIEF.md`
and `docs/ROADMAP.md`, every phase-report claim from prompts
01–16, and the user-visible strings in
`app/src/main/res/values/strings.xml` were checked against
the code as it stands AFTER the prompt-16 security fix pass
(`docs/SECURITY_FIXES_16.md`). Claims were re-read in code,
not taken from prior reports. Where a prior report and the
code disagree, the code wins and the disagreement is listed
below. Nothing was built, run, or device-tested — the same
standing sandbox limits as prompts 02–16 — so no verdict
here rests on an executed test.

## Counts

Requirement rows (23: FR-01..12 with FR-08 split into
a/b/c/d, + NFR-01..08), each given exactly one status in
the matrix:

- `VERIFIED` (static evidence sufficient): 2 — NFR-03
  (no cloud/telemetry), NFR-07 (no AI). Both are absence
  claims; a complete dependency + manifest + string audit
  is sufficient evidence for them.
- `IMPLEMENTED_NOT_VERIFIED`: 13 — FR-01, FR-02, FR-03,
  FR-04, FR-05, FR-06, FR-07, FR-08a, FR-08b, FR-08c, FR-09,
  NFR-01, NFR-02.
- `PARTIAL`: 6 rows — FR-11 (VNC core vs session),
  FR-12 (device checklist unrun), NFR-04 (no UI-thread
  tests), NFR-05 (matrix exists, prerequisites unproven),
  NFR-06 (manual reconnect, process death), NFR-08
  (release packaging is prompt 18).
- `UNAVAILABLE`: 1 row (FR-08d blocked advanced-SSH
  paths, with reasons in the UI).
- `BLOCKED`: 1 row (FR-10 RDP).
- `MOCK_ONLY`: 0. No mock is presented as behaviour.

Claim-level findings R1–R18 below: 0 newly REFUTED
outright (the one pre-fix refutation, parameter quoting,
was FIXED by the fix pass and is now PARTIAL via its
residual), 8 PARTIAL, 10 CONFIRMED (including 2 with a
stated scope note).

## Findings

### R1 — PARTIAL (residual of fixed H1): quoting guarantee holds only for the DECLARED shell

Evidence: `domain/CommandTemplate.kt` — `shellQuote(value,
shell)` (POSIX `'\''`, PowerShell `''` doubling, cmd.exe
throws for parameterised buttons); `SessionViewModel`
threads `connectedShell` from host settings into
`prepare()`; `domain/CdCommand.kt` renders per shell.
No production caller uses the single-argument
`shellQuote(value)` default (grep: only the two-argument
calls exist). BUT: the shell is user-declared and defaults
to POSIX everywhere — `domain/HostEnvironment.kt`
(`remoteShell = POSIX`), `prepare()`'s default parameter,
`RiskyCommand.classify`'s default parameter. The shell is
never auto-detected (prompt 09, by design). So a user who
connects to a cmd.exe host but never sets the shell gets
POSIX quoting silently — the gate only fires when cmd.exe
is DECLARED. The fix-pass doc states the per-shell scope
but does not state this default-shell residual.
Recommendation for prompt 18: when a button with
parameters is prepared for a host whose shell is still
the untouched default, warn that the shell is unconfirmed
(or require a one-time shell confirmation); do not let
the default silently stand in for a detection the app
explicitly does not do.

### R2 — PARTIAL: About licenses list only foundation libraries

Evidence: `about_licenses_title` = "Foundation libraries";
`about_licenses_body` lists only "Jetpack Compose,
Navigation Compose, and Kotlin Coroutines". The actual
dependency list (`gradle/libs.versions.toml`,
`app/build.gradle.kts`) also contains Room 2.6.1, sshj
0.40.0, kotlinx-serialization-json 1.7.3, and Material
Icons Extended — none of which appears on the About
screen. The string's own hedge ("the full license list
grows only as later phases add dependencies, each
recorded in its phase report") is true about phase
reports but does not make the About screen complete.
User-visible string needing correction in prompt 18:
`about_licenses_body` (and title) must list all shipped
dependencies with licences, or stop implying the list is
the app's licence list.

### R3 — PARTIAL (stale UI text): `desktop_body` predates prompts 12–13

Evidence: `desktop_body` says remote desktop "need[s] a
working protocol proof first, in later phases". Prompts
12 and 13 already ran: RDP is BLOCKED and VNC is
core-only, and the rows below the body say so correctly.
The body is stale, not false about any capability, but it
misdescribes the timeline. User-visible string needing
correction in prompt 18: reword `desktop_body` to the
post-12/13 truth (RDP blocked, VNC session blocked,
core implemented).

### R4 — CORRECTED BY ADDENDUM (not by rewriting history): PHASE_REPORT_16 §8

Evidence: `docs/PHASE_REPORT_16.md` §8 said the fix pass
comes "after prompt 17". The fix pass in fact ran BEFORE
prompt 17 (`docs/SECURITY_FIXES_16.md`, 2026-10-24).
This prompt appends a dated addendum to PHASE_REPORT_16
stating the supersession; the original §8 text is left
intact. No other phase-report claim was found to
contradict the code.

### R5 — PARTIAL (stale doc, fixed by append): REQUIREMENTS_MATRIX prompt-15 section

Evidence: the matrix's dated prompt-15 status section
predates the fix pass: it does not reflect M2 (trust
records now DELETED with the host — the prompt-04 rule
reversed) or H1 (per-shell quoting + cmd gate). The
original prompt-01 rows are untouched, as they should
be. This prompt appends a dated prompt-17 status section
in the same pattern prompt 15 established; the prompt-15
section itself is left intact as history.

### R6 — CONFIRMED (with scope note): FLAG_SECURE

Evidence: `MainActivity` sets `FLAG_SECURE` on the
window; `settings_secure_note` says screenshots and
recordings "are blocked in this app". Accurate because
the app has a single activity — the flag's scope IS the
whole app, and the fix-pass doc states that scope
honestly. No overclaim.

### R7 — CONFIRMED: VNC UI text is exactly honest

Evidence: `desktop_vnc_core` lists the implemented subset
(versions, auth types, decodings, "with unit tests for
the protocol logic") AND states "It has never connected
to a real VNC server"; `desktop_vnc_blocked`/`_unblock`
state the gate and the unblock conditions. Grep confirms
no `RfbConnector`/`RfbSession` reference in any UI or
navigation file. The VNC row does not imply a working
session anywhere.

### R8 — CONFIRMED: RDP UI text is exactly honest

Evidence: `desktop_rdp_blocked` states no RDP connection
has been demonstrated, names the missing NDK/CI/endpoint;
`desktop_rdp_library` states the candidate and that the
full licence inventory is open; no connect button exists
(`RemoteDesktopScreen` has only `UnavailableRow`s).
No RDP code, dependency, or native module exists in the
tree.

### R9 — CONFIRMED: no fake success, no empty handlers, no stub TODOs

Evidence: grep over `app/src/main/java` — no
`onClick = {}`, no `TODO()`/`FIXME` in production code
(only text-field `placeholder` parameters and KDoc
words), no hardcoded success returns, no `Log.`/`println`
in main sources. Error paths map to fixed user messages.

### R10 — CONFIRMED (reviewed, acceptable): swallowed exceptions

Evidence: remaining catches are cleanup best-effort that
must not mask the original error, or mappings to fixed
error types. The one genuine swallow (audit-16 L2, tunnel
listen loop) was FIXED in the fix pass
(remove-before-cancel in `stopAll`).

### R11 — CONFIRMED with a stated limit: no accidental dead code

Evidence: the only unwired production code is the RFB
core, deliberately unwired because the session is
blocked, and documented as such in `VNC_NOTES.md`. No
other dead code was found by grep. LIMIT: a
compiler-level unused-code check is impossible without a
build; this verdict is grep-level, not compiler-level.

### R12 — CONFIRMED: test counts as reported

Evidence: recount by grep in this prompt — 258 JVM
`@Test` methods (239 at prompt 15 + 19 from the fix
pass), 23 instrumented `@Test` methods (5 runnable +
18 class-level-`@Ignore`d in `SshIntegrationTest`).
All NOT RUN. The prompt-15 total (262) is superseded by
281 written tests; no report claims any of them passed.

### R13 — PARTIAL: FR-08's own acceptance criterion is unmet

Evidence: FR-08 requires "each supported path has an
end-to-end test". The tests exist (local/remote forward,
jump chain, SOCKS proxy in `SshIntegrationTest`) but are
`@Ignore`d under the fixture blocker. Code-implemented
does not satisfy the criterion as written; the criterion
is met only when the fixture runs. Same pattern as R14.

### R14 — PARTIAL: FR-12's own acceptance criterion is unmet

Evidence: FR-12 requires the prompt-14 manual checklist
to PASS on a real device/emulator. The checklist exists
(`MOBILE_UX_NOTES.md` §5) and is thorough (rotation,
keyboard, background/foreground, low-memory, TalkBack,
dark theme + largest font) — but it has never been run.
Static UX work (48dp rows, IME padding, pinned scroll)
does not satisfy the criterion as written.

### R15 — PARTIAL: NFR-04's own evidence is absent

Evidence: NFR-04 requires "UI-thread checks in tests".
Grep finds no main-thread check in any test file. The
architecture (Dispatchers.IO, streaming, lazy lists,
bounded scrollback) supports responsiveness, but the
specified evidence does not exist. No device measurement
exists either — prompt 14's performance claims are
structural only, correctly labelled as such.

### R16 — PARTIAL: governance-framework NAME in shippable docs

Evidence: `docs/PROJECT_BRIEF.md` (Governance section)
and `docs/BASELINE_REPORT_00.md` name the user's private
governance framework and its private directory. NO
governance CONTENT is copied anywhere (verified by
content grep: the framework's documents stay in
`../pfp-private/`, outside the product tree), and no
secret material is in the tree. But the standing rule as
usually stated ("never in product documentation") is
broader than "never copy content": if `project/` becomes
a public repo as-is, those two paragraphs name a private
methodology. Recommendation for prompt 18: remove or
rename those paragraphs (e.g. "private project
governance, held outside this tree") before any public
push. This is a release-hygiene item, not a leak — but
it must not survive into a public repo unexamined.

### R17 — CONFIRMED: version string consistency

Evidence: `diagnostics_app_version` ("0.1.0-foundation")
matches `versionName` in `app/build.gradle.kts`
(`versionCode` 1). The diagnostics screen's "no sessions"
vs "1 live session" lines are driven by the real
hoisted session ViewModel state (prompt 14), not
hardcoded.

### R18 — CONFIRMED with a dependency note: CdCommand cmd.exe rendering

Evidence: `domain/CdCommand.kt` renders cmd.exe as
`cd /d "<path>"` with double quotes. Safety rests on
`domain/WindowsPath.kt` rejecting `"` (illegal set
includes `"` plus `< > | ? *`, NUL, and control chars),
verified by read in this prompt. The render function
itself does not re-validate — it trusts input that
passed settings-time validation. That dependency is
documented here so a future caller cannot render
unvalidated paths through it.

## What was deliberately NOT changed in this prompt

- No production code, no test code, no build file, no
  manifest entry, no Room schema (DB stays v4), no
  string value. The two strings flagged above (R2, R3)
  are REPORTED for prompt 18, not edited here, so this
  audit stays independent of the fix it recommends.
- No phase report's original text was rewritten. The
  only edits to existing docs are (a) the dated addendum
  appended to `PHASE_REPORT_16.md` (R4) and (b) the dated
  prompt-17 status section appended to
  `REQUIREMENTS_MATRIX.md` (R5) — both additions, both
  explicitly dated, both leaving the original text
  intact.
- No UI-language change: English v1 unchanged, no
  values-fa, decision still Farzad's. No RDP/VNC session
  work, no claim that either works.

## Uncertain / unresolvable from here

- Compiler-level facts (unused code, Room codegen for the
  fix-pass `@Transaction` methods, `database.withTransaction`
  wiring, the prompt-15 emulator job) cannot be settled
  without a build — same standing limit as every prompt.
- Behavioural facts (handshakes, forwarding on Android,
  RFB against a real server, Keystore/DES/PBKDF2 on
  API 26+, SAF round-trip on device) cannot be settled
  without the fixture and a device.
- The dependency-vulnerability scan (audit-16 M4
  residual) was not run and is not claimed; it remains a
  release gate for prompt 18.
- Whether the prompt-18 release notes will repeat the
  central error this audit guards against — calling
  implemented code "verified" — is a prompt-18 decision,
  and this matrix is written to make that error
  checkable line by line.

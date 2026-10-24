# TermBridge — Phase Report, Prompt 14

Date: 2026-10-24. Phase: 14 — Mobile UX, Accessibility, and
Performance Refinement.
Process: autonomous continuation per the user's instruction
(no per-prompt widget).

## 1. Actual changes and touched files

This is a polish phase, not a feature phase: no new
capability, no new dependency, no database change, no
protocol or security semantic changed. The two substantial
changes are both terminal behaviour; the rest is touch,
navigation, error placement, and strings hygiene.

Terminal engine (the substantial work):

- `terminal/VtParser.kt` — alternate screen (CSI ?1049h /
  ?1049l): entering saves the main grid, cursor, and
  saved-cursor slot and switches to a blank screen; exiting
  restores the main screen exactly; scrolling on the
  alternate screen discards lines instead of adding them to
  scrollback; double-enter and stray-exit are no-ops;
  resize resizes the saved main screen too; full reset
  discards the alternate state. Other private modes are
  still consumed and ignored, as before.
- `terminal/TerminalScroll.kt` (new, pure, JVM-testable) —
  pinned auto-scroll policy: `isAtBottom`,
  `shouldAutoScroll`, `newLinesSince`. The view owns the
  pinned flag; these functions only decide from numbers.

Terminal UI:

- `ui/screens/SessionScreen.kt` — TerminalView now tracks
  the pinned flag from LazyListState layout info via
  snapshotFlow, auto-scrolls only while pinned, and shows a
  "Jump to latest (N new lines)" action while unpinned with
  new output; host-picker rows are whole-row tappable at
  48dp minimum; control labels (Ctrl-C, Ctrl-D, Tab, Esc)
  moved from hardcoded literals to string resources (arrow
  symbols stay symbols). Paste confirmation, risky dialog,
  and trust dialog code untouched.

Other screens (touch + error placement + strings):

- `ui/screens/FilesScreen.kt` — host-picker rows
  whole-row tappable; transfer direction mapped from the
  ViewModel's exactly-two hardcoded tokens to the existing
  upload/download strings.
- `ui/screens/CommandsScreen.kt` — host-picker and mode
  rows whole-row tappable; the form dialog now receives
  and displays the repository save error inside the dialog
  (it already stayed open on failure; only the error's
  location was wrong).
- `ui/viewmodel/CommandsViewModel.kt` — opening a fresh
  form (add or edit) clears a stale error, so the dialog
  never shows a previous save's failure.
- `ui/screens/HostFormScreen.kt` — auth-type rows
  whole-row tappable.
- `ui/screens/HostEnvironmentScreen.kt` — shell rows
  whole-row tappable.
- `ui/screens/BackupScreen.kt` — export-mode rows
  whole-row tappable.
- `ui/screens/DiagnosticsScreen.kt` — takes a
  `hasLiveSession` flag (default false, preview-safe) and
  shows the real live-session line; device/Android labels
  moved into strings with format arguments. This fixes a
  stale hardcoded claim ("no session feature exists yet")
  that stopped being true in prompt 06.
- `ui/navigation/TermBridgeNavGraph.kt` — the
  activity-scoped session ViewModel is hoisted to the app
  shell (same instance the Session screen uses, so nothing
  extra is created and no connection is opened by hoisting);
  a terminal action appears in the top bar while connected
  and not already on the Session screen, navigating back to
  the live terminal; Diagnostics receives the live flag;
  the NavHost applies IME padding so an open keyboard does
  not cover bottom input fields. The stale prompt-06
  "session-aware back" comment is corrected: back never
  killed the session, so a confirmation dialog would have
  been wrong — the missing piece was the way back.

Strings: 307 + 9 new = 316 total (4 control labels, jump
action, live-session indicator, 2 diagnostics labels, plus
the live-session diagnostics line), 0 missing references in
static check (only `app_name` unused in Kotlin, used by
the manifest).

Documentation (new):

- `docs/MOBILE_UX_NOTES.md` — the audit: what changed,
  screen-by-screen findings, performance hygiene (with
  explicit no-invented-numbers honesty), security paths
  verified untouched, the 10-step manual device checklist,
  the UI-language decision status, and carried-forward
  items.

Documentation (updated, not rewritten):

- `docs/TERMINAL_LIMITS.md` — alternate screen and pinned
  auto-scroll move from "not supported" to "supported"
  (with the scrollback-above-alt-screen display note); ?47
  stays listed as unsupported; header notes the prompt-14
  update.

Tests (new and changed, all written NOT RUN):

- `VtParserTest`: the unsupported-sequences test no longer
  feeds ?1049 (it now MEANS something — the test was
  changed because the documented behaviour changed, not to
  fit the code; OSC + ?25 + 256-colour remain ignored
  there) and 5 new alternate-screen tests: enter/exit
  restores main content; alt output never enters
  scrollback; double-enter and stray-exit are no-ops;
  cursor restores on exit; resize while on alt preserves
  the main screen at the new size.
- `TerminalScrollTest` (new, 7 cases): empty list at
  bottom, last-visible is bottom, scrolled-up is not,
  unknown position is not bottom (never yank on a guess),
  auto-scroll only when pinned and non-empty, new-lines
  count while unpinned, shrunk list reports zero not
  negative.

Deliberately NOT changed: no build file, no dependency, no
Room schema change (database stays at version 4), no host
profile field, no navigation route added, no RDP/VNC code
or screen text (prompts 12/13 verdicts stand untouched —
this phase polished nothing blocked), no SSH/terminal
protocol semantics, no trust/risky/paste behaviour, no
credential handling, no new permission, no analytics or
telemetry anywhere. UI language default unchanged:
English v1, and no values-fa was created (see §6).

## 2. Commands executed and exact results

- Static verification (passed): string-reference check —
  315 Kotlin references, 0 missing (316 strings defined;
  the only Kotlin-unused one is `app_name`, used by the
  manifest); strings.xml and manifest parse as XML;
  grep — no PFP mention in any prompt-14 file and no PFP
  content copied anywhere, no AI wording, no cloud/
  sign-in claims, no `Log.` call and no `println` in new
  or touched code, no TODO markers in new files, no broad
  storage permission, `allowBackup` still false,
  permissions still INTERNET + ACCESS_NETWORK_STATE only;
  brace/paren/bracket balance — 0 imbalance in all new
  and touched files under the corrected check (strings
  stripped before line comments).
- Call-site check (passed): the only CommandFormDialog
  call site passes the new error argument; the only
  DiagnosticsScreen call sites are the nav graph (with the
  live flag) and the preview (default flag).
- Screen audit (read, not run): all 12 screens read for
  touch targets, labelled actions, wrapping, focus order,
  and empty/loading/error states — findings in
  MOBILE_UX_NOTES.md §2. Device-dependent items
  (clipping at largest font scale, TalkBack order, real
  contrast) are listed as device checks, not claimed.
- No Gradle build, no test run: NOT RUN, same sandbox
  reasons as prompts 02–13 (daemon protocol + Java HTTPS).
  CI is still the first real build/test gate.

## 3. Tests added/run

- Added: 7 TerminalScrollTest cases + 5 VtParser
  alternate-screen cases = 12 new test methods; 1
  existing VtParser test updated for the behaviour change
  (documented in §1, with the reason recorded, as prompt
  06 did for its three corrected expectations).
- Not added, deliberately: no UI/instrumented test
  claiming a device behaviour (keyboard coverage, rotation,
  TalkBack order) — those belong to the manual checklist
  in MOBILE_UX_NOTES.md §5, run on a device, not faked
  here.
- Run: none. Pass/fail: NOT RUN (all). No UX behaviour
  on a device is claimed, and no test is claimed to have
  passed.

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet (same as prompts
  02–13 for the whole app).
- Compile risks to watch on the first CI run:
  `snapshotFlow` + `rememberCoroutineScope` in
  SessionScreen's TerminalView (both standard Compose
  runtime APIs, but new to this file); the NavGraph's
  hoisted `viewModel(factory = ..., viewModelStoreOwner
  = ...)` call with an explicit SessionViewModel type
  (same factory + owner as the old Session destination,
  so the instance is identical, but the call site moved);
  `imePadding` on the NavHost modifier (standard layout
  API); the new DiagnosticsScreen parameter (all call
  sites updated, preview uses the default).
- Previews: DiagnosticsScreenPreview still compiles (new
  parameter has a default); no other preview signature
  changed. Previews were not rendered (no build).

## 5. Security properties: proven vs assumed

- Proven by construction: paste still gates on the dialog
  for both paths (that code was not touched); risky
  confirmation flow unchanged (only the error display
  location in the management dialog changed, and the
  dialog is management-only — it never sends to a shell);
  trust verification untouched; no secret appears in any
  new text (the live-session indicator carries no host
  name, the diagnostics line carries no host data, the
  jump button carries only a line count); no new logging;
  no new permission.
- Proven by test design (not yet by execution — tests
  are NOT RUN): alternate-screen save/restore/no-op/
  resize behaviour, and the pinned-scroll decisions.
- Assumed, not proven: that the pinned-scroll
  snapshotFlow behaves on a real device exactly as the
  pure policy predicts (layout-info timing under rapid
  output is a device finding, not a code assumption — the
  policy degrades safely: unknown position counts as NOT
  at bottom, so the worst case is a missed auto-scroll,
  never a yank); that IME padding + default soft-input
  mode keeps every bottom field visible (device check);
  that TalkBack announces the whole-row radio rows
  sensibly (radio semantics kept, device check).
- Documented non-claims and limits: no device
  measurement (no before/after numbers — the pack's
  "measurements where practical" is honestly "not
  practical here, no device"); no contrast/TalkBack/
  clipping claim; no ?47, reflow, or wide-character fix.

## 6. Requirements implemented vs partial vs blocked

- Implemented (code + docs scope): alternate screen;
  pinned auto-scroll + jump action; whole-row selection
  for every unambiguous single-choice radio row; live-
  session return action + real diagnostics line; save
  error inside the command dialog; IME padding; strings
  centralisation of the last hardcoded prose; the screen
  audit; the manual device checklist; the performance
  hygiene record.
- Partial, explicitly: the UX pass as a whole. Everything
  checkable statically is done; everything needing a
  device (the §5 checklist) is documented but unrun.
  Calling the phase "verified on device" would be the
  false claim this report structure exists to avoid.
- Blocked: build, test execution, and all device checks
  (same standing sandbox limits; no device/emulator here).
- Not implemented, correctly: any RDP/VNC session UI or
  polish of blocked rows; any language default switch or
  values-fa (see below); any change to security-critical
  behaviour; any analytics/telemetry.

## 7. Evidence for claimed capabilities

- No device capability is claimed. Claims with evidence
  are: the static checks (§2), the call-site check (§2),
  the screen audit by reading (§2, with device items
  explicitly separated), and the code itself (every new
  pure-logic branch is exercised by a written — NOT RUN —
  test). Everything needing a run is labelled NOT RUN,
  and the device checklist is labelled as a checklist,
  not a result.

## 8. Remaining issues and next prompt

- Prompt 15 — Integration & Regression Tests: its own
  phase, not prejudged here. Items it inherits from this
  phase and earlier: the §5 device checklist (still
  unrun); Room transaction for button-child replacement
  (prompt 08); room-testing for the v2→v3 and v3→v4
  migrations; upload picker starting in the local folder
  (prompt 09, blocked on the OpenDocument contract);
  on-device SAF picker + encrypted round-trip run
  (prompt 11); connection unification (prompt 07 item);
  form-dialog-level save-error surfacing — DONE in this
  phase (dialog now shows the error); alternate-screen
  device proof (vim/less/htop against a real server).
- UI-language decision: STILL OPEN and still Farzad's.
  English v1 is unchanged; this phase only centralised
  the last hardcoded prose and documented RTL readiness
  (Compose mirrors layout from locale; strings are the
  only prose surface; terminal content is LTR by protocol
  nature). No values-fa was created and no default was
  switched — prompt 15 must not assume an answer either.
- Open decisions carried forward (unchanged): repository
  + CI push, application ID confirmation, SSH/SFTP
  integration fixture.
- Continuing autonomously to prompt 15 per the user's instruction.

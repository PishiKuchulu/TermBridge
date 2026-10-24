# TermBridge — Phase Report, Prompt 08

Date: 2026-10-24. Phase: 08 — Custom Command Buttons and Snippets.
Process: autonomous continuation per the user's instruction
(no per-prompt widget).

## 1. Actual changes and touched files

No new dependency. Everything here is own code on top of Room
2.6.1 (prompt 04) and the prompt-06 terminal.

Domain (pure Kotlin, no Android dependency, JVM-testable):
- `CommandButton.kt` — per-host button model; three strictly
  separate modes (INSERT_ONLY / EXECUTE_ONE / SEQUENCE);
  `PreparedCommand` (rendered text/steps + risk assessment) is
  the only thing the UI ever acts on, and it is never executed
  implicitly.
- `CommandTemplate.kt` — `{{name}}` placeholders; values are
  ALWAYS single-quote escaped for a POSIX-style shell, and
  templates must NOT quote a placeholder themselves (the preview
  shows the exact quoted text). Undeclared placeholders block a
  save; values for undeclared variables are ignored, never
  interpolated; required variables without a value or default
  block preparation.
- `RiskyCommand.kt` — conservative heuristic (see §5 for the
  list). Rendered text is classified, segment by segment, so a
  benign first segment cannot hide a risky later one.
- `SecretScanner.kt` — obvious-secret heuristic; a match BLOCKS
  a save (pattern label shown, never the secret itself).
- `CommandValidator.kt` — label/template/steps/variable/
  placeholder/secret validation, enforced in the repository.
- `CommandRunner.kt` — the execution boundary with a fake-able
  `TerminalSender`. Insert sends text only and its code path
  contains no sendEnter call at all; execute-one sends text +
  one Enter; a sequence sends steps in order with a 1-second
  pause, reports progress after each step, and checks
  cancellation between steps. One sequence at a time: starting
  a second sequence while one runs is refused until the first
  is stopped.

Data:
- `CommandButtonEntity.kt` — three tables: `command_buttons`
  (hostId, label, category, sortOrder, mode, template),
  `command_steps` (ordered templates), `command_variables`
  (name, default, required). Foreign keys cascade (host ->
  buttons -> steps/variables). No secret column exists.
- `CommandButtonDao.kt` — per-host ordered queries, child
  queries, insert/update/delete. List rows are shallow
  (label/category/mode/order); the full button is loaded before
  any edit or execution dialog.
- `CommandRepository.kt` — validation (including the secret
  scan) before any write; per-host scoping; a button cannot be
  moved to another host; duplicate copies content with a
  "(copy)" label and a new id at the end of the list; move
  up/down swaps sortOrder with the neighbour; delete removes
  button + steps + variables.
- `TermBridgeDatabase.kt` — version 1 -> 2 with a real
  `MIGRATION_1_2` (CREATE TABLE + indices for all three tables,
  matching the entity definitions exactly), NOT
  fallbackToDestructiveMigration, even though the app is
  pre-release: anyone who built from source keeps their hosts
  and trust records. `TermBridgeApplication` adds the migration
  and exposes `commandRepository`.
- `HostRepository.kt` — one optional parameter
  (`commandButtonDao`, default null, so existing callers/tests
  keep compiling); `deleteHost` now also deletes that host's
  buttons (the Room cascade is a second net, not the only one).

UI:
- `CommandsViewModel.kt` — management only (host selection,
  list, save/delete/duplicate/move, form-button state). It
  never sends anything to a shell, so managing buttons can
  never accidentally run one.
- `CommandsScreen.kt` — rewritten from shell to management:
  host picker, Add/Edit form dialog (label, category, mode
  radios, command or one-step-per-line steps, variables as
  name or name=default lines, required-names field, per-field
  validation errors), duplicate, move up/down, delete with
  confirmation, and a real "Open session" navigation action.
  The shell banner is gone from this screen because management
  is real. Creating, selecting, or opening this screen never
  runs a button.
- `CommandDialogs.kt` — shared `ParameterDialog` (parameter
  fields + live preview of exactly what will be inserted or
  sent + mode note; the Run/Insert tap here IS the explicit
  execution action) and `RiskyConfirmDialog` (second
  confirmation for risky commands, stating plainly that
  detection is a heuristic and can miss dangerous commands).
  Dismissing the risky dialog returns to the parameter dialog;
  confirming it is the only path to executing a risky command.
- `SessionViewModel.kt` — extended with the connected host's
  buttons flow, action-button state, pending-risky state, and
  sequence progress/stop. Buttons are loaded only for the
  connected host. Execution guards on CONNECTED + a live
  terminal session; disconnect clears buttons, action, risky,
  and sequence state.
- `SessionScreen.kt` — a buttons area under the live terminal
  when CONNECTED: per-host buttons with Insert as a secondary
  (outlined) action and Run / Run sequence as primary actions
  (visually and semantically distinct, as the pack requires),
  sequence progress with a Stop button and a stopped state
  naming where it stopped.
- Navigation: `TermBridgeApp` now takes `commandRepository`;
  the Session destination passes it to the (activity-scoped)
  SessionViewModel factory, the Commands destination builds
  the CommandsViewModel. `MainActivity` passes both
  repositories from the application.
- Strings: 171 total, 0 missing references in static check
  (only `app_name` unused in Kotlin, used by the manifest).
  Old shell strings (`commands_empty_title/body`) removed;
  the Commands preview was removed with the shell (it now
  requires a ViewModel, and faking a repository in main-source
  preview code would be worse than no preview).

Documentation:
- `docs/COMMAND_BUTTONS.md` — mode table, quoting rule,
  risky list, sequence stop/error semantics, secret-scan
  rules, persistence/scoping, and what is not in this phase
  (no import/export, no separate exec channel).

## 2. Commands executed and exact results

- Static verification (passed): string-reference check — 170
  Kotlin references, 0 missing; strings.xml and manifest parse
  as XML; grep — no AI wording, no cloud/sign-in claims, no
  logging call in new code, no hardcoded secret in new code,
  no PFP mention in the product tree; brace/paren/bracket
  balance check (after stripping string literals) — 0 imbalance
  in all new and touched files.
- No Gradle build, no test run: NOT RUN, same sandbox reasons
  as prompts 02–07 (daemon protocol + Java HTTPS). CI is still
  the first real build/test gate.

## 3. Tests added/run

- JVM unit tests (written, NOT RUN locally):
  `CommandTemplateTest` (8 cases — extraction order, shell
  quoting incl. single-quote escaping, missing-value error,
  malformed placeholders, defaults/required, undeclared-value
  ignored, sequence rendering);
  `RiskyAndSecretTest` (7 cases — benign set not risky,
  10 risky categories with reason labels, benign segment
  hiding a risky one, sequence risk from any step, secret
  patterns incl. variable defaults, benign templates clean);
  `CommandValidatorTest` (7 cases — valid, blank label/
  template, sequence without steps, single with steps,
  undeclared placeholder, duplicate/invalid variables, secret
  blocked);
  `CommandRunnerTest` (5 cases — insert sends text but never
  submits, execute-one submits exactly once, sequence order +
  progress, cancellation sends no further steps, wrong-mode
  refusal);
  `CommandRepositoryTest` (8 cases — save/load round-trip
  with steps/variables, per-host scoping + ordering, duplicate
  semantics, delete removes children, move-up swap + no-op at
  the top, secret save blocked without leaking the secret,
  cross-host move rejected, host deletion deletes its buttons).
  Test doubles: `FakeCommandButtonDao` added to `FakeDaos.kt`
  (emulates cascades in delete paths).
- Instrumented integration: two new ignored cases in
  `SshIntegrationTest` (execute-one appears in shell output;
  insert-only does not execute until Enter), same fixture
  blocker as prompts 05–07.
- Run: none. Pass/fail: NOT RUN (unit) / IGNORED (integration).
  No real command execution is claimed.

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet.
- Compile risks to watch on the first CI run: Room v2 schema
  vs `MIGRATION_1_2` SQL (column names/types must match the
  entities exactly, including the Boolean `required` column);
  the `CommandButtonDao` interface implemented by
  `FakeCommandButtonDao` (override parameter names differ
  from the interface — legal Kotlin, but worth a glance if
  CI complains); `flatMapLatest` + `ExperimentalCoroutinesApi`
  in CommandsViewModel; AlertDialog + scrollable form content
  in CommandsScreen; `collectAsState` on the new Session
  StateFlows; the `host_form/{hostId}` route pattern is
  unchanged but the NavGraph signature changed (both
  repositories now required — MainActivity updated).
- The migration itself is NOT RUN (no device, no room-testing
  dependency added deliberately — a migration test would need
  `androidx.room:room-testing`, which is a prompt-15/CI decision,
  not a silent addition here).

## 5. Security properties: proven vs assumed

- Proven by construction: insert-only has no sendEnter path;
  execution requires an explicit dialog tap; risky commands get
  a second dialog; parameter values are shell-quoted, never raw;
  templates/defaults with obvious secrets cannot be saved;
  buttons are per-host and die with their host; no secret is
  logged (no log call in new code); parameter values are never
  persisted.
- Proven by tests (once CI runs them): mode separation
  (insert vs submit observable in the fake sender), quoting,
  risk categories, secret patterns, validation, repository
  semantics incl. host-deletion cascade.
- Assumed, not proven: Room generates the v2 schema and the
  migration as written (needs CI build + a v1 database to
  migrate — none exists in this sandbox); sshj shell behaviour
  for submitted commands on real servers (needs the fixture);
  the 1-second inter-step pause suits real shells (tunable in
  CommandRunner, not a claim about any server).
- Documented non-claims and limits: the risky heuristic is
  conservative and bypassable (aliases, encoding, indirection)
  — it over-triggers by design and never makes a command safe;
  the secret scan is heuristic and cannot see secrets behind
  variables or encoding; sequences do NOT auto-stop on error
  (no per-step exit status exists on a PTY shell); Stop only
  stops further submissions, not already-running steps; save
  replacement of steps/variables is sequential, not a single
  Room transaction (a crash mid-save could leave partial
  children — prompt 15 should wrap it); repository save errors
  surface on the management screen, not inside the form dialog
  (minor UX gap, listed in §8).

## 6. Requirements implemented vs partial vs blocked

- Implemented (code scope): three strictly separate modes with
  distinct UI actions; per-host persistence across restart
  (Room v2 + migration); label/category/ordering; named
  variables with defaults, required flags, validation, and a
  live pre-execution preview; shell-quoted substitution;
  risky confirmation (heuristic, documented); no auto-run on
  creation/selection/opening; edit/duplicate/reorder/delete
  with delete confirmation; secret blocking on save; sequence
  progress + stop/cancel with stated no-auto-stop semantics.
- Partial: persistence across restart is implemented by Room
  but not demonstrated by a run (no CI/device yet); the
  migration is written but not executed against a real v1
  database; form-dialog error surfacing is screen-level, not
  dialog-level (see §5).
- Blocked: build, test execution, real-server fixture (see §3/§4).
- Not implemented, correctly: import/export (prompt 11,
  validated, as the pack requires — no early import/export was
  added and none is justified here); environment variables and
  working directories (prompt 09); a separate non-shell exec
  channel; icon/color choice for buttons (the pack allows it
  only if consistent with the design system — no icon/color
  picker was invented here; mode is communicated by label and
  button style instead).

## 7. Evidence for claimed capabilities

- No real command execution is claimed. Claims with evidence
  are static checks (§2) and the test list (§3). Everything
  needing a run is labelled NOT RUN / IGNORED.

## 8. Remaining issues and next prompt

- Prompt 09 — Environment and Paths: per-host environment
  variables and local/remote working directories, and how they
  interact with (not silently rewrite) command buttons.
- Open decisions carried forward: repository + CI push,
  application ID confirmation, UI-language confirmation (before
  prompt 14), SSH integration fixture, connection unification
  (prompt 15), Room transaction for button-child replacement,
  form-dialog-level save-error surfacing, room-testing for the
  v1->v2 migration.
- Continuing autonomously to prompt 09 per the user's instruction.

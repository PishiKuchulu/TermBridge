# TermBridge — Integration Notes (Prompt 15)

Decisions taken in the integration phase, with reasons. Each
decision names what would change it, so a later phase does not
re-litigate it from scratch — and does not silently reverse it.

## 1. Connection unification: KEEP SEPARATE (decided)

FilesViewModel keeps its own SSH connection, separate from the
activity-scoped terminal connection in SessionViewModel. This
closes the prompt-07 open item with a decision, not a refactor.

Why separation wins here:

- Lifecycle mismatch. The terminal ViewModel is activity-scoped
  (prompt 06: the session survives tab switches); the Files
  ViewModel is destination-scoped (leaving the screen
  disconnects). A shared connection would need a new shared
  lifecycle holder with reference counting — a new failure
  mode (who disconnects last, what happens to an in-flight
  transfer when the terminal disconnects) that cannot be
  tested in this environment (no CI, no device, no fixture).
- Host mismatch. Files can target a different host than the
  live terminal. Unification only helps the same-host case,
  so the second-connection code path must stay regardless —
  unification would add a branch, not remove a path.
- Failure isolation. Today an SFTP failure or disconnect
  cannot kill the terminal, and a terminal disconnect cannot
  abort a transfer except by leaving the screen (which is
  already the documented Files behaviour). A shared sshj
  client couples both: a connection drop kills both, and a
  channel error in one surface could poison shared state.
- Trust/passphrase flow. Each connection runs its own
  prompt-05 trust decision and passphrase entry, with
  credentials wiped after connect. Sharing would require
  reusing trust decisions across ViewModels or reloading
  credentials on reconnect — complexity in the most
  security-sensitive code, for a minor benefit.

Cost, stated honestly: one extra TCP connection and one
extra authentication when both surfaces are used, and a
second passphrase prompt for key hosts in Files even when
the terminal is already connected to the same host (trust
is already persisted, so no second trust dialog). The
benefit of unification — one fewer connection and auth
round-trip in the same-host case — does not outweigh the
above in a codebase with no integration-test environment
to prove the shared lifecycle safe.

What would change this: a controlled fixture (see §4) plus
a device run proving a shared holder's disconnect semantics
under rotation, background/foreground, and mid-transfer
terminal disconnect. Until then, do not unify.

Technical note (why unification was plausible, not fantasy):
a single sshj SSHClient supports multiple channels, so the
terminal's shell channel and an SFTP channel could share one
transport. The blocker is lifecycle and failure coupling,
not protocol capability.

## 2. Command-button child replacement: TRANSACTION (fixed)

Prompt 08 left save replacement sequential (delete steps /
variables, then insert new ones); a crash mid-save could
leave a button with partial children. Prompt 15 closes it:

- `CommandButtonDao.replaceChildren` is a Room `@Transaction`
  (same pattern as prompt 10's `JumpHopDao.replaceChain`):
  delete + insert roll back together on failure.
- `CommandRepository.saveButton` now calls only that method
  for children; `moveButton` uses the new `@Transaction`
  `swapSortOrders` for the same class of partial-write risk
  (two separate order updates could leave two buttons
  sharing one sort order).
- `CommandRepositoryTransactionTest` proves the repository
  contract on the JVM: a failed replacement leaves the old
  children intact. The fake DAO emulates the transaction
  contract with scratch copies; Room's `@Transaction`
  guarantee itself cannot execute on the JVM (see §3).
- Deletion is unchanged (children, then row): a crash there
  leaves a row with no children, which re-delete fixes, and
  the foreign-key cascade is the second net. That state is
  recoverable, unlike partial children on a live button.

## 3. Room migration tests: JVM AUDIT, not room-testing (decided)

Decision: do NOT add `androidx.room:room-testing` /
MigrationTestHelper tests in this phase. Reasons:

- MigrationTestHelper validates migrations against exported
  schema JSONs. This project has `exportSchema = false`
  since prompt 02, so no v1, v2, or v3 schema JSON exists.
  Reconstructing historical schemas by hand would produce
  unproven assertions — tests that pass against invented
  schemas prove nothing about the real database.
- The correct long-term fix is to enable schema export
  going forward (so v4's schema is captured and v4→v5 can
  be helper-tested), not to fabricate v1–v3 schemas now.
  That is a prompt-16/18 or first-release decision, because
  it changes the build (schema directory, committed JSON)
  and should land with a real CI run to prove it.

What prompt 15 does instead, and what it proves:

- `MigrationSqlAuditTest` (JVM, runs in the existing CI
  unit-test job, no emulator needed): runs each Migration
  object against a recording proxy database and checks the
  issued SQL against hand-mirrored entity schemas — same
  tables, columns, types, nullability, primary keys,
  foreign keys (all resolving, all ON DELETE CASCADE), and
  indices (including uniqueness); plus non-destructiveness
  (no DROP/DELETE/ALTER) and version chaining 1→2→3→4.
  If an entity changes without its migration, this fails.
- What it does NOT prove (stated, not hidden): Room's own
  schema comparison accepting the migrated database, and
  data surviving a migration. Both need a real Room
  database. Until a device/emulator run exists, the
  first-run check is manual: install a v1–v3 build (or a
  build at each version), create data, upgrade to the new
  build, and confirm hosts, trust records, buttons, env
  vars, and advanced config survive. That checklist item
  is recorded in docs/TEST_MATRIX.md as BLOCKED, not
  passed.

## 4. Integration fixture: still the blocker (unchanged)

The controlled SSH/SFTP fixture from
docs/SSH_INTEGRATION_BLOCKER.md still does not exist here
(no container runtime, no CI, no device). Consequences,
unchanged from prompts 05–14:

- All 18 `SshIntegrationTest` cases stay class-level
  `@Ignore`d. They are never counted as passed, and no
  mock is presented as a protocol test: the loopback peer
  in `RfbConnectionTest` is labelled in its own KDoc as a
  handshake-sequencing proof only.
- The CI change in this phase (see §5) makes the
  instrumented suite actually run on CI once a repo
  exists — the ignored cases will then be skipped
  (reported as skipped, not passed), and the runnable
  instrumented tests (Keystore, Room, smoke) will execute.
- Flaky tests: none known. There is no test history to
  judge flakiness from (nothing has run); the first CI
  run must treat any flaky result as an investigation
  item, not a retry-and-forget.

## 5. CI changes in this phase

`.github/workflows/android-ci.yml` gains:

- Test-report artifacts: the `build` job uploads the JVM
  unit-test XML reports (`app/build/test-results/
  testDebugUnitTest/`) on every run, including failures
  (`if: always()`), so results are machine-readable even
  when the job fails — the pack's "machine-readable test
  output when available".
- A separate `instrumented` job running
  `:app:connectedDebugAndroidTest` on an emulator
  (API 29, x86_64, google_apis via
  reactivecircus/android-emulator-runner). Separate job
  so an emulator failure does not hide the unit-test
  signal, and vice versa. The ignored integration cases
  will report as skipped there, not passed.

Both are file changes only: no repo exists yet, so no CI
run has executed them. The first CI run must watch the
emulator job (API level, runner version, timeout) and the
migration-audit test's classpath (see phase report §4).

## 6. What prompt 15 deliberately did NOT do

- No RDP/VNC session UI or polish (prompts 12/13 verdicts
  stand; the RFB core stays unwired).
- No UI-language change (English v1; the decision is
  still Farzad's — see phase report §6).
- No new feature, permission, dependency, or database
  version (DB stays v4). The only dependency-adjacent
  note: the migration-audit test uses `androidx.sqlite.db`
  interfaces that arrive transitively with Room — no new
  dependency was added.
- No test removed or weakened. One test file's behaviour
  changed only where production behaviour changed in
  prompt 14 (?1049, documented there); nothing in prompt
  15 weakens an assertion to obtain green.

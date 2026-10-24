# TermBridge — Phase Report, Prompt 15

Date: 2026-10-24. Phase: 15 — Integration, Regression, and
Failure-Path Testing.
Process: autonomous continuation per the user's instruction
(no per-prompt widget).

## 1. Actual changes and touched files

This is an integration phase, not a feature phase: no new
capability, no new permission, no new dependency, no
database version change (DB stays v4), no RDP/VNC session
work, no UI-language change. Three carried-forward decisions
are closed (details + what-would-change-them in
docs/INTEGRATION_NOTES.md), one real defect class is fixed,
and the test suite is consolidated with its gaps closed
where pure logic allowed it.

Decision 1 — connection unification: KEEP SEPARATE (decided,
not refactored). FilesViewModel keeps its own SSH
connection. A shared sshj client is protocol-possible
(one client supports multiple channels) but lifecycle-wrong
here: the terminal ViewModel is activity-scoped while
Files is destination-scoped, so sharing needs a new
reference-counted holder; it would only help the
same-host case (the second-connection path must stay for
different hosts); and it would couple failures (an SFTP
disconnect could kill the terminal and vice versa). Cost
of separation, stated: one extra TCP connection and
authentication, and a second passphrase prompt for key
hosts in Files. The only code touch is the decision record
in FilesViewModel's class KDoc (no behaviour change).

Decision 2 — command-button child replacement: TRANSACTION
(fixed). `CommandButtonDao.replaceChildren` is a Room
`@Transaction` (same pattern as prompt 10's
`JumpHopDao.replaceChain`): the prompt-08 sequential
delete-then-insert gap is closed, so a crash mid-save
cannot leave a button with partial children.
`CommandRepository.saveButton` now calls only that method
for children, and `moveButton` uses the new `@Transaction`
`swapSortOrders` for the same partial-write class (two
separate order updates could leave two buttons sharing
one sort order). Deletion is unchanged (children, then
row): a crash there leaves a row with no children, which
re-delete fixes, and the foreign-key cascade is the second
net. `CommandRepository`'s KDoc is updated to record the
fix; the old "sequential" limitation note is removed
because it is no longer true.

Decision 3 — Room migration tests: JVM AUDIT, not
room-testing (decided). `androidx.room:room-testing` /
MigrationTestHelper was NOT added, deliberately:
MigrationTestHelper validates against exported schema
JSONs, and this project has `exportSchema = false` since
prompt 02, so no v1–v3 schema JSON exists — hand-
reconstructing historical schemas would be unproven
assertions, not evidence. The correct long-term fix
(enable schema export going forward so v4→v5 can be
helper-tested) is a first-release decision, recorded in
INTEGRATION_NOTES §3, not fabricated now. What prompt 15
does instead: `MigrationSqlAuditTest` runs each Migration
object against a recording proxy database and checks the
issued SQL against hand-mirrored entity schemas (same
tables, columns, types, nullability, primary keys,
foreign keys — all resolving, all ON DELETE CASCADE —
and indices including uniqueness), plus
non-destructiveness and version chaining 1→2→3→4. What
it does NOT prove is stated in the test's KDoc: Room's
own schema comparison, and data surviving a migration —
both need a real Room database and remain BLOCKED on a
device/emulator run.

Tests (new, all written NOT RUN):

- `CommandRepositoryTransactionTest` (3): save replaces
  children wholly; a failed replacement leaves the old
  children intact (failure injected via the fake DAO);
  move swap exchanges orders without duplication. Honesty
  note in the test's KDoc: the production atomicity is
  Room's `@Transaction` guarantee (not JVM-executable);
  the test proves the repository contract (one atomic DAO
  call, never four separate calls) with a fake that
  emulates the transaction contract.
- `MigrationSqlAuditTest` (4): version chaining; only
  non-destructive CREATE statements; tables match entity
  schemas exactly; foreign-key targets resolve and
  indices reference real columns (final schema: 10 tables).
- `CdCommandTest` (5): POSIX/PowerShell single-quote
  rendering, embedded-quote escaping, cmd `cd /d` double
  quotes, and no `&&` chaining in any dialect.
- `WindowsPathTest` (9): drive/UNC roots, slash
  normalisation, dot handling, root clamping, illegal
  characters, NUL, relative rejection.
- `SecretRedactorTest` (6): replacement, multiple
  secrets, empty-secret safety, CharArray variant, and
  that `wipe()` actually zeroes CharArray and ByteArray.
- `FakeDaos.kt`: `FakeCommandButtonDao` gains
  `replaceChildren` (scratch-copy commit semantics +
  `failNextReplaceChildren` injection hook) and
  `swapSortOrders`.

Documentation (new):

- `docs/INTEGRATION_NOTES.md` — the three decisions with
  reasons and reversal conditions, the fixture status,
  the CI changes, and what was deliberately not done.
- `docs/TEST_MATRIX.md` — the pack §15 matrix mapped to
  actual tests and honest status (239 JVM methods, 5
  runnable instrumented, 18 ignored instrumented, 262
  total), with known gaps listed, not hidden.

Documentation (updated, not rewritten):

- `docs/REQUIREMENTS_MATRIX.md` — a dated "Prompt 15
  status update" section appended; the original prompt-01
  rows are untouched. FR-10 BLOCKED, FR-11 SPLIT,
  FR-08/FR-09 implemented-with-blocked-parts, the rest
  implemented-in-code with integration proof BLOCKED.

CI (file change only — no repo exists, so NOT RUN):

- `.github/workflows/android-ci.yml` — the `build` job
  uploads JVM unit-test XML reports on every run
  (`if: always()`); a new separate `instrumented` job
  runs `:app:connectedDebugAndroidTest` on an emulator
  (API 29, x86_64, google_apis). The ignored
  integration cases will report as skipped there, not
  passed. Separate job so an emulator failure does not
  hide the unit-test signal.

Deliberately NOT changed: no build dependency added (the
migration-audit test uses `androidx.sqlite.db` interfaces
that arrive transitively with Room); no schema change; no
security behaviour touched (trust, risky, paste, secret
scan code untouched); no RDP/VNC code or text touched; no
test removed or weakened (the only behaviour-changed test
in recent history is prompt 14's ?1049 update, documented
there); no UI-language change (English v1; see §6).

## 2. Commands executed and exact results

- Static verification (passed): string-reference check —
  316 strings defined (unchanged from prompt 14; no new
  user-visible text was needed), 315 Kotlin references,
  0 missing (only `app_name` unused in Kotlin, used by
  the manifest); strings.xml and manifest parse as XML;
  the CI YAML parses (python yaml); grep — no PFP mention
  in any prompt-15 file and no PFP content copied
  anywhere, no AI wording, no cloud/sign-in claims, no
  `Log.` call and no `println` in new or touched code, no
  TODO markers in new files, no broad storage permission,
  `allowBackup` still false, permissions still INTERNET +
  ACCESS_NETWORK_STATE only; brace/paren/bracket balance
  — 0 imbalance in all new and touched files under the
  corrected check (strings stripped before line comments);
  test counts by `@Test` annotation — 239 JVM, 5 runnable
  instrumented, 18 ignored instrumented.
- Call-site check (passed): the only `saveButton` child
  write path is `replaceChildren`; the only `moveButton`
  order write path is `swapSortOrders`; no remaining
  caller of the four separate child calls for saves
  (deleteButton still uses them, deliberately — see §1);
  the only `replaceChildren`/`swapSortOrders`
  implementations are the Room DAO and the fake.
- No Gradle build, no test run: NOT RUN, same sandbox
  reasons as prompts 02–14 (daemon protocol + Java HTTPS).
  CI is still the first real build/test gate, and the new
  instrumented job has never run either.

## 3. Tests added/run

- Added: 27 JVM test methods (3 transaction + 4 migration
  audit + 5 CdCommand + 9 WindowsPath + 6 SecretRedactor),
  taking the JVM suite from 212 to 239 methods; 0 new
  instrumented methods (the 18 ignored cases stay ignored
  — un-ignoring them without a fixture would be the exact
  false claim this phase exists to prevent).
- Consolidated, not just added: TEST_MATRIX.md maps every
  pack §15 area to its tests and status, including the
  areas with no automated coverage (restart/rotation/
  process recreation, accessibility device checks) — those
  are MANUAL checklist items, not silently absent rows.
- Run: none. Pass/fail: NOT RUN (all). No test is claimed
  to have passed, and no ignored test is counted.

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet (same as prompts
  02–14 for the whole app).
- Compile risks to watch on the first CI run:
  `CommandButtonDao.replaceChildren` / `swapSortOrders`
  as `@Transaction` methods with bodies calling other
  DAO suspend functions (same shape as prompt 10's
  `replaceChain`, which carries the same unproven status);
  `FakeCommandButtonDao` implementing the two new DAO
  methods (parameter names differ from the interface by
  design, to avoid shadowing the fake's maps — the
  override is by position, not by name, so this is safe
  but worth a glance); `MigrationSqlAuditTest`'s classpath
  — it imports `androidx.sqlite.db.SupportSQLiteDatabase`
  and `androidx.room.migration.Migration`, which arrive
  transitively with Room 2.6.1's runtime; if the unit-test
  classpath ever stops including them, this test fails to
  compile and the fallback is to move it to the
  instrumented suite (where Room is certainly present);
  the CI YAML's new emulator job (API 29, runner version,
  timeout) and artifact paths
  (`app/build/test-results/testDebugUnitTest/`,
  `app/build/outputs/androidTest-results/connected/`).
- Previews: none changed. No preview signature changed.

## 5. Security properties: proven vs assumed

- Proven by construction: the transaction change touches
  only non-secret persistence (buttons hold no secrets —
  the secret scan still runs before any write, and the
  save path is unchanged up to the child replacement);
  no credential, env value, or trust record is read,
  logged, or altered by any prompt-15 code; the migration
  audit test never executes SQL against a real database
  (proxy only), so it cannot leak or alter data; no new
  logging, permission, or network code.
- Proven by test design (not yet by execution — tests
  are NOT RUN): atomic child replacement contract,
  migration SQL matching entity schemas, cd rendering
  without chaining, Windows path clamping, redaction and
  wiping behaviour.
- Assumed, not proven: that Room's `@Transaction`
  annotation on the new DAO methods generates the
  expected transaction wrapper (same assumption already
  carried for prompt 10's `replaceChain` — CI compile +
  a device run are the proof); that the migration SQL
  Room itself generates from the entities matches the
  hand-mirrored expectations (the audit checks migration
  SQL against entities, not Room-generated SQL against
  migration SQL — the device upgrade check in
  INTEGRATION_NOTES §3 closes this); that the emulator
  job's API-29 image runs the Keystore/Room/smoke tests
  (first CI run will tell).
- Documented non-claims and limits: no integration test
  has run against a real server; no migration has run
  against a real database; no device measurement exists;
  no flaky-test history exists (zero runs, not zero
  flakiness).

## 6. Requirements implemented vs partial vs blocked

- Implemented (code + docs scope): the three decisions
  (separation, transaction, audit-vs-room-testing);
  the transaction fix itself; the migration SQL audit;
  the five gap-test files; the test matrix; the
  requirements-matrix status update; the CI report +
  instrumented-job changes.
- Partial, explicitly: integration testing as a whole.
  Everything checkable statically or on the JVM is done;
  everything needing a server, device, or emulator
  (the 18 ignored cases, the Room schema-compare and
  data-survival migration proof, the prompt-14 device
  checklist, restart/rotation/recreation) is documented
  as BLOCKED/MANUAL, not passed.
- Blocked: build, test execution, fixture, device checks
  (same standing sandbox limits; no device/emulator here).
- Not implemented, correctly: any RDP/VNC session UI;
  any language default switch or values-fa (UI-language
  decision STILL OPEN and still Farzad's — prompt 15
  assumes no answer, as instructed); any new feature;
  any test weakening.

## 7. Evidence for claimed capabilities

- No capability requiring a run is claimed. Claims with
  evidence are: the static checks (§2), the call-site
  check (§2), the code itself (every new pure-logic
  branch is exercised by a written — NOT RUN — test),
  and the decision records (which cite the exact
  constraints that force them, e.g. exportSchema=false
  for the room-testing decision). Everything needing a
  run is labelled NOT RUN, IGNORED, MANUAL, or BLOCKED.

## 8. Remaining issues and next prompt

- Prompt 16 — Independent Security Audit: its own phase,
  not prejudged here. Items it inherits: the standing
  fixture blocker; the unproven `@Transaction` wrappers
  (prompts 10 + 15); the unproven migration data-survival
  proof; the unmeasured PBKDF2 cost on a low-end phone
  (prompt 11); DES availability on API 26+ for VNC Auth
  (prompt 13); snapshotFlow timing on device (prompt 14).
- Open decisions carried forward (unchanged): repository
  + CI push, application ID confirmation, UI-language
  decision (still Farzad's), SSH/SFTP integration fixture,
  schema-export enablement for future migration tests
  (prompt-15 decision, §3).
- Continuing autonomously to prompt 16 per the user's instruction.

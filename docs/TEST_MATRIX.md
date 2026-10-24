# TermBridge — Test Matrix (Prompt 15)

Pack §15 test matrix, mapped to actual tests and honest
status. Status key (same as the brief): `NOT RUN` = written
but never executed; `IGNORED` = explicitly skipped by
`@Ignore` with a recorded blocker, never counted as passed;
`MANUAL` = a device checklist item, not an automated test;
`BLOCKED` = cannot run until the named prerequisite exists.
Nothing below is claimed to have passed — nothing in this
project has ever run (no repo, no CI, no device).

## Counts (by @Test annotation, prompt 15)

- JVM unit tests: 239 methods in 31 files
  (`app/src/test/`), including 27 added in prompt 15.
- Instrumented, runnable: 5 methods — `KeystoreAndRoomTest`
  (4) + `MainActivitySmokeTest` (1).
- Instrumented, ignored: 18 methods — `SshIntegrationTest`
  (whole class `@Ignore`d, fixture blocker).
- Total written: 262 methods.

## Matrix

| Pack §15 area | JVM (NOT RUN) | Instrumented / manual | Status |
|---|---|---|---|
| Host CRUD + encrypted credential persistence | HostRepositoryTest (9), HostValidatorTest (7) | KeystoreAndRoomTest (4) | NOT RUN / BLOCKED on CI+device |
| Host-key first trust, rejection, changed key | HostKeyTrustTest (5), SshTransportTest (policy cases) | SshIntegrationTest: unknown + changed flows | JVM NOT RUN; integration IGNORED (fixture) |
| SSH password/key auth, wrong creds, timeout, cancellation, disconnect | SshTransportTest (mapper cases), HostKeyTrustTest | SshIntegrationTest: handshake, wrong creds, disconnect/cancel | JVM NOT RUN; integration IGNORED (fixture) |
| Interactive shell input/output, Ctrl-C, large output, reconnect | VtParserTest (14), TerminalScrollTest (7) | SshIntegrationTest: shell streaming | JVM NOT RUN; integration IGNORED (fixture); large-output + reconnect device proof MANUAL |
| SFTP list/upload/download, large/zero-byte/Unicode, conflict, permissions, cancellation, interrupted network | RemotePathTest (7) | SshIntegrationTest: round-trip + zero/Unicode/conflict/cancel | JVM NOT RUN; integration IGNORED (fixture); permission + interrupted-network MANUAL |
| Command buttons: insert-only vs execute, variables, sequence errors, dangerous confirmation | CommandTemplateTest (8), CommandValidatorTest (7), CommandRunnerTest (5), CommandRepositoryTest (8), CommandButtonInteractionTest (4), RiskyAndSecretTest (7), CommandRepositoryTransactionTest (3, prompt 15) | SshIntegrationTest: execute-one + insert-only appear-in-shell | JVM NOT RUN; integration IGNORED (fixture) |
| Environment/path validation + server-specific behaviour | EnvironmentValidatorTest (12), HostEnvironmentRepositoryTest (10), CdCommandTest (5, prompt 15), WindowsPathTest (9, prompt 15) | SshIntegrationTest: env accepted/rejected, terminal + SFTP start directory | JVM NOT RUN; integration IGNORED (fixture); OpenSSH AcceptEnv behaviour MANUAL on real server |
| Tunnels / jump / proxy | AdvancedValidationTest (18), AdvancedRepositoryTest (8) | SshIntegrationTest: local + remote forward, jump chain, SOCKS proxy, partial failure | JVM NOT RUN; integration IGNORED (fixture) |
| Import/export: invalid files, preview, round-trip, secret handling | BackupCodecTest (15), BackupServiceTest (8), ExportCryptoTest (6) | None (SAF I/O reuses prompt-09 picker pattern) | JVM NOT RUN; on-device SAF + encrypted round-trip MANUAL (prompt 11 item) |
| RDP / VNC real-session lifecycle | RfbAuthTest (6), RfbDecoderTest (16), RfbMessagesTest (10), RfbConnectionTest (4, loopback peer = sequencing proof only) | None — no session exists to test | Protocol core NOT RUN; session BLOCKED (prompts 12/13 gates) |
| App restart, rotation, process recreation, offline, background/foreground | None automated (no harness exists) | Prompt-14 manual checklist §5 (MOBILE_UX_NOTES.md) | MANUAL, BLOCKED on device |
| Accessibility, navigation, layout regression | RoutesTest (3), ToolchainSmokeTest (1) | MainActivitySmokeTest (1); prompt-14 checklist (TalkBack, largest font, dark theme) | JVM NOT RUN; instrumented NOT RUN; device checks MANUAL |
| Secret handling / redaction | RiskyAndSecretTest (7), SecretRedactorTest (6, prompt 15), SshTransportTest (mapper leak case) | KeystoreAndRoomTest (tampered blob) | NOT RUN |
| Database migrations v1→v2→v3→v4 | MigrationSqlAuditTest (4, prompt 15 — SQL-vs-entity audit, see INTEGRATION_NOTES §3) | None | JVM NOT RUN; Room schema-compare + data-survival proof BLOCKED on device/emulator run |
| Regression: prompt-08 sequential save gap | CommandRepositoryTransactionTest (3) | None | NOT RUN; Room @Transaction guarantee itself needs a device run to observe |

## Known gaps (not hidden)

- No automated UI test asserts a screen's behaviour beyond
  the smoke test; Compose UI tests were not added because
  they need a device/emulator to mean anything, and none
  exists here. The prompt-14 manual checklist is the
  coverage for screens until the CI instrumented job runs.
- No performance measurement (no device): the pack's
  "measurements where practical" remains "not practical
  here", as in prompt 14.
- No flaky-test history: with zero runs, flakiness is
  unknown, not zero. First CI runs must record it.
- No test was removed or weakened in prompt 15.

## Prompt 18 additions (2026-10-24, appended — rows above untouched)

- Shell confirmation: ShellDefaultWarningTest (4, predicate
  truth table), HostEnvironmentRepositoryTest (+2:
  shellConfirmed round-trip + defaults-false),
  HostRepositoryTest (+1: duplicate copies the shell value
  but not its confirmation). Dialog wiring
  (ViewModel flag -> SessionScreen -> ParameterDialog)
  is verified by read, not by test — Compose dialogs
  have no JVM harness here.
- Database migrations v1→v2→v3→v4→v5: MigrationSqlAuditTest
  grows from 4 to 6 methods (ALTER ADD COLUMN matching +
  final host_settings schema check). The harness rule
  changed deliberately and is documented in the file:
  ALTER is now allowed ONLY as ADD COLUMN (SQLite has no
  IF NOT EXISTS for ADD COLUMN; Room runs each migration
  once per version step). DROP/DELETE/PRAGMA and other
  ALTER forms are still forbidden.
- No test was removed or weakened in prompt 18. All tests
  remain NOT RUN (same sandbox limits as prompts 02–17).

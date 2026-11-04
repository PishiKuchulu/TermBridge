# TermBridge — Test Matrix

Refreshed 2026-11-04 (R-04 accuracy pass), counts
re-refreshed the same day after the G7 wave. The prompt-15 original and its prompt-18
additions described a tree that had never run; that is no
longer true, so the matrix is rewritten rather than appended
to. Status key: `RUN` = executed with the stated result;
`IGNORED` = skipped by `@Ignore` with a recorded blocker,
never counted as passed; `ASSUME-SKIP` = skips itself when
its fixture is not configured; `MANUAL` = a device checklist
item, not an automated test; `BLOCKED` = cannot run until
the named prerequisite exists.

## Counts (by @Test annotation, grep recount 2026-11-04, post-G7)

- JVM unit tests: 480 methods in 66 files (`app/src/test/`).
  All run and pass locally (`tests/local/local-check.sh`,
  OK (480 tests) read from the JUnit log, 2026-11-04) and in
  CI. This includes the JVM-side RfbLiveTest (2), which
  ASSUME-SKIPs unless TB_VNC_* is set — the CI `vnc` job
  (item F-03a) is the one place it runs live.
- Instrumented: 30 methods in 5 files (`app/src/androidTest/`):
  - `KeystoreAndRoomTest` (4) + `MainActivitySmokeTest` (1):
    run in the CI emulator job.
  - `CryptoProviderTest` (2): run in the CI emulator job.
  - `SshIntegrationTest` (21): 14 tier-1 + 6 tier-2 cases
    (local forward, remote forward, jump chain, SOCKS,
    partial tunnel failure, changed host key) + the RSA-SHA2
    client-key case (item R-10) — ALL implemented, ZERO
    `@Ignore` remaining; they ASSUME-SKIP only when the
    fixture is not provisioned, and their first live CI run
    comes with the G0–G7 push.
  - `RfbIntegrationTest` (2): ASSUME-SKIP unless a VNC
    fixture is configured; not run in the emulator job
    (item F-03a — the live VNC proof is the JVM test above).
- Total written: 510 methods.

## Matrix

| Area | JVM unit tests (all RUN, passing) | Instrumented / manual | Status |
|---|---|---|---|
| Host CRUD + encrypted credential persistence | HostRepositoryTest (12), HostValidatorTest (7) | KeystoreAndRoomTest (4) | JVM RUN; instrumented RUN in CI |
| Host-key first trust, rejection, changed key | HostKeyTrustTest (5), SshTransportTest (policy cases) | SshIntegrationTest: unknown-host flow RUN in CI; changed-key flow IGNORED (tier-2) | Partial: changed-key integration open (R-03) |
| SSH password/key auth, wrong creds, timeout, cancellation, disconnect | SshTransportTest (9), SshKeyLoadTest (1) | SshIntegrationTest: handshake, key auth, wrong creds, disconnect/cancel | RUN in CI against the fixture |
| Interactive shell input/output, Ctrl-C, large output, reconnect | VtParserTest (14), TerminalScrollTest (7) | SshIntegrationTest: shell streaming | JVM + integration RUN; large-output + reconnect device proof MANUAL |
| SFTP list/upload/download, large/zero-byte/Unicode, conflict, permissions, cancellation, interrupted network | RemotePathTest (7), SftpManagerRenameTest (4) | SshIntegrationTest: round-trip + zero/Unicode/conflict/cancel | JVM + integration RUN; permission + interrupted-network MANUAL |
| Command buttons: insert-only vs execute, variables, sequence errors, dangerous confirmation, shell gate | CommandTemplateTest (8), CommandValidatorTest (7), CommandRunnerTest (5), CommandRepositoryTest (8), CommandRepositoryTransactionTest (3), CommandButtonInteractionTest (4), RiskyAndSecretTest (10), ShellGateTest (13), ShellQuotingTest (11), ShellDefaultWarningTest (4), ControlCharsTest (6) | SshIntegrationTest: execute-one + insert-only appear-in-shell | RUN |
| Environment/path validation + server-specific behaviour | EnvironmentValidatorTest (12), HostEnvironmentRepositoryTest (12), CdCommandTest (5), WindowsPathTest (9) | SshIntegrationTest: env accepted/rejected, terminal + SFTP start directory | JVM + integration RUN; OpenSSH AcceptEnv behaviour on real servers MANUAL (see F-02) |
| Tunnels / jump / proxy | AdvancedValidationTest (18), AdvancedRepositoryTest (8) | SshIntegrationTest forwarding cases | JVM RUN; integration IGNORED (tier-2, R-03) |
| Import/export: invalid files, preview, round-trip, secret handling | BackupCodecTest (15), BackupServiceTest (8), BackupReplaceAtomicityTest (2), ExportCryptoTest (6) | None (SAF I/O reuses the picker pattern) | JVM RUN; on-device SAF round-trip MANUAL (F-04) |
| Settings, app lock, quick connect, discovery, history | AppSettingsTest (5), PinLockTest (4), Phase2Test (9: QuickConnectTest, SubnetPlannerTest, ConnectionLogCodecTest) | CryptoProviderTest (2), MainActivitySmokeTest (1) | RUN |
| RDP / VNC protocol + session | RfbAuthTest (6), RfbDecoderTest (16), RfbMessagesTest (10), RfbConnectionTest (4, loopback peer = sequencing proof only), RfbKeysTest (4) | RfbIntegrationTest (2) | JVM RUN; real-server integration ASSUME-SKIP, not run in CI (F-03a); RDP has no implementation (F-03c) |
| App restart, rotation, process recreation, offline, background/foreground | None automated | Prompt-14 manual checklist §5 (MOBILE_UX_NOTES.md) | MANUAL, BLOCKED on device (R-08) |
| Accessibility, navigation, layout regression | RoutesTest (3), ToolchainSmokeTest (1) | MainActivitySmokeTest (1); prompt-14 checklist (TalkBack, largest font, dark theme) | JVM + smoke RUN; device checks MANUAL (Q-04) |
| Secret handling / redaction | RiskyAndSecretTest (10), SecretRedactorTest (8), SshTransportTest (mapper leak case) | KeystoreAndRoomTest (tampered blob) | RUN |
| Database migrations v1→v2→v3→v4→v5 | MigrationSqlAuditTest (6 — SQL-vs-entity audit, see INTEGRATION_NOTES §3) | KeystoreAndRoomTest (Room on device) | JVM RUN; Room data-survival proof across upgrades MANUAL/BLOCKED on device |
| Regression: prompt-08 sequential save gap | CommandRepositoryTransactionTest (3) | None | JVM RUN; Room @Transaction behaviour itself needs a device run to observe |

## Known gaps (not hidden)

- The CI instrumented job's per-test outcome on the current
  HEAD was not re-verified in this pass; the table reflects
  the job's configuration and `docs/PARITY_PHASES.md`, and
  the first parity PR re-proves it.
- Six tier-2 integration cases remain `@Ignore`'d (R-03)
  and two VNC integration cases never run in CI (F-03a).
- No automated UI test asserts a screen's behaviour beyond
  the smoke test; Compose UI tests need a device/emulator
  to mean anything. The prompt-14 manual checklist covers
  screens until a device run (R-08, F-04).
- No performance measurement exists (Q-03 names the first
  one: a 50 MB terminal-output stress case).
- Emulator flakiness is documented (Unknown API Level /
  split-APK install retries in the fixture wrapper); it is
  not zero and is not hidden.

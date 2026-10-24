# TermBridge — Requirements Matrix (Prompt 01)

Status key: `LOCKED` = decision/requirement fixed for implementation.
`OPEN` = resolved only in its named prompt, with the evidence that prompt
must produce. `DEFERRED` = explicitly postponed. `NOT RUN` = no test has
been executed for that row yet. Nothing below is claimed as implemented,
compiled, or tested — this matrix is the contract that later prompts must
satisfy with evidence.

Priority: P0 = MVP core, P1 = MVP-complete, P2 = conditional/advanced.

## Functional requirements

| ID | Priority | User story / requirement | Acceptance criteria (testable) | Depends on | Evidence required | Status |
|---|---|---|---|---|---|---|
| FR-01 | P0 | As a user, I create, edit, delete, and list host profiles (name, host, port, username, auth type) without any account or sign-in. | CRUD round-trip persists across restart; no sign-in screen blocks local use; validation rejects empty host/username and out-of-range port. | ADR-02 | Unit + persistence tests (prompt 04) | LOCKED scope; storage impl OPEN to 04 |
| FR-02 | P0 | As a user, I authenticate with password or an SSH private key, with passphrase handling for encrypted keys. | Password auth and at least Ed25519 + RSA key auth succeed against a controlled sshd; wrong passphrase fails cleanly without leaking the key. | FR-04, ADR-03 | Integration test vs controlled sshd (05) | LOCKED scope |
| FR-03 | P0 | As a user, I am warned on first connection to an unknown host and can verify its fingerprint before trusting it; a changed host key blocks the connection with a clear warning. | Unknown key → fingerprint shown, no auto-trust; changed key → hard stop, not silent acceptance; accepted key persists per host. | ADR-03, ADR-05 | Host-key tests incl. changed-key case (05) | LOCKED |
| FR-04 | P0 | As a user, I open a real interactive SSH shell session, type, see output, resize, disconnect, and reconnect. | Stateful shell (not one-shot exec); resize propagates; disconnect/reconnect and error paths are handled and reported. | FR-02, ADR-04 | Real shell integration test (06) | LOCKED scope |
| FR-05 | P0 | As a user, I browse remote directories and upload/download files over SFTP, with progress, cancellation, and error reporting. | List, upload, download, cancel, and permission-error cases all behave as specified; partial transfers do not silently claim success. | FR-02 | SFTP integration tests (07) | LOCKED scope, only where server offers SFTP |
| FR-06 | P0 | As a user, I create command buttons with three distinct modes: insert text only, execute one command, run a multi-step sequence; with variables/parameters and confirmation for risky actions. | Insert mode never executes; execute/sequence modes run only after any required confirmation; parameters are validated before running. | FR-04 | Behaviour tests incl. insert-never-executes (08) | LOCKED scope |
| FR-07 | P1 | As a user, I set per-host/session local and remote working directories and environment variables, with the app telling me where a setting cannot apply. | Supported settings apply in their documented scope; unsupported login modes show the limitation instead of faking it. | FR-04 | Scoped-behaviour tests + docs (09) | LOCKED scope |
| FR-08 | P1 | As a user, I use SSH tunnels, jump hosts, and proxy options where they are proven to work. | Each supported path has an end-to-end test; unsupported paths are documented, not offered as working. | FR-02 | E2E tests per path (10) | OPEN to 10 |
| FR-09 | P1 | As a user, I import host profiles and keys from documented formats with a preview, and export an encrypted backup with a round-trip restore. | Preview shows exactly what will be imported; invalid files are rejected with a reason; export → wipe → restore returns the same profiles; secrets are never exported in plaintext. | FR-01, ADR-08 | Round-trip + validation tests (11) | OPEN format to 11 |
| FR-10 | P2 | As a user, I open a remote desktop to a compatible Windows system via RDP. | Real protocol proof-of-concept + lifecycle/security tests pass first; only then is the feature offered. | ADR-06 | POC gate (12) | DEFERRED to 12; NOT proven |
| FR-11 | P2 | As a user, I open a remote desktop to a compatible Linux session via VNC or a suitable alternative protocol. | Real protocol proof-of-concept + supported-server list pass first; only then is the feature offered. | ADR-07 | POC gate (13) | DEFERRED to 13; NOT proven |
| FR-12 | P0 | As a user, I use the app phone-first: no vertical text, no clipped essential labels, touch targets, keyboard and accessibility behaviour that work in portrait and landscape. | All destinations reachable; back navigation correct; manual checklist in prompt 14 passes on a real device/emulator. | ADR-01 | UI tests + manual checklist (03, 14) | LOCKED scope |

## Non-functional requirements

| ID | Requirement | Acceptance criteria | Status |
|---|---|---|---|
| NFR-01 Security | Secrets are encrypted at rest with a Keystore-backed key, never stored/logged in plaintext, never hardcoded; logs redact secrets. | Secret-handling tests pass; static scan finds no plaintext secret storage; log output contains no credential (04, 16). | LOCKED direction (ADR-05); impl in 04 |
| NFR-02 Security | No silent host-key/certificate bypass anywhere, including in tests shipped in the app. | Code search + tests prove the bypass path does not exist in production code (05, 16). | LOCKED |
| NFR-03 Privacy | No mandatory cloud account, no mandatory sync, no analytics/telemetry that sends hostnames, credentials, or command history off-device in MVP. | Manifest + dependency audit shows no such SDK in MVP (02, 17). | LOCKED |
| NFR-04 Performance | Terminal rendering and SFTP progress stay responsive on a phone; long transfers and large listings do not block the UI thread. | No network/disk I/O on the main thread; UI-thread checks in tests (06, 07, 14). | LOCKED direction |
| NFR-05 Compatibility | Protocol support is conditional and documented: server-side prerequisites are stated per protocol; unsupported combinations are shown as unsupported. | Capability matrix (17) lists supported/unsupported combinations with evidence. | LOCKED |
| NFR-06 Reliability | Timeouts, cancellation, reconnect, and background/foreground transitions are handled and reported, not silently dropped. | Failure-path tests for each transport (05–07, 15). | LOCKED direction |
| NFR-07 No AI | No AI/LLM/agent feature, dependency, UI wording, or roadmap item is introduced at any phase. | Dependency + string audit in capability audit (17). | LOCKED |
| NFR-08 Release hygiene | Release artifacts exclude build caches, secrets, local machine state, signing keys, and governance internals. | Release checklist + artifact listing verified (18). | LOCKED |

## Explicitly out of scope for MVP

- Mandatory cloud sync or account; any paywall on core functions.
- Root-required operations.
- Universal protocol support ("works on every machine") for SSH/SFTP/RDP/VNC.
- AI features of any kind.
- Blind import of arbitrary password/key file formats (only documented,
  validated, previewed formats — prompt 11).

## Prompt 15 status update (2026-10-24; original rows above untouched)

The pack requires this matrix to reflect actual implementation
and test status. Per-requirement status as of prompt 15 —
nothing below claims a run; all tests are NOT RUN:

- FR-01..FR-07: implemented in code (prompts 04–09); JVM
  tests written, NOT RUN; integration proof BLOCKED on the
  SSH/SFTP fixture (see docs/SSH_INTEGRATION_BLOCKER.md).
- FR-08: implemented in code for local/remote forwarding,
  jump chains, and unauthenticated SOCKS proxy (prompt 10);
  dynamic forwarding, HTTP CONNECT, ProxyCommand, and
  authenticated SOCKS are BLOCKED with reasons in the UI,
  not offered as working. Integration tests IGNORED
  (fixture).
- FR-09: implemented in code (prompt 11, TermBridge backup
  JSON v1, encrypted + non-secret modes); JVM round-trip
  tests written, NOT RUN; on-device SAF round-trip MANUAL.
- FR-10: BLOCKED — RDP POC gate failed (prompt 12);
  no RDP code, no connect button, blocker report in
  docs/RDP_NOTES.md.
- FR-11: SPLIT — VNC protocol core implemented in pure
  Kotlin (prompt 13), session BLOCKED on the
  controlled-server proof gate; no session UI, no connect
  button, blocker note in docs/VNC_NOTES.md.
- FR-12: implemented in code as far as statically
  checkable (prompts 03, 14); device checklist MANUAL,
  BLOCKED on device.
- NFR-01..NFR-08: directions hold; audit evidence is
  prompt 16 (security) and prompt 17 (capability/claim)
  work, not claimed here.
- Full per-area mapping: docs/TEST_MATRIX.md.
  Integration decisions: docs/INTEGRATION_NOTES.md.

## Prompt 17 status update (2026-10-24; original rows and the prompt-15 section above untouched)

Capability & claim audit (prompt 17, audit only — no code
changed). Per-requirement status as of prompt 17, after
the prompt-16 security fix pass — nothing below claims a
run; all tests are NOT RUN:

- FR-01..FR-07, FR-09: implemented in code; JVM tests
  written, NOT RUN; integration proof BLOCKED on the
  SSH/SFTP fixture. Two behaviour changes since the
  prompt-15 section: (1) command-button quoting is now
  PER DECLARED SHELL (POSIX `'\''`, PowerShell `''`
  doubling, cmd.exe parameterised buttons refused) —
  but the shell is user-declared and defaults to POSIX,
  so a wrongly declared shell silently voids the
  guarantee (audit finding R1, for prompt 18);
  (2) host deletion now DELETES the host's trust record
  (prompt-04 rule reversed), so a re-added host
  re-verifies with a dialog.
- FR-08: implemented subset (local/remote forwarding,
  jump chains, unauthenticated SOCKS) in code, tests
  written NOT RUN, integration @Ignore'd; blocked subset
  (dynamic forwarding, HTTP CONNECT, ProxyCommand,
  authenticated SOCKS) UNAVAILABLE with reasons in the
  UI. The requirement's own acceptance criterion (an
  end-to-end test per supported path) is UNMET until the
  fixture runs.
- FR-10: BLOCKED — RDP POC gate failed (prompt 12);
  no RDP code, no connect button.
- FR-11: SPLIT — VNC protocol core implemented in pure
  Kotlin (prompt 13), session BLOCKED on the
  controlled-server proof gate; no session UI, no
  connect button.
- FR-12: implemented in code as far as statically
  checkable (prompts 03, 14); the device checklist is
  DOCUMENTED, NOT RUN — its acceptance criterion
  (checklist passes on a real device) is UNMET.
- NFR-01, NFR-02: hold in code (Keystore encryption,
  no accept-all path), statically re-verified in audits
  16 and 17; execution proof BLOCKED on CI/device.
- NFR-03, NFR-07: VERIFIED by complete static audit
  (dependency list + manifest + strings): no cloud or
  telemetry SDK, no AI feature/dependency/wording.
- NFR-04: PARTIAL — architecture supports responsiveness
  (IO dispatchers, streaming, lazy lists, bounded
  scrollback) but no UI-thread test exists and no device
  measurement exists.
- NFR-05: PARTIAL — the capability matrix
  (docs/CAPABILITY_MATRIX.md) now exists, but every
  server-side prerequisite is still unproven by run.
- NFR-06: PARTIAL — timeouts, cancellation, and
  deterministic disconnect in code; reconnect is manual;
  process death loses the session (documented).
- NFR-08: PARTIAL — gitignore + grep hygiene hold, but
  release packaging is prompt 18, and two shippable docs
  (PROJECT_BRIEF.md Governance section,
  BASELINE_REPORT_00.md) NAME the private governance
  framework — content not copied, but prompt 18 must
  remove or rename those paragraphs before any public
  push (audit finding R16).
- Full claim-by-claim verdicts: docs/CAPABILITY_MATRIX.md
  (23 rows) and docs/CAPABILITY_CLAIM_AUDIT.md
  (findings R1–R18). Test totals written, NOT RUN:
  258 JVM + 23 instrumented (5 runnable + 18 ignored)
  = 281.

## Prompt 18 status (2026-10-24, appended — original rows above untouched)

- NFR-08: the R16 paragraphs are now REMOVED/RENAMED —
  PROJECT_BRIEF.md's Governance section is now a generic
  "Process" section and BASELINE_REPORT_00.md's governance
  references (name, private directory path, private-upload
  hash and version note) are replaced with generic wording.
  No methodology content was ever copied; only names
  changed. Residual, stated honestly: phase-report check
  sentences (e.g. "no PFP content in prompt-14 files")
  still contain the acronym as the name of the check
  itself — they name no methodology content, but a
  zero-mention public tree would need those sentences
  scrubbed too. That is a pre-push decision for Farzad,
  recorded in docs/RELEASE_NOTES.md.
- FR-08 (buttons): R1 residual CLOSED in code — a new
  `shellConfirmed` flag (DB v4→v5, MIGRATION_4_5) tracks
  whether the host's shell was ever presented on the
  environment screen; unconfirmed shells warn (not block)
  on parameterised buttons. Duplicate copies the shell
  value but not its confirmation; backup import yields
  unconfirmed (confirmation is device-local, not in the
  backup format).
- FR-02/FR-03 (About/licenses): R2/R3 strings corrected —
  about_licenses now lists all shipped runtime
  dependencies with licences; desktop_body states the
  post-12/13 truth (RDP blocked, VNC core-only).
- Test totals written, NOT RUN: see docs/PHASE_REPORT_18.md
  §3 for the recount (JVM + instrumented). No test has
  ever been executed in this environment.

# TermBridge — Phase Report, Prompt 07

Date: 2026-10-24. Phase: 07 — SFTP File Manager and Bidirectional Transfers.
Process: autonomous continuation per the user's instruction
(no per-prompt widget).

## 1. Actual changes and touched files

No new dependency. SFTP uses the same sshj 0.40.0 from prompt 05
(verified in prompt 05: Apache 2.0, ADR-03).

Transport:
- `RemotePath.kt` (pure, JVM-tested) — absolute-path
  normalization ("." dropped, ".." popped, clamped at root),
  parent, join, single-name validation (no "/", no "..", no
  blank, no NUL). Full paths are built by the app, not typed;
  names in dialogs are the attack surface, and they are validated.
- `SftpManager.kt` — SFTP over an established sshj connection:
  list (directories first, then name order), mkdir, rename,
  delete (single entry only), statSize, download/upload via
  InputStream/OutputStream with counting wrappers (progress +
  cancellation flag checked on every read/write), size-only
  integrity check (remote stat vs bytes transferred for
  uploads; bytes written vs listing size for downloads).
  No recursive delete, no digest check (see §5).
  SFTPClient is opened lazily and fails with a clear
  "does not offer SFTP" error when the subsystem is absent.

UI:
- `TrustDialog.kt` — extracted shared trust dialog (previously
  inline in SessionScreen, now used by both Session and Files).
- `FilesViewModel` — own SSH connection (explicit design: separate
  from the terminal's connection; unification is a prompt-15
  decision), host picker + trust flow + passphrase, browsing
  (current path, entries, Up clamped at root, Refresh), mkdir/
  rename/delete, SAF upload (OpenDocument, display-name via
  OpenableColumns, conflict dialog: Overwrite / Upload with new
  name with " (1)" suffix / Cancel), SAF download
  (CreateDocument, progress, cancel, best-effort partial-file
  deletion on failure/cancel), transfer progress StateFlow.
  Browsing starts at "/" (root), not home — sshj's SFTP API
  used here exposes no pwd/home query (documented, not claimed).
- `FilesScreen` — connection UI when not connected; browser
  when connected (path bar, Up/Refresh/Upload/New folder/
  Disconnect, LinearProgressIndicator with bytes, entry rows
  with folder/file icons, size, Download/Rename/Delete actions,
  rename/mkdir/delete/conflict dialogs). Local access is SAF
  only; no broad storage permission exists in the manifest or
  code (verified by grep).
- Navigation: Files destination now builds FilesViewModel with
  the app's ContentResolver and an Add-host callback.
- Strings: all new Files strings added; old shell strings
  (files_empty_title/body) removed; static check passes
  (0 missing, only `app_name` unused in Kotlin, used by manifest).

Documentation:
- `docs/SFTP_NOTES.md` — subsystem requirement, SAF-only access,
  streaming/progress/cancellation, size-only integrity (no
  digest claim), conflict handling, single-entry delete, root
  start, own-connection design.

## 2. Commands executed and exact results

- Static verification (passed): string-reference check — 0 missing;
  grep — no broad storage permission, no logging in SFTP code.
- No Gradle build, no test run: NOT RUN, same sandbox reasons.
  CI is still the first real build/test gate.

## 3. Tests added/run

- JVM unit tests (written, NOT RUN locally): `RemotePathTest`
  (7 cases — normalize, root clamp, non-absolute/NUL rejection,
  parent, join validation, name validation incl. Unicode,
  display name).
- Instrumented integration: two new ignored cases in
  `SshIntegrationTest` (upload/download round-trip;
  zero-byte/Unicode/conflict/cancellation), same fixture
  blocker as prompts 05–06.
- Run: none. Pass/fail: NOT RUN (unit) / IGNORED (integration).
  Real-server upload/download is NOT claimed.

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet.
- Compile risks to watch on the first CI run: sshj SFTPClient
  API (`newSFTPClient`, `ls`, `mkdir`, `rename`, `rm`, `stat`,
  `get(String, OutputStream)`, `put(InputStream, String)`,
  RemoteResourceInfo attributes), SAF ActivityResultContracts
  (OpenDocument/CreateDocument), DocumentsContract.deleteDocument,
  LinearProgressIndicator progress lambda API.
- Transfer test coverage (zero-byte, small, large, Unicode,
  conflict, permission-denied, cancellation, interrupted) is
  listed as fixture tests, not run — the prompt's own
  "as feasible" qualifier, with the blocker documented.

## 5. Security properties: proven vs assumed

- Proven by construction: path traversal blocked by
  RemotePath (names validated, paths normalized/clamped);
  no broad storage permission; transfers stream (no whole-file
  memory load); no secret logging; delete is not recursive.
- Proven by tests (once CI runs them): RemotePath rules.
- Assumed, not proven: sshj SFTP behaviour on Android and on
  real servers (especially `stat` size semantics and `rm` on
  empty directories); SAF behaviour across DocumentsProviders.
- Documented non-claims: digest verification (size only),
  recursive delete, home-start, connection unification.

## 6. Requirements implemented vs partial vs blocked

- Implemented (code scope): remote browser (list/navigate/path/
  metadata size/mkdir/rename/delete with confirmation), local
  browser via SAF (no broad permission), upload/download with
  progress/cancellation/retry-by-reconnect (retry itself is a
  fresh transfer after reconnect, not a resume — resumes are
  not claimed), spaces/Unicode names (validated, not rejected),
  permission-denied/missing-path/conflict handling, path
  traversal prevention, no root required, size integrity check,
  SFTP-subsystem requirement made explicit in UI + docs.
- Partial: large-file handling is streaming by construction but
  not demonstrated (no fixture run); interrupted-transfer
  recovery is cancel + retry, not resume.
- Blocked: build, test execution, real-server fixture (see §3/§4).
- Not implemented, correctly: command buttons (prompt 08),
  environment/paths (prompt 09), advanced SSH (prompt 10),
  recursive delete, digest verification, resume.

## 7. Evidence for claimed capabilities

- No real transfer is claimed. Claims with evidence are static
  checks (§2) and the test list (§3). Everything needing a run
  is labelled NOT RUN / IGNORED.

## 8. Remaining issues and next prompt

- Prompt 08 — Custom Command Buttons: per-host buttons with
  the three strictly separate modes (insert-only / execute-one /
  multi-step sequence), parameters, and confirmation for risky
  actions, over the prompt-06 terminal.
- Open decisions carried forward: repository + CI push,
  application ID confirmation, UI-language confirmation (before
  prompt 14), SSH/SFTP integration fixture, connection
  unification (prompt 15).
- Continuing autonomously to prompt 08 per the user's instruction.

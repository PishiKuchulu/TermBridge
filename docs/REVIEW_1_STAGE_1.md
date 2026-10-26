# Review #1 — Stage 1 fixes (applied, local tree)

Scope approved by Farzad: C-01, C-02, C-03, H-08, workflow sync.
Stage 2 (H-01, H-02, H-04, H-06, H-07, M-*) and Stage 3 (H-05, H-03,
capability completions) are NOT in this batch.

## C-01 — private-key auth (was completely broken)

- `transport/SshTransport.kt`: `loadKeys(pem, passphraseText)` →
  `loadKeys(pem, null, PasswordUtils.createOneOff(passphrase))`.
  The two-argument overload takes a FILE LOCATION and NPEs on a null
  passphrase; the three-argument overload parses PEM content (verified
  against sshj 0.40.0 bytecode). The finder holds the request's own
  passphrase array (single use, during this auth); the caller already
  wipes it in `finally`, so no copy is made.
- New `SshKeyLoadTest` (JVM): an unencrypted RSA PKCS#8 PEM
  generated at runtime, loaded from text through a plain `SSHClient`
  with no server. No test private key is stored in the repo.
  Verified sshj limitation (not a TermBridge bug): sshj 0.40.0's
  PKCS#8 parser rejects Ed25519 keys (OID 1.3.101.112); Ed25519 keys
  in OpenSSH format (the ssh-keygen default) go through sshj's
  OpenSSH parser instead.
- Still open (review stage-1 item 5, NOT in this batch): fixture
  integration test with a real key, which needs `PubkeyAuthentication
  yes` in `tests/fixture/setup-sshd-fixture.sh` (currently `no`).
  That test should use an ssh-keygen-generated key (OpenSSH format),
  which also covers the Ed25519 path.

## C-02 — network I/O off the main thread

- `terminal/TerminalSession.kt`: new single-consumer `Channel`
  writer on `Dispatchers.IO`. `sendBytes` only enqueues — it never
  touches the socket, so Compose handlers cannot throw
  NetworkOnMainThreadException and keys cannot be swallowed by the old
  catch-all. The channel is recreated on every `start()` so a previous
  `close()` cannot leak stale bytes into a later shell. Writer
  failures call `notifyClosed` unless the close was intentional.
  `close()` is documented as off-main-only (it sends
  SSH_MSG_CHANNEL_CLOSE).
- `SessionViewModel`, `FilesViewModel`, `AdvancedViewModel`:
  `disconnect()` / `stopTunnels()` capture the session/connection,
  null the fields, and close on `viewModelScope.launch(IO)`.
  `onCleared()` uses a detached IO scope (viewModelScope is already
  cancelled there). `SshTransport.connect`/`connectChain` already ran
  on IO — no change needed there.

## C-03 — Backspace and hardware keys

- `ui/screens/SessionScreen.kt`: new ⌫ ControlButton (the guaranteed
  path — soft keyboards do not reliably deliver Backspace for an
  always-empty capture field); `onPreviewKeyEvent` handles hardware
  Backspace / Enter / Tab / arrows / Escape / Ctrl+C / Ctrl+D;
  `keyboardOptions` now disables autocorrect and uses
  `KeyboardType.Password` (no IME learning/suggestions).
- Honest limit: a soft keyboard's Backspace on this empty field is
  IME-dependent and may still not arrive as a key event; the button
  is the reliable path. No Compose UI test was added (local harness
  cannot compile UI; CI proves it).

## H-08 — SFTP directory delete

- `transport/SftpManager.kt`: `delete(path, isDirectory)` — `rmdir`
  for directories, `rm` for files; the old comment claiming `rm`
  succeeds on empty directories was wrong and is replaced.
- `FilesViewModel.delete` passes `entry.isDirectory`. The UI's
  "empty the directory first" hint stays for non-empty dirs (rmdir
  refuses those, by design — no recursive delete).
- New instrumented test `sftpDeleteRemovesFileAndEmptyDirectory`
  (file via rm, empty dir via mkdir+rmdir, both asserted gone).
  Instrumented total is now 24 (was 23).

## Workflow sync (H-09, local tree only)

- `.github/workflows/android-ci.yml` in the local tree was stale
  (plain `connectedDebugAndroidTest`, "@Ignore'd (no fixture yet)"
  comment). It is now byte-identical to the live file (blob SHA
  `75ec6edc…b245`, read from main HEAD `fb5d7fd`): the instrumented
  job runs `bash tests/fixture/run-instrumented-with-fixture.sh`.
  This file is NOT pushed by this batch — the live repo already has
  this exact content; the sync only stops future ZIPs/reviews from
  going stale again.
- Known stale comment inside the synced file: it says "12
  implemented SshIntegrationTest cases"; with the new delete test
  it is 13. Fixing that one word needs a browser-route workflow
  commit, so it waits for the next real workflow change.

## Verification

- `tests/local/local-check.sh` must exit 0 before push (it proves
  C-01, C-02 non-UI code, H-08, and instrumented-test compilation).
- CI must still prove: C-03 compilation (Compose, excluded locally),
  the new instrumented delete test on the emulator, and the debug
  assemble. The fixture key-auth test remains open (see C-01).

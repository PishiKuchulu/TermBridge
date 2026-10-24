# TermBridge — Test Plan (Prompt 01)

Test labels follow the brief: a test that has not run is `NOT RUN`,
never "passed". All rows below are `NOT RUN` at prompt 01 — this plan
defines what later prompts must run, it does not claim results.

## Unit tests (JVM, no device)

- Host validation: empty host/username, port range, duplicate handling.
- Command-button policy: insert mode never calls execute; risky-action
  confirmation is required before execute/sequence; parameter validation
  rejects missing/invalid parameters.
- Terminal engine: scripted VT byte streams — text, colours, cursor,
  erase, scroll, alternate screen, resize, wide characters.
- Encryption: round-trip encrypt/decrypt, wrong-key failure, IV
  uniqueness, no plaintext secret in stored blobs.
- Host-key store: unknown → prompt, accepted → stored, changed → hard
  stop, fingerprint formatting matches the presented key.
- Import validation: malformed files rejected with a reason; preview
  matches exactly what would be stored (nothing stored on preview).
- Environment/path scoping: settings apply only in their documented
  scope; unsupported modes report the limitation.

## Integration tests (controlled endpoints, no real user servers)

- SSH vs a local controlled sshd (container or local sshd): password
  auth, key auth (Ed25519, RSA), wrong password/key failure, timeout,
  cancellation, disconnect/reconnect, host-key changed case.
- Shell session: real interactive shell, command echo, resize
  propagation, long output, exit/error paths.
- SFTP vs the same controlled sshd: list, upload, download, cancel,
  progress, permission-denied, missing-file, interrupted-transfer cases.
- Tunnels/jump/proxy: one E2E test per supported path (prompt 10).
- Import/export round-trip: export → wipe → restore, with wrong
  passphrase failing cleanly (prompt 11).
- RDP POC: real connection to a compatible Windows endpoint (prompt 12).
- VNC POC: real connection to a configured Linux VNC server (prompt 13).

## Device / manual tests (prompt 14 checklist, NOT RUN until a device run)

- All destinations reachable; back navigation correct with keyboard
  open and with an active session.
- Portrait + landscape, small and large fonts, dark/light, no vertical
  or clipped essential text, touch targets, screen-reader labels.
- Background/foreground during a session and during a transfer.
- Keyboard: software keyboard, hardware/external keyboard, special keys
  (Ctrl, Esc, arrows, Tab) in the terminal.
- Storage Access Framework flows for local file pick/save.

## Security tests (prompts 04, 16)

- Static scan: no hardcoded secrets, no plaintext secret storage,
  no trust-all host-key verifier in production code, no secret in logs.
- Threat-model cases from THREAT_MODEL.md, one test or documented
  manual check per threat.
- Dependency licence audit: every dependency recorded with licence and
  maintenance evidence; no AI/cloud SDK in MVP.

## Regression (prompt 15)

- The full unit + integration matrix above, run together; a failure is
  fixed or explicitly recorded as a blocker — never silently waived.
- Every regression fix gets a test that would have caught it.

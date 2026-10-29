# Review #2 — remediation record

Source: `docs/REVIEW_2_REPORT.md` (findings F-01 .. F-24) and the
owner's `docs/REVIEW_2_VERIFICATION.md`. This file records what the
remediation commit changed. **Nothing here was compiled or run
locally** (the sandbox cannot reach Maven/Google repositories); CI is
the authority, and on-device verification is still open.

| Finding | Change |
|---|---|
| F-01 | `SshCryptoProvider` installs the bundled Bouncy Castle (appended, by reflection) in `Application.onCreate`; `CryptoProviderTest` probes X25519 / Ed25519 lookups on the emulator without a network. Root cause is still UNPROVEN until that test runs. |
| F-02, F-20 | `HostKeyFingerprint` hashes the SSH wire blob (matches `ssh-keygen -lf`); labels come from sshj `KeyType`. Test vector computed independently. Stored fingerprints from earlier builds will no longer match and need a one-time re-trust. |
| F-03, F-22 | `ControlChars`; refused in `shellQuote`, `CommandValidator`, POSIX/cmd working directory, `cd` at connect (re-validated), `%` for cmd. Previews and the paste dialog show control bytes visibly. |
| F-04 | Keystore `decrypt` never creates a key; creation/deletion share one lock; one shared instance. |
| F-05 | Previous connection/terminal closed on reconnect and on failure after authentication. |
| F-06 | Mapping uses `DisconnectReason`; new `SecureChannelFailed`; `SshDiagnostic` (enum + class names only) appended to the user-facing text. |
| F-07, F-08 | CSI moves use Long arithmetic; OSC ends on BEL or ST; CSI parameter length bounded; tab-stop precedence bug fixed. |
| F-09 | Risky classifier normalises quotes, path prefixes, wrappers, and splits on `&` / subshells (POSIX). |
| F-10 | Parser lock, volatile state, channel published before session, SSH window-change sent on resize. |
| F-11 | Upload writes `<name>.tbpart-*` then renames; `overwrite` is explicit; partial temp file removed on failure/cancel. |
| F-12 | Fixture: `LogLevel DEBUG1`, `127.0.0.1`, RSA + ECDSA host keys, public-key user + `publicKeyAuthSucceeds`. |
| F-14 | `actOnPrepared` re-derives risk from the rendered text. |
| F-15 | Import preview lists non-local forwards, proxy, jump hosts, working directory, buttons. |
| F-16, F-17 | Retry only when no test started; per-run secrets scrubbed from result files before upload. |
| F-18, F-19 | Misleading key wipe removed; export passphrase minimum 12 (import still accepts 8); Gradle wrapper checksum. |
| F-21, F-23, F-24 | SFTP lock / typed error mapping / comment fix; trivial assertion replaced; docs corrected; atomic `uiState` updates; download file-name validation. |

Not done: AAD binding of Keystore blobs (changes the stored format),
F-13 timeout behaviour (needs a device run to confirm), tier-2
integration stubs, release signing.

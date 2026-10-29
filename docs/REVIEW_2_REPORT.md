# TermBridge review of HEAD cd279db8 (brief of 2026-10-27)

Everything below is static review. I could not run the app or the tests. For sshj behaviour I read the `v0.40.0` tag source from GitHub, not the Maven jar, so I assume it matches the jar. Items marked **(assumption)** were inferred rather than read.

Two things were checked by experiment. I ran the fingerprint comparison in F-02 with OpenSSL and Python. I also ran Python regexes equivalent to the Kotlin ones for F-09. For F-09 the regex semantics are equivalent but it was not run in Kotlin.

Line numbers are per file.

## Critical

### F-01 — Likely client-side cause of the run #33 connect failure: sshj is pointed at Android's platform "BC" provider
- **Severity:** Critical if confirmed (no SSH connection would succeed on the CI image). The cause is **inferred, not proven**.
- **Location:** `TermBridgeApplication.kt` (whole file, no provider setup); `SshTransport.kt:67` and `:134`; `tests/fixture/setup-sshd-fixture.sh:70`.
- **Evidence:**
  - `val client = SSHClient()` uses `DefaultConfig`, and nothing in the app touches `java.security.Security`.
  - In sshj 0.40.0's `SecurityUtils.registerSecurityProvider` the bundled BC is added only when `Security.getProvider("BC") == null`. Then `setSecurityProvider("BC")` is called regardless.
  - Every later lookup is by name: `KeyAgreement.getInstance(alg, "BC")`, `KeyFactory.getInstance("Ed25519", "BC")`.
  - `Curve25519DH` uses `"X25519"` and `Ed25519KeyFactory.getPublicKey` uses `getKeyFactory("Ed25519")`.
  - The fixture offers only `HostKey .../ssh_host_ed25519_key`.
- **Why it matters:**
  - On Android a platform provider named "BC" already exists, so the library's own BC is never registered. The name "BC" then resolves to the platform's older, stripped copy.
  - **(assumption)** That copy lacks X25519 and the Ed25519 KeyFactory on API 29.
  - If so, the client fails inside key exchange or host-key decoding and drops the socket. The server logs at most a pre-auth close, which matches your sshd evidence.
  - The Ed25519-only fixture hits exactly the missing algorithm.
  - Other client-side causes I considered:
    - A host-key verifier returning `false` would surface as `HostKeyRejected`, not `NetworkError`, so it is unlikely.
    - Emulator networking works, because the server saw the connections.
- **Suggested fix:**
  - **Decisive test, no network needed.** Add an instrumented test that logs `Security.getProviders()` names and versions. It should then try `KeyAgreement.getInstance("X25519","BC")` and `KeyFactory.getInstance("Ed25519","BC")` and report only the exception class names.
  - If those fail, remove the platform "BC" and insert the bundled `BouncyCastleProvider` at position 1 before the first `SSHClient()`. Do this in `Application.onCreate`.
  - I checked `AndroidConfig` in the sshj source. It registers SpongyCastle and is not the fix.

### F-02 — Displayed and stored host-key fingerprint is not the OpenSSH fingerprint
- **Severity:** High (the security-critical TOFU verification step cannot be done out of band).
- **Location:** `HostKeyFingerprint.kt:21`; claims in its KDoc, `docs/PHASE_REPORT_05.md:21`, and test `SshTransportTest.kt:22-29`.
- **Evidence:**
  ```kotlin
  val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
  ```
  - The KDoc says "the same format OpenSSH prints, so a user can compare it against ... `ssh-keygen -lf`".
  - `publicKey.encoded` is the X.509 SubjectPublicKeyInfo DER. sshj's `Ed25519KeyFactory` even builds a 44-byte X.509 encoding.
  - OpenSSH hashes the SSH wire blob.
  - On a generated Ed25519 key:
    - SHA-256 of the SSH blob gave `SHA256:4UyFbLGt0HZIwAHq7efhtvKxKwuZ5fAFKziRoYmPh38`.
    - SHA-256 of the X.509 encoding gave `SHA256:6IoNdLKeSYsrT8WRj8/E9IlhC25or2WbL4Wwnh+CyG4`.
    - They are not equal.
  - The test only checks the `SHA256:` prefix, length 50 and no padding, so it cannot catch this.
- **Why it matters:**
  - The user is shown a value that never matches what `ssh-keygen -lf` prints on the server. The first-contact dialog is therefore unverifiable. In practice users will tap Accept blindly, which defeats TOFU.
  - Fingerprints are also incompatible with any future known_hosts import.
- **Suggested fix:**
  - Hash `Buffer.PlainBuffer().putPublicKey(key).compactData`. This is the same call sshj's own `getFingerprint` uses, but with SHA-256.
  - Add a test with a fixed OpenSSH key and its known `ssh-keygen -lf` output.
  - Have the CI wrapper pass the fixture's real fingerprint (the setup script already prints it) so the integration test compares against it.
  - Stored fingerprints will need a one-time re-trust after the change.

### F-03 — Control characters are typed into the PTY unfiltered, including a zero-interaction path (`cd` at connect)
- **Severity:** High, conditional. It needs attacker-influenced data: an imported backup, a pasted value, or a typed path.
- **Location:** `RemotePath.kt:18-19`; `CdCommand.kt:40`; `SessionViewModel.kt:329-330`; `CommandTemplate.kt:143` and `:172-174`; `BackupCodec.kt:581`.
- **Evidence:**
  ```kotlin
  if (!absolutePath.startsWith("/") || '\u0000' in absolutePath) return null   // only NUL rejected
  "cd " + CommandTemplate.shellQuote(directory, shell)
  terminal.sendText(CdCommand.render(workingDir, settings.remoteShell)); terminal.sendEnter()
  ```
  - Single quotes only stop the shell parser. The line discipline and readline interpret raw bytes first.
  - A working directory of `/tmp/<0x03>\n<cmd>\n` renders as `cd '/tmp/^C⏎<cmd>⏎'`. The ^C discards the line, and `<cmd>` then runs as a new line.
  - `WindowsPath.normalize` already rejects `< 0x20`, so the POSIX side is inconsistent.
  - The same applies to parameter values in `render()`. Nothing rejects control characters, and the preview cannot show them even though the code claims it is "exactly the text that will be sent".
- **Why it matters:**
  - The working-directory path runs on every connect, without the shell gate or the risky dialog.
  - The import path validates only via `validateRemoteDirectory`, which has the same hole.
- **Suggested fix:**
  - Reject any C0/C1 control character (and DEL) in `RemotePath.normalize`.
  - Reject them in `shellQuote` input, which fails closed for all shells.
  - Validate button templates, steps and defaults in `CommandValidator`.
  - Render control characters visibly in the preview.

## High / Medium

### F-04 — Keystore `decrypt()` can create a new key
- **Severity:** Medium to High.
- **Location:** `AndroidKeystoreSecretCrypto.kt:38-55`, `:71-76`; `TermBridgeApplication.kt` (two separate instances).
- **Evidence:** `decrypt()` calls `getOrCreateKey()`, which generates a key whenever `keyStore.getKey(...) as? SecretKey` is null.
- **Why it matters:**
  - Any null or transient Keystore miss during a read silently mints a fresh key under the same alias. After that, every existing blob is permanently undecryptable. This contradicts the `SecretCrypto` contract of "never silent re-encryption".
  - There is also no lock around creation. `hostRepository` and `environmentRepository` each hold their own `AndroidKeystoreSecretCrypto()`. Two first-use encryptions can race, and the later key overwrites the earlier one.
- **Suggested fix:**
  - `decrypt()` should only look up the key and throw if it is missing.
  - Only `encrypt()` creates it, under a process-wide lock, using one shared instance.

### F-05 — Authenticated SSH sessions leak on failure and on reconnect
- **Severity:** Medium.
- **Location:** `SessionViewModel.kt:227` (`connect()`), `:267` (`connection = conn`), `:357` (generic catch); `SessionScreen.kt:404`.
- **Evidence:**
  - After the transport authenticates, `connection = conn` is set. If `terminal.start()` then throws, the catch only updates UI state, with no `conn.disconnect()` or `terminal.close()`.
  - The Connect button calls `viewModel.connect(passphrase)` directly after the shell closes or fails. `connect()` never closes the previous `connection` or `terminalSession`.
  - `TerminalSession`'s own KDoc says closing the shell does not close the transport.
- **Why it matters:** An authenticated sshj client, socket and threads stay open after the UI reports FAILED. Each reconnect overwrites the reference and orphans the old session.
- **Suggested fix:** Call the teardown from `disconnect()` at the start of `connect()`, and in the catch blocks for any `connection` already assigned.

### F-06 — `SshErrorMapper` collapses all transport failures into "Network error"
- **Severity:** Medium (this is why F-01 is invisible).
- **Location:** `SshError.kt:35-39`, `:45`.
- **Evidence:**
  ```kotlin
  is TransportException -> if (e.message?.contains("host key", ignoreCase = true) == true) HostKeyRejected else NetworkError
  ```
- **Why it matters:**
  - Key-exchange failure, algorithm failure and protocol error all show the same fixed message.
  - Detecting host-key rejection by substring is fragile. sshj sets the reason in a typed field.
- **Suggested fix:**
  - Map on `(e as SSHException).disconnectReason`, for example `HOST_KEY_NOT_VERIFIABLE` and `KEY_EXCHANGE_FAILED`.
  - Add a fixed internal diagnostic code made only of the `DisconnectReason` enum name plus exception class simple names along the cause chain. Never include messages.
  - That resolves the cause-leak concern. Enum names and class names carry no server or user text, while the old `cause` field could have echoed it.
  - Show the code in Diagnostics only.

### F-07 — VT parser overflow lets remote output kill the session
- **Severity:** Medium.
- **Location:** `VtParser.kt:242-243`.
- **Evidence:**
  ```kotlin
  'B' -> cursorRow = (cursorRow + param(params, 0, 1)).coerceAtMost(rows - 1)
  'C' -> cursorCol = (cursorCol + param(params, 0, 1)).coerceAtMost(cols - 1)
  ```
- **Why it matters:**
  - `ESC[2147483647B` with `cursorRow >= 1` overflows to a negative Int. `coerceAtMost` does not rescue it, and the next `screen[cursorRow]` throws.
  - `TerminalSession`'s reader catches this as a generic `Exception` and reports "Connection lost". Any program or file that prints this sequence ends the session.
- **Suggested fix:** Clamp the parameter first (`coerceIn(0, rows)`), or use `coerceIn(0, rows - 1)` on a Long sum.

### F-08 — OSC strings terminated by ST are swallowed forever
- **Severity:** Medium.
- **Location:** `VtParser.kt:114-115`.
- **Evidence:**
  ```kotlin
  ParserState.OSC -> { if (ch == '\u0007') state = ParserState.GROUND }
  ```
- **Why it matters:**
  - Only BEL ends an OSC. The standard terminator `ESC \` (sent by many programs, for example for window titles) leaves the parser in OSC state. All later output is silently discarded until a BEL arrives.
  - Output can also be hidden deliberately.
  - The parser buffers nothing for OSC, so memory is not affected.
- **Suggested fix:** Handle ESC inside OSC (`ESC \` ends it, any other `ESC` restarts), and abort on CAN/SUB.

### F-09 — Risky-command heuristic does not match the quoted rendered text
- **Severity:** Medium.
- **Location:** `RiskyCommand.kt:83`, `:67`, `:91`, `:150`.
- **Evidence:**
  - The KDoc says the rendered text is classified. But `shellQuote` wraps every value in `'...'`, and the patterns anchor on unquoted tokens such as `\s(/|/etc|...)`.
  - Results from equivalent regexes:
    - `chmod -R 777 /etc` is flagged; `chmod -R 777 '/etc'` is not.
    - `systemctl stop sshd` is flagged; `systemctl stop 'sshd'` is not.
    - `rm '/etc'` is not flagged.
    - Single-argument `rm /` and `rm /etc` are not flagged either, because the pattern needs two whitespace gaps.
  - `splitSegments` does not split on single `&`, subshells or `$(...)`. `ls & rm -rf /`, `/bin/rm -rf /` and `(rm -rf /)` are all unflagged because the patterns are anchored with `^\s*rm`.
- **Why it matters:** Parameterised buttons, the main user-value path, lose most path-based warnings. The code documents obfuscation as a known gap, but quoting and `&` are ordinary syntax.
- **Suggested fix:** Strip one layer of single quotes from tokens before matching. Split on `&` and `(`, and allow an optional path prefix: `^\s*(?:\S*/)?rm`.

### F-10 — `TerminalSession` thread safety and the "no resize API" claim
- **Severity:** Medium.
- **Location:** `TerminalSession.kt:49`, `:51`, `:97`, `:116`, `:120`, `:142-143`, `:189-193`.
- **Evidence:**
  - `session`, `closed`, `closeNotified` and `writeChannel` are plain `var`s touched from the reader, writer and main threads.
  - `VtParser` has no synchronisation (no `@Synchronized` or `Mutex` anywhere). `parser.feed` runs on the reader thread while `parser.resize` and `parser.snapshot` run from the UI.
  - `session = sess` is set at line 97 but `writeChannel` is replaced at line 120, after `startShell()`. Keys queued in that window are dropped.
  - The comment at line 189 says sshj has no window-change API. `startShell()` returns `Session.Shell`, which has `changeWindowDimensions(cols, rows, w, h)`, and the return value is discarded at `:116`.
- **Why it matters:**
  - Concurrent grid mutation can throw or corrupt the screen.
  - The server-side PTY stays at 80×24, so vim, less and htop render wrongly after any resize.
- **Suggested fix:**
  - Guard parser access with a lock and mark the shared state `@Volatile`.
  - Create the channel before `session` is published.
  - Keep the `Shell` and call `changeWindowDimensions` from `resize()`, off the main thread.

### F-11 — SFTP upload truncates in place; the overwrite check uses a stale listing; partial remote files remain
- **Severity:** Medium.
- **Location:** `SftpManager.kt:183`; `FilesViewModel.kt:410`, `:449`.
- **Evidence:**
  ```kotlin
  setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
  if (_uiState.value.entries.any { it.name == safeName }) { ... conflict dialog ... }
  ```
- **Why it matters:**
  - The conflict check reads the cached listing, so a file created after the last refresh is silently truncated.
  - On "overwrite", a failed or cancelled transfer leaves the old file destroyed and the new one partial, with no cleanup.
  - Downloads clean up their partial local file; uploads do not.
  - `RemotePath.join(...) ?: return` in `startUpload` returns silently with no message if the SAF display name contains `/`.
- **Suggested fix:**
  - Upload to a temporary name in the same directory, then rename on success, preferably with `stat` immediately before.
  - Delete the temp file on failure or cancel.
  - Surface an error when `join` returns null.

### F-12 — The fixture hides a real-auth gap and is thin on diagnostics
- **Severity:** Medium.
- **Location:** `setup-sshd-fixture.sh:68`, `:70`, `:72`, `:75`.
- **Evidence:** `PubkeyAuthentication no`, `StrictModes no`, `ListenAddress 0.0.0.0`, one `HostKey` (ed25519) and no `LogLevel`.
- **Why it matters:**
  - Key auth (the C-01 fix) can never be exercised, so the integration tests cannot prove it.
  - A single Ed25519 host key means one algorithm failure takes out every test, which is what F-01 shows.
  - At the default log level, key-exchange and algorithm errors are not recorded, which is why the server log was uninformative.
  - `0.0.0.0` exposes a password-auth server on the runner. The script comment says "loopback-reachable", but the emulator reaches `10.0.2.2` via loopback, so `127.0.0.1` suffices.
- **Suggested fix:** Add `LogLevel DEBUG1`, `ListenAddress 127.0.0.1`, an RSA and an ECDSA host key, and a second user with `PubkeyAuthentication yes` and a generated key. Keep `StrictModes` default for that user.

## Low

### F-13 — Trust dialog versus sshj timeouts (assumption)
- **Location:** `SshTransport.kt:229-230`, `:242`, `:247`; `SessionViewModel.kt:199`, `:205`.
- **Evidence:** The verifier blocks its thread with `runBlocking { decisionHandler.decideUnknown(...) }` while `setConnectTimeout`/`setTimeout` are 15 s and 30 s.
- **Why it matters:**
  - **(assumption)** If sshj applies its kex timeout while the verifier waits, a slow user makes the connect fail with a timeout. The blocked thread stays parked until `answerTrust` or `cancel`.
  - The catch block never clears `pendingTrust`, so a stale dialog can remain visible after FAILED.
- **Suggested fix:** Clear `pendingTrust` in the catch, and verify whether the kex timeout covers the verifier callback.

### F-14 — `actOnPrepared` trusts the caller-supplied `isRisky` flag
- **Location:** `SessionViewModel.kt:474`.
- **Evidence:** `if (prepared.isRisky && !confirmedRisky)`.
- **Why it matters:** `PreparedCommand` is a public data class. Unlike the shell gate, the risky check is not re-derived at the execution boundary, so the "bypassing the dialog cannot bypass the gate" claim covers only the shell gate.
- **Suggested fix:** Re-run `RiskyCommand.classify` on `rendered` / `renderedSteps` in `actOnPrepared`.

### F-15 — Import preview shows counts only
- **Location:** `BackupService.kt` (preview builder); `BackupScreen.kt` and `BackupViewModel.kt` (no non-loopback warning).
- **Why it matters:** An imported file can create tunnels with `allowNonLoopback = true`, a SOCKS proxy host or a working directory. All three pass validation without being flagged. I did not verify whether imported tunnels auto-start.
- **Suggested fix:** Add preview warnings for non-loopback binds, proxies and working directories.

### F-16 — CI wrapper retry comment overclaims
- **Location:** `run-instrumented-with-fixture.sh:98-101` vs `:118`.
- **Evidence:**
  - The comment says "A run that actually executes tests (pass or fail) is never retried".
  - The code is only `grep -q -e "Unknown API Level" -e "PropertyFetcher" -e "Cannot install split APKs"` over the whole attempt log.
- **Why it matters:** A test failure whose log happens to contain one of these strings is retried and may pass on retry, masking a real failure. Failures still end with the last attempt's exit code.
- **Suggested fix:** Retry only if the log contains no `Starting ... tests` marker, or check the XML results directory.

### F-17 — Fixture password in Gradle `-P` arguments (assumption)
- **Location:** `run-instrumented-with-fixture.sh:113`.
- **Why it matters:** **(assumption)** The password then appears on process command lines and likely in the uploaded `androidTest-results` artifact. Impact is low because the password is per-run and the server dies with the runner. It contradicts the "never logged" claim.
- **Suggested fix:** Check the uploaded artifact for `fixture_password`, and scrub it or restrict the artifact if present.

### F-18 — `ExportCrypto` wipes a clone, not the key
- **Location:** `ExportCrypto.kt:83`, `:103`.
- **Evidence:** `key.encoded?.wipe()`. `SecretKeySpec.getEncoded()` returns a copy, so the real key bytes are not wiped, and an extra copy is allocated.
- **Suggested fix:** Remove the pretence, or use a destroyable key implementation.

### F-19 — Posture items
- **Locations:** `BackupCodec.kt:148`, `:199`; `AndroidKeystoreSecretCrypto.kt:57-69`; `gradle-wrapper.properties`.
- **Evidence:**
  - Minimum backup passphrase is 8 characters, so an exported file is brute-forceable offline.
  - Keystore blobs carry no AAD, so a ciphertext cannot be bound to its row or field.
  - The wrapper has no `distributionSha256Sum`.
- **Suggested fix:** Raise the passphrase minimum or add a strength hint; bind blobs to record identity via `updateAAD`; add the checksum.

### F-20 — Host-key type labels are wrong
- **Location:** `HostKeyFingerprint.kt:28-29`.
- **Evidence:** `"EC" -> "ssh-ecdsa"` (not a real SSH type name); only `"EdDSA"` maps to `ssh-ed25519`.
- **Why it matters:** sshj's `KeyType.ED25519.isMyType` accepts both `"EdDSA"` and `"Ed25519"`, so BC-backed keys may fall through to the raw algorithm string. **(assumption)** BC reports `"Ed25519"`. Display only.
- **Suggested fix:** Use `KeyType.fromKey(key).toString()`, which returns the real wire names.

### F-21 — SFTP code-quality items
- **Location:** `SftpManager.kt:18`, `:57-67`, `:227-232`.
- **Evidence:**
  - The KDoc "Remote size in bytes at listing time; -1 when unknown." sits above `isRegularFile` instead of `size`.
  - `ensureSftp` is not synchronised, so concurrent list and transfer can create two `SFTPClient`s.
  - `mapError` classifies by `"permission" in message.lowercase()`. Server-supplied paths and messages can produce the wrong category.
- **Suggested fix:** Move the comment, add a lock, and map on `SFTPException.statusCode`.

### F-22 — cmd.exe `cd` and `%` expansion
- **Location:** `CdCommand.kt:41`.
- **Evidence:** `cd /d "$directory"`. `WindowsPath` bans `"` but not `%`, and cmd expands `%VAR%` inside double quotes.
- **Suggested fix:** Reject `%` and `!` in cmd working directories.

### F-23 — Meaningless assertions and doc contradictions
- **Location:** `SshIntegrationTest.kt:149`; `SshTransportTest.kt:22`.
- **Evidence:**
  - `assertFalse(e.error.userMessage.contains("wrong-password-12345"))` is trivially true because the messages are constants.
  - `fingerprintFormatIsOpenSshSha256` does not compare against any OpenSSH value (see F-02).
  - `docs/CAPABILITY_MATRIX.md` FR-02 and FR-03 still say `SshIntegrationTest @Ignore'd`. The file has 13 active tests and 6 ignored.
  - `docs/RELEASE_READINESS.md` says 12 tier-1 tests and 282 unit tests. The code has 13 and 283.
- **Suggested fix:** Delete or replace the redundant assertion, and fix the docs.

### F-24 — Smaller items
- **Lost updates on `_uiState.value = _uiState.value.copy(...)` from several threads.** One example is `awaitDecision` running on the sshj thread (`SessionViewModel.kt:199`). A lost update to `pendingTrust` would leave the verifier blocked with no dialog until Cancel. Use `update {}`.
- **Paste dialog** shows raw text with invisible control characters (`SessionScreen.kt`, `paste_dialog_body`). **(assumption)** Control characters are not rendered visibly.
- **Download local name.** `entry.name` is a server-supplied string used as a SAF display name (`FilesViewModel.kt`, `downloadToLocalFolder`). It is not validated with `RemotePath.isValidName`. Provider sanitisation behaviour is an **(assumption)**.

## Checked and found clean

- **Host-key persistence order:** trust is persisted only after authentication succeeds. There is no accept-all path, and a missing decision is a rejection (`SshTransport.kt`, `HostKeyVerificationPolicy.kt`).
- **Shell gate logic:**
  - `CommandTemplate.blockReason` and the placement of the gate in `actOnPrepared` (before the risky dialog) are correct.
  - The gap is only in the risky flag (F-14).
  - `CommandRunner` mode separation holds: insert never sends Enter.
  - Sequence cancel is checked between steps.
- **Keystore IV handling:** a fresh IV from the Keystore per encryption, and the `IV || ciphertext` layout is consistent in `decrypt`. The problems are key creation on read (F-04) and the lack of AAD (F-19).
- **Backup crypto construction:** PBKDF2-HMAC-SHA256 at 600k, per-export random salt and IV, GCM tag, and constants used on decrypt. The error does not distinguish a wrong passphrase from corruption. Plaintext credentials in a non-encrypted file are rejected by design according to comments (`BackupFormat.kt`); I did not trace that code. Size and count caps exist.
- **SFTP path handling:** `RemotePath` clamps `..` at root and `join` rejects `/` and `..`. Directory delete uses `rmdir`. Transfers stream and check size. Download cleans its partial local file.
- **Manifest and platform hardening:** `allowBackup=false`, cleartext off, only INTERNET and ACCESS_NETWORK_STATE, and `FLAG_SECURE` on the single activity.
- **Secret scrubbing:** `SecretRedactor` and `SecretScanner` behave as documented, including withholding whole messages.
- **Cause-dropping design:** reviewed on its merits. It is sound, and F-06 achieves diagnosability without reintroducing messages.
- **sshj key loading:** `loadKeys(pem, null, finder)` is used with the content overload as documented in review #1. I did not re-verify it against the jar.

**Not examined in depth, so not claimed clean:**
- Room migrations and `MigrationSqlAuditTest`
- `HostRepository` and the other repositories
- `TunnelManager` and `SocksSocketFactory`
- `BackupCodec` internals beyond the validation hooks
- most Compose screens
- `RfbConnection` and the rest of the RFB core (out of scope)

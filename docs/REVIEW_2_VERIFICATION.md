# Review #2 verification (HEAD cd279db8) — 2026-10-28

Source reviewed: `~/workspace/user/files/TermBridge_Review_cd279db8_1_8121.md`
(24 findings, F-01..F-24, plus clean claims). Verified against the local
tree (== HEAD cd279db8; no local changes since that push) and, where
stated, against sshj 0.40.0 bytecode from `~/workspace/tools/sshj-0.40.0.jar`
(SHA-256 a6a5533f…) — not against the reviewer's GitHub-tag reading.

## Verdict tally

- **22 CONFIRMED** in full or in substance (F-02..F-12, F-14..F-24
  except as noted below; F-17 upgraded from assumption to confirmed).
- **2 PARTIAL** (F-01, F-13): the mechanism/observation stands, the
  headline causal claim is unproven or partly rejected.
- **0 fully REJECTED** as a finding. One sub-claim inside F-13 is
  rejected (stale dialog); one sub-claim inside F-20 is unproven.
- **Nothing fabricated.** Several line numbers are approximate and
  two citation paths differ (see notes); substance is unaffected.

## Per-finding verification

### F-01 — BC provider collision as run #33 cause: PARTIAL
- CONFIRMED mechanism, at bytecode level. App: no `Security` provider
  setup anywhere; `SshTransport.kt:67,134,169` use no-arg `SSHClient()`
  (DefaultConfig); no SpongyCastle dependency in the app (only sshj).
  sshj `SecurityUtils.registerSecurityProvider`: instantiates the
  bundled `BouncyCastleProvider` (name "BC"); `Security.addProvider`
  is skipped when a provider of that name already exists, but the
  MD5/DH capability test is run against the bundled provider object
  and, on success, `setSecurityProvider("BC")` adopts the NAME.
  Later lookups (`getKeyAgreement`, `getKeyFactory`) resolve by that
  name, i.e. to the platform "BC" first. `Curve25519DH` uses
  "X25519"; `Ed25519KeyFactory` uses `getKeyFactory("Ed25519")`.
  Fixture offers only an Ed25519 host key (`setup-sshd-fixture.sh`,
  `HostKey .../ssh_host_ed25519_key`). `AndroidConfig` registers
  SpongyCastle ("SC") but the app does not use it, and without a
  SpongyCastle dependency it could not register anyway — so the
  reviewer's "AndroidConfig is not the fix" is consistent.
- UNPROVEN as the run #33 cause: the specific claim that the API-29
  platform "BC" lacks X25519 and the Ed25519 KeyFactory is marked
  (assumption) by the reviewer and cannot be proven from this
  sandbox (no API-29 provider to query). Pre-auth closes are
  consistent with F-01 but do not prove it — any pre-auth client
  drop looks the same in sshd.log. The proposed decisive test
  (instrumented test logging provider names/versions and trying
  `KeyAgreement.getInstance("X25519","BC")` /
  `KeyFactory.getInstance("Ed25519","BC")`, reporting only exception
  class names) is correct and is the required next step; the
  provider-insert fix is CONDITIONAL on that test failing.
- Precision note: `setSecurityProvider("BC")` is not literally
  "called regardless" — it runs only when the securityProvider field
  is still null and the MD5/DH test passes. On first registration
  both hold, so the practical effect is as the reviewer states.

### F-02 — Fingerprint is not the OpenSSH fingerprint: CONFIRMED
- Code: `HostKeyFingerprint.compute` hashes `publicKey.encoded`
  (X.509 SubjectPublicKeyInfo DER); KDoc claims identity with
  `ssh-keygen -lf` output. Independently reproduced here with a
  fresh Ed25519 key: `ssh-keygen -lf` fingerprint == SHA-256 of the
  SSH wire blob, and != SHA-256 of the X.509 DER encoding.
- Fix direction confirmed by jar bytecode: sshj's own
  `SecurityUtils.getFingerprint` hashes
  `Buffer.PlainBuffer().putPublicKey(key).getCompactData()` (with
  MD5, hex) — the reviewer's suggested SHA-256 variant of exactly
  that input is the right fix. Test `fingerprintFormatIsOpenSshSha256`
  checks only prefix/length/no-padding, so it cannot catch this.
  Stored fingerprints will need a one-time re-trust after the fix.

### F-03 — Control characters into the PTY: CONFIRMED in substance
- `transport/RemotePath.normalize` rejects only NUL (absolute +
  no-NUL); `WindowsPath.normalize` rejects `< 0x20` — the POSIX/
  Windows inconsistency is real. `CdCommand.render` (POSIX/
  PowerShell: `shellQuote`; CMD: double quotes) + `SessionViewModel`
  cd + Enter on every connect, and `EnvironmentValidator.
  validateRemoteDirectory` for POSIX delegates to
  `RemotePath.normalize`, so the import path (`BackupCodec.kt:581`)
  carries the same hole. `shellQuote` only quotes; it never rejects
  controls. Overlap: this is review #1 Stage 2 item H-01 (pending,
  not yet applied) with new detail (import path, preview
  invisibility) — not a new defect in full.

### F-04 — Keystore decrypt() can create a key: CONFIRMED
- `AndroidKeystoreSecretCrypto.decrypt` calls `getOrCreateKey()`,
  which generates under the same alias when `getKey` returns null.
  `SecretCrypto`'s contract says decrypt "throws on a lost/
  invalidated key" and "never as a silent re-encryption".
  Two instances exist (`TermBridgeApplication`: hostRepository and
  environmentRepository each construct one); no lock in
  `getOrCreateKey`.

### F-05 — Authenticated sessions leak on failure/reconnect: CONFIRMED
- In `SessionViewModel.connect`, after `connection = conn` the
  `SshTransportException`/generic catches only set FAILED state;
  no `conn.disconnect()` and no clearing of `connection` on the
  `terminal.start()` failure path (the non-sshj branch does
  disconnect, the throw path does not). `connect()` cancels only
  `connectJob`; it never calls `disconnect()` first, so a reconnect
  overwrites `connection`/`terminalSession` and orphans the old
  session. `SessionScreen` Connect calls `connect()` directly.

### F-06 — Error mapper collapses transport failures: CONFIRMED
- `SshError.kt` mapper is exactly as quoted (substring "host key"
  check, else NetworkError). Jar: `SSHException.getDisconnectReason()`
  exists and `DisconnectReason` (package `common`, not `transport`
  as cited) includes `KEY_EXCHANGE_FAILED` and
  `HOST_KEY_NOT_VERIFIABLE`. The suggested diagnostic code (enum
  name + exception class simple names, no messages, Diagnostics
  only) is compatible with the cause-dropping design and resolves
  the F-01 invisibility without reintroducing message leakage.

### F-07 — VT parser overflow: CONFIRMED
- `VtParser.parseParams` uses `toIntOrNull() ?: 0`, so
  `ESC[2147483647B` parses to Int.MAX_VALUE; `cursorRow + param`
  wraps to negative in Kotlin Int arithmetic, `coerceAtMost`
  does not rescue a negative, and the next `screen[cursorRow]`
  access throws. Same for 'C'/cols. 'A'/'D' are rescued by
  `coerceAtLeast(0)`. `TerminalSession`'s reader catch reports
  this as "Connection lost".

### F-08 — OSC terminated only by BEL: CONFIRMED
- `VtParser.feed` OSC branch exits only on `\u0007`; ESC inside
  OSC is ignored, so `ESC \` never terminates it and later output
  is discarded. No OSC buffer exists, so no memory effect — as
  the reviewer states.

### F-09 — Risky heuristic vs quoted rendered text: CONFIRMED
- `splitSegments` splits only on `;`, newline, `&&`, `||`, `|` —
  not single `&`, `(`, or `$(...)`. Segment patterns are anchored
  (`^\s*rm`, `^\s*chmod`, ...), and rendered parameter values are
  single-quoted by `shellQuote`, so quoted paths systematically
  miss the path patterns (this is the app's own quoting, not
  exotic obfuscation, and parameterised buttons are the main
  user-value path). The reviewer's examples check out against the
  actual patterns, including single-arg `rm /` / `rm /etc`
  unflagged (no second whitespace gap for the system-path pattern,
  no `-rf` for the recursive pattern) and `ls & rm -rf /`,
  `/bin/rm -rf /`, `(rm -rf /)` unflagged by anchoring.
  Line numbers cited are approximate (patterns ~67-91,
  `splitSegments` ~150 in this tree); substance unaffected.

### F-10 — TerminalSession thread safety + resize claim: CONFIRMED
- `session`, `closed`, `closeNotified`, `writeChannel` are plain
  vars; no `@Volatile`, `@Synchronized`, or `Mutex` in
  `TerminalSession`/`VtParser`; `parser.feed` runs on the reader
  thread while `parser.resize`/`snapshot` run from the UI.
  `session = sess` is set before `startShell()` and `writeChannel`
  is replaced after it, so keys sent in that window go to a
  channel with no consumer and are dropped. Resize: the KDoc
  claim that sshj has no window-change API is wrong — jar
  `Session$Shell` (package `connection.channel.direct`) declares
  `changeWindowDimensions(int,int,int,int)`, and `startShell()`'s
  return value is discarded. DEDUPE: the resize part repeats
  review #1 H-04 (already confirmed, not fixed in Stage 1);
  the thread-safety/channel-ordering parts are new.

### F-11 — SFTP upload truncates in place: CONFIRMED
- `SftpManager.upload` opens with `WRITE, CREAT, TRUNC` and
  streams in place; no temp-file + rename, no cleanup on failure/
  cancel (downloads do clean their partial local file, confirmed
  in `FilesViewModel`). Conflict check in `onUploadPicked` reads
  the cached `_uiState.entries`, so a file created after the last
  refresh is silently truncated. `startUpload`'s
  `RemotePath.join(...) ?: return` is silent with no message.

### F-12 — Fixture hides a real-auth gap: CONFIRMED in substance
- Fixture config confirmed: `PubkeyAuthentication no`,
  `StrictModes no`, `ListenAddress 0.0.0.0`, single Ed25519 host
  key, no `LogLevel` line. DEDUPE: the key-auth gap repeats
  review #1 H-09's fixture part (already known; Stage 1 item 5,
  fixture key-auth test, still open). New and sound: `LogLevel
  DEBUG1` (default level proved uninformative in run #33 — only
  pre-auth closes), `127.0.0.1` listen address, RSA + ECDSA host
  keys, a second user with key auth enabled.

### F-13 — Trust dialog vs sshj timeouts: PARTIAL
- UNPROVEN: the kex-timeout-covers-verifier assumption. No
  watchdog was found in the checked path, and a socket timeout
  cannot fire on a thread parked in `runBlocking` on the verifier
  callback (no socket read is pending there).
- REJECTED (sub-claim): "catch never clears pendingTrust, stale
  dialog after FAILED". `awaitDecision`'s `finally` clears
  `pendingTrust` when it exits, and both `cancel()` and
  `disconnect()` complete the deferred with false, which unblocks
  it. For every answered/cancelled path the dialog state is
  cleared. The remaining true observation — the verifier thread
  stays parked until answer/cancel — is not by itself a defect,
  since those two actions unblock it.

### F-14 — actOnPrepared trusts caller isRisky: CONFIRMED (gap)
- `actOnPrepared` re-derives the shell gate (`blockReason`) but
  uses `prepared.isRisky` as-is. Nuance: in production the only
  producer is `CommandTemplate.prepare`, which does classify via
  `RiskyCommand` at preview time — so this is a defence-in-depth
  gap (a hand-constructed `PreparedCommand` could bypass it),
  not a UI-bypassable hole. The reviewer's "bypassing the dialog
  cannot bypass the gate" wording covers only the shell gate, as
  stated.

### F-15 — Import preview shows counts only: CONFIRMED in substance
- `ImportPreview` exposes counts + conflicts + decode warnings
  only; no preview warning is generated for non-loopback binds,
  SOCKS proxies, or working directories (decode warnings cover
  jump-hop drops only). UNPROVEN sub-point: whether imported
  tunnels auto-start — tunnel start is manual
  (`AdvancedViewModel.startTunnels`); no auto-start on import was
  found.

### F-16 — CI wrapper retry comment overclaims: CONFIRMED
- Comment claims a run that executes tests is never retried; code
  only greps the whole attempt log for the three signatures, with
  no "tests started" check. In run #33 this did not misfire
  (attempts 1-2 were infra signatures, attempt 3 executed tests
  and was not retried), but the code does not guarantee the
  comment's claim.

### F-17 — Fixture password in Gradle -P arguments: CONFIRMED (upgraded)
- Wrapper passes `-P...fixture_password="$FIXTURE_PASSWORD"`.
  The artifact channel is PROVEN, not merely assumed: the run #32
  instrumented-test-reports artifact's `utp.0.log` records the
  fixture password among the instrumentation arguments (value
  never quoted anywhere, per standing rule). The wrapper itself
  does not echo it, but UTP logs it into the uploaded artifact,
  so the "never logged" claim fails in effect. Impact LOW:
  single-use password, runner dies with the run, private repo,
  artifact access restricted to repo collaborators.

### F-18 — ExportCrypto wipes a clone: CONFIRMED
- `deriveKey` returns `SecretKeySpec`; `key.encoded?.wipe()` wipes
  the copy `SecretKeySpec.getEncoded()` returns, not the key's
  bytes, and allocates an extra copy. Low severity (in-memory
  only).

### F-19 — Posture items: CONFIRMED
- `MIN_EXPORT_PASSPHRASE_LENGTH = 8` (`BackupFormat.kt:39`,
  enforced in `BackupCodec` encode/decode). Keystore blobs use
  `GCMParameterSpec` with no `updateAAD` anywhere in
  `AndroidKeystoreSecretCrypto`. `gradle-wrapper.properties`
  has no `distributionSha256Sum` (it does set
  `validateDistributionUrl=true`).

### F-20 — Host-key type labels wrong: CONFIRMED (main claim)
- `HostKeyFingerprint.sshKeyType` maps "EC" to "ssh-ecdsa", which
  is not a real SSH wire name (jar `KeyType` names are
  `ssh-rsa`, `ssh-dss`, `ssh-ed25519`, plus cert variants).
  UNPROVEN sub-point: whether a BC-backed key reports "Ed25519"
  (sshj's `Ed25519KeyFactory` uses "Ed25519" as its algorithm
  name, which makes the fall-through plausible, but the platform
  key's `getAlgorithm` return was not observed). Display only.

### F-21 — SFTP code-quality items: CONFIRMED
- The "Remote size in bytes" KDoc sits above `isRegularFile`
  instead of `size` in `RemoteEntry`. `ensureSftp` is not
  synchronised (check-then-act on `sftp`). `mapError` classifies
  by substring on the message, including server-supplied text.

### F-22 — cmd.exe cd and % expansion: CONFIRMED
- `CdCommand` CMD branch uses double quotes; `WindowsPath`
  bans `"` but not `%` (illegal set is `" < > | ? *` plus
  `< 0x20`). cmd expands `%VAR%` inside double quotes.

### F-23 — Meaningless assertions and doc contradictions: CONFIRMED
- `SshIntegrationTest` wrong-credentials assertion
  `assertFalse(userMessage.contains("wrong-password-12345"))`
  is trivially true given fixed messages (the `assertEquals
  (AuthFailed)` above it is the real assertion).
- `fingerprintFormatIsOpenSshSha256` compares against no OpenSSH
  value — same root defect as F-02, not a separate defect.
- `CAPABILITY_MATRIX.md` FR-02/FR-03 (and sibling rows) still say
  `SshIntegrationTest @Ignore'd`; the class has 13 active tests
  and 6 ignored. `RELEASE_READINESS.md` says 12 tier-1 tests and
  282 unit tests; the code has 13 and 283. Note: these are
  Phase 8 [PENDING] drafts, so fixing them belongs to Phase 8
  in any case.

### F-24 — Smaller items: CONFIRMED in substance
- Lost updates: `_uiState.value = _uiState.value.copy(...)`
  read-modify-write runs from main, IO, and the sshj verifier
  thread (`awaitDecision` via `runBlocking`); no `update {}`.
- Download local name: `downloadToLocalFolder` passes
  server-supplied `entry.name` to `createDocument` with no
  `RemotePath.isValidName` check; provider sanitisation behaviour
  is an assumption (as the reviewer marked), and a failure
  surfaces as an error message, limiting impact.
- Paste dialog invisibility: assumption as marked (Compose
  `Text` rendering of control characters was not proven either
  way here).

## Clean claims — spot-checked, consistent
- Trust persisted only after authentication succeeds
  (`SshTransport` pending-trust write after auth); no accept-all
  path; shell gate placed before the risky dialog in
  `actOnPrepared`; `CommandRunner` insert sends no Enter;
  Keystore fresh IV per encryption with `IV || ciphertext`
  layout; backup PBKDF2 600k with random salt/IV and GCM tag;
  `RemotePath` clamps `..` at root and `join` rejects `/`/`..`;
  directory delete uses `rmdir`; manifest (INTERNET +
  ACCESS_NETWORK_STATE only, `allowBackup=false`, cleartext
  off); cause-dropping design is sound and F-06 is compatible
  with it. These were consistent with prior phase verification
  and the spot checks done in this round; the reviewer did not
  re-verify sshj key loading against the jar, and neither did
  this round beyond the review #1 record.

## Not examined by the reviewer (carried forward)
- Room migrations, repositories, TunnelManager/SOCKS internals,
  BackupCodec internals beyond validation hooks, most Compose
  screens, RFB core (out of scope).

## Status of this record
- Written locally only (2026-10-28). NOT pushed. It belongs in the
  next fix batch's push, together with whatever fixes are approved.
- Release decision unchanged: NO-GO (instrumented proof, tier-2
  tests, device run, signing still open; F-01 decisive test now
  the highest-priority open question).

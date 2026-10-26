# Verification of external review #1 (TermBridge_Source_Review_1_pk5f.md)

Reviewer reviewed the refreshed source ZIP (252 files, 2026-10-25).
Each finding below was checked line-by-line against the local tree
(which matches the ZIP except the workflow file — see H-09) and, for
C-01/H-04/H-08, against sshj 0.40.0 bytecode via `javap`.

Verdict key: CONFIRMED = reproduced in code (and library bytecode where
stated); PARTIAL = core proven, a sub-detail unverified; STALE = true
for the ZIP but not for live repo HEAD; REJECTED = refuted with evidence.

## Critical

| ID | Verdict | Evidence / note |
|---|---|---|
| C-01 key auth broken | CONFIRMED | `SshTransport.kt`: `client.loadKeys(pem, passphraseText)` where pem is PEM *text*. Bytecode: `loadKeys(String,String)` = `loadKeys(location, passphrase.toCharArray())` — location-based, and NPEs on null passphrase. Fix path verified: 3-arg `loadKeys(String,String,PasswordFinder)` sniffs content (`detectKeyFileFormat` looks for `-----BEGIN`/`PRIVATE KEY-----` in the string). |
| C-02 main-thread network I/O | CONFIRMED | `TerminalSession.sendBytes` writes the socket stream directly with `catch (_: Exception)` swallowing all; `SessionViewModel.sendText/sendEnter/sendCtrlC/sendBackspace`, `disconnect()`, `onCleared()` call it with no dispatcher; Compose handlers run on Main. Android BlockGuard makes NetworkOnMainThreadException near-certain for socket writes on Main. Device run remains the final word, but the code path is unambiguous. |
| C-03 Backspace/hardware keys | CONFIRMED | `SessionScreen.kt`: input field resets `input = ""` after every change, so the `new.length < input.length` backspace branch can never fire; no `onKeyEvent`/`onPreviewKeyEvent` anywhere in the file; no Backspace button (only Paste + Keyboard); `keyboardOptions` sets only `imeAction = Send` (no autoCorrect off, no Password type). |

## High

| ID | Verdict | Evidence / note |
|---|---|---|
| H-01 control-char injection | CONFIRMED | `CommandValidator` has no control-char check (no `0x20`/`0x7F`/ISO-control test); `CommandRunner.insert` sends `rendered` raw; `RemotePath.normalize` rejects only NUL; `SessionViewModel:325-327` auto-sends `cd '<dir>'` + Enter on connect from a stored dir. A crafted backup dir containing `^C` + `\n` breaks out of the quote on connect. Breaks "INSERT_ONLY never sends Enter" and "import never executes". |
| H-02 PowerShell smart quotes | CONFIRMED | `CommandTemplate.shellQuote` POWERSHELL branch doubles only ASCII `'`; `CdCommand.render` reuses it. PowerShell's tokenizer treats U+2018/U+2019/U+201A/U+201B as string delimiters, and Android keyboards emit `’` by default. |
| H-03 terminal/SFTP ignore chain+proxy | CONFIRMED | `SessionViewModel:258` and `FilesViewModel:192` build `SshConnectionRequest` without `proxy` (default NONE); `connectChain` is used only by `AdvancedViewModel:353` (tunnels); `TerminalSession`/`SftpManager` accept only `SshjConnection`. README's "Advanced SSH" bullet lists chains/SOCKS without scoping them to tunnels. |
| H-04 PTY size fixed, docs wrong | CONFIRMED | `TerminalSession.resize` comment claims no sshj window API; javap proves `Session$Shell.changeWindowDimensions(int,int,int,int)` exists in 0.40.0; `startShell()` result is discarded (`:101`); `SessionViewModel:280` hardcodes 80×24; `SessionScreen:552` computes cols as `maxWidth/8f`; `TERMINAL_LIMITS.md` repeats the false claim. |
| H-05 VT parser gaps | CONFIRMED except tab | `escape()` has no `(`/`)` branches (charset char prints); CSI `0x20–0x2F` intermediates fall to `else → GROUND` (`ESC [ 2 SP q` prints `q`); OSC ends only on BEL, not ST; `executeCsi` handles only A/B/C/D/H/f/d/G/J/K/m/s/u (no r/L/M/@/P/X/S/T/q); `applySgr` has no 7/2/3/4 and consumes-and-ignores 38/48 while TERM is `xterm-256color`; `parseParams` maps colon params (`4:3`) to 0 → full reset; no DSR/DA response; cells are single `Char` (surrogate pairs = 2 cells, no RTL shaping). REJECTED sub-claim: the tab `coerceAtMost` does NOT bind to `8` — Kotlin leading-dot continuation applies it to the whole `((col/8)+1)*8` expression. |
| H-06 VtParser race/shared snapshot | CONFIRMED (structural) | `snapshot()` puts the parser's own mutable row lists into `TerminalSnapshot` (both are data classes, so StateFlow dedupe sees in-place mutations as no-change); `feed` runs on `Dispatchers.IO` reader, `resize` from UI, no `synchronized`/`Mutex` anywhere in parser/session. Manifestation needs a stress/device run. |
| H-07 sequence blind input | CONFIRMED | `CommandRunner.executeSequence`: fixed 1000 ms delay, no prompt detection, no stop-on-error; a failed `cd` step leaves later steps running in the wrong directory. |
| H-08 SFTP rmdir | CONFIRMED | `SftpManager.delete` calls only `rm()`; its comment ("succeeds for empty dirs server-side") is wrong — OpenSSH returns Failure for REMOVE on a directory. javap proves `SFTPClient.rmdir(String)` exists. No `rmdir` call anywhere in main (only a RiskyCommand regex mentions it). |
| H-09 CI/fixture | STALE for live repo | TRUE for the ZIP/local tree: local `.github/workflows/android-ci.yml:76` still runs plain `connectedDebugAndroidTest` with the "@Ignore'd (no fixture yet)" comment. But live repo HEAD (5175329, verified via API read) runs `tests/fixture/run-instrumented-with-fixture.sh`. The local tree never received the browser-route workflow commits. Action: sync the workflow file into the local tree; the fixture part (PubkeyAuthentication no, line 72) is still true and key auth is still untested. CI API-29-only vs minSdk 26 also true; actions pinned to tags, not SHAs, also true. |

## Medium — all CONFIRMED (notes where hedged)

- M-01: no quoted-placeholder check in validator; `echo '{{x}}'` + `; rm -rf ~; ` injects. CONFIRMED.
- M-02: `splitSegments` splits on `;`/`\n`/`&&`/`||`/`|` but not lone `&`; `^\s*`-anchored patterns miss `if/for/(/{/$(//bin//\rm/nohup/xargs/find -delete/bash -c` wrappers. Docs already call it heuristic; the bypasses are trivial. CONFIRMED.
- M-03: `password\s*=\s*\S+` and `--password\s+\S+` match `password={{pw}}` / `--password {{pw}}` (safe placeholder use blocked); `-p`, `mysql -pSECRET`, `curl -u`, URLs, Basic auth undetected. CONFIRMED.
- M-04: `BackupCodec:647-658` + `BackupService:380,452` carry the file's `allowNonLoopback`/`enabled` through import with no override. A malicious file can import an enabled non-loopback tunnel. CONFIRMED.
- M-05: replace path (`BackupService:267`) deletes env vars in-transaction, then applies children from the export; non-secret exports carry no env vars, so existing encrypted env vars are permanently deleted. CONFIRMED.
- M-06: upload opens the destination with TRUNC directly (no `.part` + atomic rename); conflict check is UI-list-side only. CONFIRMED.
- M-07: upload stream `RemoteFileOutputStream()` is never closed/flushed (only `remote.close()`); last-write errors are lost; size check partially mitigates; `copyTo` 8 KB buffer. CONFIRMED.
- M-08: `refresh()` launches a new coroutine per call with no job tracking; a stale listing can overwrite a newer path's entries. CONFIRMED.
- M-09: `buildExport` and `parseForPreview` run on Main (`viewModelScope.launch`, no `withContext` around them); SAF `queryDisplayNameAndSize` is called outside the IO block in `startUpload`. PBKDF2-600k on Main freezes weak phones. CONFIRMED (deleteDocument-in-catch part not separately checked).
- M-10: `openOutputStream(uri)` without `"wt"` — code fact CONFIRMED; the truncate consequence is provider-dependent (reviewer's own ⚠️).
- M-11: `SessionViewModel.connect` assigns `connection = conn` with no close of a previous connection in the prologue; `disconnect()`/`onCleared()` close on Main. CONFIRMED for the SessionViewModel instance; TunnelManager-map and decisionDeferred-hang sub-details not separately checked.
- M-12: only `authPassword`/`authPublickey` branches exist; no keyboard-interactive/2FA. CONFIRMED.
- M-13: `SocksSocketFactory.createSocket(host,port)` builds `InetSocketAddress(host,port)`, which resolves locally — DNS leak + proxy-only names fail. Fix is `createUnresolved`. CONFIRMED.
- M-14: list mapping copies `RemoteResourceInfo.isDirectory/isRegularFile` with no stat-follow; symlinks-to-dirs are neither, so not enterable. CONFIRMED.
- M-15: `saveButton`'s `insertButton` + `replaceChildren` are not in one transaction (`replaceChildren` itself is `@Transaction`, the pair is not); sortOrder max+1 is read-modify-write without a lock. CONFIRMED with that nuance.
- M-16: `getOrCreateKey()` has no synchronization and `TermBridgeApplication` creates two `AndroidKeystoreSecretCrypto` instances; concurrent first use can orphan data encrypted under the replaced key. GCM without AAD also confirmed absent. CONFIRMED.
- M-17: no bracketed-paste support (no `200~`/`201~` anywhere); paste goes through a dialog but is sent raw. CONFIRMED for bracketed absence; dialog control-char visualization not examined in detail.
- M-18: `FLAG_SECURE` only on the MainActivity window; no `SecureFlagPolicy` anywhere; Compose dialogs have separate windows. CONFIRMED.
- M-19: no BC provider config / `AndroidConfig` anywhere; ed25519/chacha-on-Android risk unproven (reviewer's ⚠️). BC advisories already known from Phase 5. CONFIRMED as code fact.

## Low — all CONFIRMED except noted

L-01 authType param unused in `validateCredential` body (signature only). L-02 `valueOf` on stored strings (`AdvancedRepository:181`, import paths) — unknown value crashes a Flow collection. L-03 `exportSchema = false` (line 39). L-04 trust keyed by `hostname:port` string; deleteHost derives the endpoint and deletes by it (case-sensitivity + orphan-on-rename follow). L-05 `SshError.kt:36` matches `"host key"` by substring (file is `SshError.kt`, not `SshErrorMapper` as cited). L-06 FK CASCADE on `jumpHostId` (lines 57–58) silently drops hops when a jump host is deleted. L-07 `isLoopback` is exactly 4 strings (no `127.0.0.0/8`); REMOTE `targetHost` has no loopback constraint in validation. L-08 PARTIAL: only NONE/SOCKS offered (doc-confirmed); the exact misleading message and IPv6 rejection were not traced to the parse function. L-09 `ExportCrypto.kt` is under `security/` (not `data/` as cited); `key.encoded?.wipe()` at lines 83/103 wipes a clone (`getEncoded()` copies). L-10 `copySettingsForDuplicate` is dead (definition only) and copies `shellConfirmed`. L-11 PARTIAL: value wipe-on-validation-failure depends on the caller (not traced); no constraint handling around the uniqueness check-then-insert. L-12 `hasExistingCredential` loads async and gates saves — race window structurally present. L-13 `PasswordVisualTransformation` without `KeyboardType.Password`. L-14 no `SecretScanner` use in `BackupService`/`BackupCodec` — notes/tags/templates unscanned on non-secret export. L-15 no keepalive set anywhere. L-16 `isMinifyEnabled = false`, no `distributionSha256Sum`. L-17 exactly 321 strings, 18 hardcoded `errorMessage = "..."` in ViewModels/data.

## Documentation mismatches — all CONFIRMED

Window-resize API claim, vim/less/htop usability claim, FR-04 resize, INSERT_ONLY/import guarantees, README Advanced-SSH scoping, README "all Apache 2.0" licences (BC/slf4j are MIT-style), `SftpManager.delete` comment, "default export carries no secrets" (L-14), fixture-in-CI claim (stale per H-09 note). Token-revocation reminder remains an open operational item.

## Bottom line

The review is unusually strong: of 46 findings, 44 are confirmed in full
or in substance, 1 sub-claim is rejected (H-05 tab precedence), and 1
finding is stale for the live repo (H-09's CI claim). The reporter's
own confidence labels were honest — the ⚠️ items I could check
mechanically (C-02's thread path, M-10's provider dependence, M-19's
Android BC risk) hold up as stated. Nothing in the report was found
fabricated; citation paths differ in two places (L-05, L-09) without
changing the substance.

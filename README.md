# TermBridge

Independent Android app for remote-server management over
SSH: host profiles with Keystore-encrypted credentials, a
real interactive terminal, SFTP file transfer, custom
command buttons, per-host environment settings, SSH tunnels
/ jump hosts / SOCKS proxy, and passphrase-encrypted
import/export. Phone-first, no mandatory cloud account, no
root, no AI features.

**Status: signed releases ship from this tree.** Version
`0.1.1-foundation` (versionCode 2), database v10. CI went
green for the first time on 2026-10-25 (run #13: unit tests
+ debug assemble + emulator instrumented tests). Since then:
the 9-phase security remediation and the review-2 fix wave
landed (see `docs/REVIEW_2_REMEDIATION.md`), four
Termius-parity phases added settings/themes, Vault
navigation, an app lock and a VNC screen (see
`docs/PARITY_PHASES.md`), and signed releases v0.1.0 /
v0.1.1 were published from tags (signature verified against
the release keystore). Still open: on-device verification
runs and tier-2 integration tests. See
`docs/RELEASE_NOTES.md` for the release gates.

**At-rest scope (audit #6 TB-010):** "Keystore-encrypted"
covers credentials, keychain keys and environment-variable
VALUES. Host names, usernames, env-var names, command-button
templates, tunnel definitions, session transcripts and the
connection log are stored unencrypted in app-private storage
(protected by Android's per-app sandbox and
`allowBackup=false`, not by cryptography). The threat model
treats a compromised/unlocked device as out of scope; see
`docs/SECURITY_KEY_LIFECYCLE.md`.

## What works (evidence labels in docs/PARITY_PHASES.md and the phase reports)

- Hosts + credentials: Room profiles, Android Keystore
  AES-256-GCM, host-key fingerprint verification
  (UNKNOWN / MATCH / CHANGED, trust persisted only after
  successful auth, trust deleted with the host).
- SSH terminal: own VT parser (incl. alternate screen),
  sshj 0.40.0 shell channel, paste confirmation, control
  keys, pinned auto-scroll.
- SFTP: streaming upload/download, SAF-only local access,
  single-entry delete, path-traversal clamping.
- Command buttons: three strictly separate modes
  (insert-only / execute-one / sequence), `{{name}}`
  parameters with per-shell quoting (POSIX, PowerShell;
  cmd.exe parameterised buttons refused), risky-command
  second confirmation, secret-scan blocking saves.
  Since remediation phase 1, a parameterised button is
  REFUSED at the execution boundary while the host's shell
  is unconfirmed or the prepared text is stale (shell
  changed since preparation) — in every mode, including
  insert-only — and the dialog disables its action button
  with the reason. "Confirmed" means the user asserted the
  shell in Environment and saved it; the app cannot detect
  a remote shell, so confirmation is a user assertion, not
  an app verification.
- Environment/paths: per-host env vars (Keystore-encrypted,
  via sshj `setEnvVar`, never `export` injection) and
  working directories (one visible `cd` per shell, SFTP
  start directory). Buttons are never modified by env/cd.
- Advanced SSH: local + remote forwarding (loopback
  default), jump-host chains, unauthenticated SOCKS proxy.
  Dynamic forwarding, HTTP CONNECT, ProxyCommand, and
  authenticated SOCKS are unavailable, with reasons in
  the UI — not faked.
- Import/export: one versioned JSON format (v1). Default
  export carries no secrets; the explicit encrypted
  backup uses PBKDF2 (600k) + AES-256-GCM. Trusted host
  keys are never exported; import never trusts a key or
  executes a command.
- App shell (parity phases 1–4): persisted settings
  (font size, scrollback, TERM type, keep-alive, colour
  schemes), Vault / Connections / Settings navigation,
  known-hosts management, local connection-history log,
  subnet discovery, quick-connect form pre-fill, and an
  optional app lock (salted PBKDF2 PIN with throttling,
  optional biometric unlock).

## What is blocked (honest, by design)

- **RDP**: blocked by the prompt-12 proof gate (no NDK,
  no CI build, no Windows endpoint here). No RDP code,
  no connect button. Candidate: FreeRDP 3.x, built from
  source when unblocked.
- **VNC session**: the pure-Kotlin RFB protocol core
  (versions 3.3/3.7/3.8, No Auth + VNC Auth,
  Raw/CopyRect/RRE/CoRRE/Hextile) is wired to a remote
  desktop screen since parity phase 4 (touch-as-mouse,
  key row, Unicode typing). It is NOT yet proven against
  a real VNC server: `RfbIntegrationTest` exists but is
  skipped in CI, so treat VNC as unverified until that
  proof lands (item F-03a in
  `docs/PARITY_TRACEABILITY.md`). A JVM-level proof does
  exist: the CI `vnc` job runs `RfbLiveTest` against a real
  x11vnc server (protocol + framebuffer); what remains
  unverified is the on-device screen and input path
  (audit #6 TB-019).

## Build

Toolchain: Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21,
JDK 17, compile/target SDK 35, minSdk 26. The committed
`gradlew` wrapper is genuine (see `docs/DEVELOPMENT.md`).

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest   # needs an emulator/device
```

CI (`.github/workflows/android-ci.yml`): unit-test +
debug-assemble job (on main it also publishes the debug
APK to the rolling `debug-latest` release), plus an
emulator instrumented-test job. First green run: #13 on
2026-10-25 (commit 0ce9772). The instrumented job
provisions the SSH fixture before the emulator run
(remediation phase 7); without a fixture the fixture
tests skip via Assume.

## Server prerequisites

- SSH: a reachable SSH server (Linux/Unix, or Windows
  with an SSH server configured — do not assume Windows
  has one enabled). SFTP needs a compatible SFTP
  subsystem on the server. Most servers accept only
  LANG/LC_* environment variables by default.
- RDP: not supported (see "blocked" above). VNC: the
  client screen exists but is unverified against a real
  server (see "blocked" above).

## Permissions, privacy, security

- Permissions: INTERNET + ACCESS_NETWORK_STATE in the app manifest, plus USE_BIOMETRIC and USE_FINGERPRINT contributed to the merged manifest by the AndroidX biometric library for the optional app lock (verified against the biometric 1.1.0 library manifest, 2026-11-04).
  `allowBackup=false` (export is the migration path);
  cleartext traffic forbidden; one exported activity
  (the launcher); FLAG_SECURE on the app window.
- Secrets: never plaintext, never logged, never
  hardcoded. Keystore loss means re-entering credentials.
- Audits: `docs/SECURITY_AUDIT.md` (prompt 16, 13
  findings) + `docs/SECURITY_FIXES_16.md` (fix pass) +
  `docs/CAPABILITY_CLAIM_AUDIT.md` (prompt 17, 23 rows) +
  `docs/REVIEW_2_REMEDIATION.md` (review-2 fix wave).
  Residual gates: full dependency-tree scan (OSV spot
  queries done — 4 Bouncy Castle advisories assessed,
  see `docs/REVIEW_2_REMEDIATION.md`), fixture tier-2
  tests, device runs, release signing. Remaining-work
  tracking: `docs/PARITY_TRACEABILITY.md`.

## Licences (shipped runtime dependencies, all Apache 2.0)

Jetpack Compose (BOM 2024.09.00), Navigation Compose
2.8.0, Kotlin Coroutines 1.8.1, Room 2.6.1, sshj 0.40.0,
kotlinx-serialization-json 1.7.3, Material Icons Extended
(Compose BOM 2024.09.00), AndroidX core/lifecycle/activity,
AndroidX Biometric 1.1.0.
Test-only dependencies (JUnit, Espresso, Compose UI test)
are not shipped. The About screen lists the same set.

## Known limitations (summary — full list in docs)

Alternate-screen `?47` unsupported; no terminal reflow;
no background service (process death loses the session);
tunnel session is destination-scoped (leaving Advanced
stops tunnels); SFTP and terminal use separate SSH
connections (prompt-15 decision, documented);
upload picker cannot start in the local folder
(`OpenDocument` contract limit); VNC Auth is weak by
design (DES, 8-char password — trusted networks or SSH
tunnels only). Full capability matrix:
`docs/CAPABILITY_MATRIX.md`.

## Docs map

- `docs/PROJECT_BRIEF.md`, `docs/ROADMAP.md`,
  `docs/REQUIREMENTS_MATRIX.md` — what was promised.
- `docs/BASELINE_REPORT_00.md`,
  `docs/PHASE_REPORT_01.md` … `docs/PHASE_REPORT_18.md`
  — one report per prompt, with evidence labels.
- `docs/RELEASE_NOTES.md` — the handoff: gates, open
  decisions, clean-tree checklist.
- `docs/DEVELOPMENT.md` — toolchain rationale and
  commands. `docs/TEST_MATRIX.md` — test coverage map.

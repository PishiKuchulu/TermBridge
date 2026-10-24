# TermBridge

Independent Android app for remote-server management over
SSH: host profiles with Keystore-encrypted credentials, a
real interactive terminal, SFTP file transfer, custom
command buttons, per-host environment settings, SSH tunnels
/ jump hosts / SOCKS proxy, and passphrase-encrypted
import/export. Phone-first, no mandatory cloud account, no
root, no AI features.

**Status: handoff, NOT a release.** Version
`0.1.0-foundation` (versionCode 1), database v5. The whole
tree was written in this environment but has never been
built, tested, or run on a device here — see
`docs/RELEASE_NOTES.md` for the release gates that must
pass first. Nothing in this file claims otherwise.

## What works in code (all NOT RUN — see release notes)

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

## What is blocked (honest, by design)

- **RDP**: blocked by the prompt-12 proof gate (no NDK,
  no CI build, no Windows endpoint here). No RDP code,
  no connect button. Candidate: FreeRDP 3.x, built from
  source when unblocked.
- **VNC session**: blocked by the prompt-13 proof gate
  (no controlled Linux VNC server here). A pure-Kotlin
  RFB protocol core IS in the tree (versions 3.3/3.7/3.8,
  No Auth + VNC Auth, Raw/CopyRect/RRE/CoRRE/Hextile)
  but is deliberately unwired — no session UI, no
  connect button — until a real-server proof exists.

## Build

Toolchain: Gradle 8.10.2, AGP 8.7.3, Kotlin 2.0.21,
JDK 17, compile/target SDK 35, minSdk 26. The committed
`gradlew` wrapper is genuine (see `docs/DEVELOPMENT.md`).

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest   # needs an emulator/device
```

CI (`.github/workflows/android-ci.yml`, never run —
no repository exists yet): unit-test + debug-assemble
job, plus an emulator instrumented-test job.

## Server prerequisites

- SSH: a reachable SSH server (Linux/Unix, or Windows
  with an SSH server configured — do not assume Windows
  has one enabled). SFTP needs a compatible SFTP
  subsystem on the server. Most servers accept only
  LANG/LC_* environment variables by default.
- RDP/VNC: see "blocked" above; no endpoint is
  supported until the proof gates pass.

## Permissions, privacy, security

- Permissions: INTERNET + ACCESS_NETWORK_STATE only.
  `allowBackup=false` (export is the migration path);
  cleartext traffic forbidden; one exported activity
  (the launcher); FLAG_SECURE on the app window.
- Secrets: never plaintext, never logged, never
  hardcoded. Keystore loss means re-entering credentials.
- Audits: `docs/SECURITY_AUDIT.md` (prompt 16, 13
  findings) + `docs/SECURITY_FIXES_16.md` (fix pass) +
  `docs/CAPABILITY_CLAIM_AUDIT.md` (prompt 17, 23 rows).
  Residual gates: dependency-vulnerability scan, first
  CI build, fixture + device runs.

## Licences (shipped runtime dependencies, all Apache 2.0)

Jetpack Compose (BOM 2024.09.00), Navigation Compose
2.8.0, Kotlin Coroutines 1.8.1, Room 2.6.1, sshj 0.40.0,
kotlinx-serialization-json 1.7.3, Material Icons Extended
(Compose BOM 2024.09.00), AndroidX core/lifecycle/activity.
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

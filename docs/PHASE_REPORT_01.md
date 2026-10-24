# TermBridge — Phase Report, Prompt 01

Date: 2026-10-24. Phase: 01 — Requirements, Architecture Decisions,
and Feasibility. No feature code was written in this phase, by design.

## 1. Actual changes and touched files

- `docs/REQUIREMENTS_MATRIX.md` — FR-01…FR-12, NFR-01…NFR-08, out-of-scope list.
- `docs/ARCHITECTURE.md` — logical layers, data flow, protocol
  abstraction, security boundaries, lifecycle ownership.
- `docs/ADR/ADR-01-language-ui.md` — Kotlin + Compose (LOCKED direction).
- `docs/ADR/ADR-02-persistence.md` — Room + encrypted secrets (direction LOCKED).
- `docs/ADR/ADR-03-ssh-sftp.md` — sshj primary, MINA SSHD fallback.
- `docs/ADR/ADR-04-terminal.md` — own Kotlin VT engine recommended; termlib only after a licence check.
- `docs/ADR/ADR-05-credential-encryption.md` — Keystore + AES-256-GCM; deprecated security-crypto wrapper NOT used.
- `docs/ADR/ADR-06-rdp.md` — FreeRDP as POC candidate only (not locked, not proven).
- `docs/ADR/ADR-07-vnc.md` — no library locked; POC decides in prompt 13.
- `docs/ADR/ADR-08-import-export.md` — documented formats only; format decision in prompt 11.
- `docs/FEASIBILITY_AND_RISKS.md` — feasibility verdicts + risk register R-01…R-10 + supported/unsupported combinations.
- `docs/TEST_PLAN.md` — unit, integration, device/manual, security, regression tests (all NOT RUN).
- `docs/THREAT_MODEL.md` — assets, threats, mitigations, explicit out-of-scope limits.
- `docs/PHASE_REPORT_01.md` — this report.

No app source, Gradle file, or dependency was added. No governance
internals were copied into the product tree.

## 2. Commands executed and exact results

- Web searches (2026-10-24) for sshj, JSch/mwiede, Apache MINA SSHD,
  FreeRDP Android + licence, VNC/RFB clients, terminal/VT libraries,
  Android Keystore/security-crypto deprecation: completed, results
  summarised in ADR-03…ADR-07 with their limits stated.
- No build, no test run, no emulator run: exit codes N/A — nothing to
  claim. Repository baseline checks: there is no repository yet, so
  there was no existing build to run (prompt 02 owns the foundation).

## 3. Tests added/run

- Added: none (test plan only). Run: none. Pass/fail: NOT RUN (all).
- Tests not run, with reasons: no code exists yet; no Android SDK and
  no reliable Gradle daemon in this sandbox; no device/emulator here.

## 4. Build status and environment limitations

- Build status: no build exists yet (prompt 02).
- Limitations carried from prompt 00, unchanged: JDK 21 available,
  Android SDK absent, Gradle daemon unreliable in this sandbox, no
  device/emulator. Prompt 02 must decide the real build route (CI /
  capable machine) and document it.

## 5. Security implications and dependency/licence decisions

- sshj: Apache 2.0, pure Java (no ABI risk for the core) — primary.
- Apache MINA SSHD: Apache 2.0 — named fallback.
- mwiede JSch: BSD-style — considered, not primary (ADR-03 reasoning).
- FreeRDP: mixed licence in distribution packaging (Apache-2.0 core +
  LGPL-2.1-or-later components) — exact inventory required in prompt 12.
- ConnectBot termlib: licence NOT verified — therefore NOT adopted.
- androidx.security:security-crypto: deprecated in 1.0/1.1.0 — NOT used.
- No AI, cloud, or analytics dependency is introduced.

## 6. Requirements implemented vs partial vs blocked

- Implemented: none (documentation phase).
- Defined with acceptance criteria: FR-01…FR-12, NFR-01…NFR-08.
- Partial / OPEN: exact versions (Kotlin/Compose/AGP/minSdk/targetSdk),
  terminal final choice, RDP/VNC libraries, import/export formats,
  Keystore lifecycle details — each assigned to its owning prompt.
- Blocked: none for this phase. The phase's own exit gate is met by
  this report: every major capability has a verified-feasibility path
  or an explicit blocker/deferral (RDP/VNC), and no dependency or UI
  claim in these docs asserts an unproven capability.

## 7. Evidence for claimed capabilities

- No capability is claimed as implemented. "Feasible" in
  FEASIBILITY_AND_RISKS.md means "a credible path exists from the
  sources found", and each such row names the proof its later prompt
  must produce (controlled-sshd test, POC, etc.).
- Library versions found in search (e.g. sshj 0.40.0, 13 May 2025)
  are reported as search findings, to be re-verified against Maven
  Central in the build environment — they are not pinned here.

## 8. Remaining issues and next prompt

- Prompt 02 — Project Foundation and Reproducible Build: create the
  app foundation, pin versions only after the build environment can
  resolve them, set up test/CI foundations, and document the real
  build route given the sandbox limitation.
- Awaiting the user's go-ahead for prompt 02, per the pack's
  stop-at-the-gate rule.

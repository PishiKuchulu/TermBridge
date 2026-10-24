# TermBridge — Feasibility and Risks (Prompt 01)

Evidence honesty note: library facts below come from web search on
2026-10-24, not from a downloaded artifact or a running proof-of-concept.
Versions and licences must be re-verified against the authoritative
source (Maven Central / project repository) in prompts 02/05 before any
dependency is pinned. A "feasible" row below means "a credible path
exists", never "it already works".

## Feasibility by capability

| Capability | Verdict | Basis |
|---|---|---|
| Host profiles + persistence | Feasible | Room + Keystore are standard Android; no server needed. |
| SSH password/key auth | Feasible (to prove in 05) | sshj 0.40.0 (13 May 2025 per sources found), pure Java, Apache 2.0, SFTP + forwarding in one library, Terrapin mitigation. |
| Host-key verification | Feasible | sshj host-key verifier interface; app must implement fingerprint display + per-host store + changed-key hard stop (FR-03). |
| Interactive terminal | Feasible, own-engine path | No drop-in Compose VT library found; a scoped Kotlin VT engine is a bounded, testable build (ADR-04). Risk is TUI edge cases, not basic shells. |
| SFTP file transfer | Feasible (to prove in 07) | Same sshj session as SSH; server must offer SFTP — a server prerequisite, not an app bug. |
| Command buttons | Feasible | Pure app logic (insert vs execute vs sequence) + confirmation policy; the risk is UX (accidental execution), mitigated by FR-06 tests. |
| Tunnels / jump hosts / proxy | Feasible for sshj-supported paths | sshj supports forwarding; each path still needs its own E2E test in prompt 10. |
| Import/export | Feasible for documented formats | OpenSSH/PEM key parsing via sshj/BouncyCastle path; PuTTY only if a maintained parser is found in prompt 11. |
| RDP (Windows) | Plausible, HIGH cost, unproven | FreeRDP has a real Android NDK/CMake port (4 ABIs, API 21+ default); licence is mixed (Apache + LGPL components) and must be inventoried exactly in prompt 12. |
| VNC (Linux) | Protocol core implemented, session BLOCKED pending fixture | Scoped Kotlin RFB core built in prompt 13 (3.3/3.7/3.8, None + VNC Auth, Raw/CopyRect/RRE/CoRRE/Hextile) — but server-unproven: no controlled server, device, or CI run. Native LibVNCClient rejected (GPLv2 + NDK). See docs/VNC_NOTES.md. |
| Android build in this sandbox | NOT available | No Android SDK here; Gradle daemon does not stay alive in this sandbox (known workspace limitation). Build proof must come from CI or a capable machine in prompt 02. |

## Risk register

| ID | Risk | Impact | Mitigation / owner prompt |
|---|---|---|---|
| R-01 | sshj Android/BouncyCastle interaction or a version newer than 0.40.0 changes the API surface. | Core blocked or reworked. | Pin only after Maven Central check in 02; MINA SSHD is the named fallback (ADR-03); POC in 05. |
| R-02 | Terminal TUI edge cases (alt screen, wide chars, obscure escapes) render wrong. | Power users hit garbled TUIs. | Scripted VT test corpus in 06; scope the supported subset honestly in docs; do not claim full xterm. |
| R-03 | FreeRDP native build blows up APK size, ABI coverage, or CI time. | RDP delays or kills the release. | RDP stays an isolated native module; POC gate in 12 decides ship vs blocker report; core never depends on it. |
| R-04 | VNC has no good Android path and the POC fails. | FR-11 unshipped. | Blocker report in 13 is the correct outcome; SSH/SFTP core is unaffected. |
| R-05 | Keystore key loss on restore / OEM Keystore quirks lock users out of their hosts. | Data loss perception, support burden. | Prompt 04 must define rotation + restore behaviour + backup rule explicitly; never silently present undecryptable hosts as usable. |
| R-06 | A "trust all host keys" shortcut slips into production code or a test helper ships in the app. | MITM vulnerability. | FR-03 tests + prompt-16 audit search for promiscuous verifiers; no such code in production sources. |
| R-07 | Import of a malicious profile/key file executes or stores something unsafe. | Credential or code risk. | Untrusted-input handling: validate + preview + fail closed (11); never auto-execute imported commands. |
| R-08 | Secrets leak via logs, crash reports, screenshots, or backups. | Credential exposure. | Redaction rules (THREAT_MODEL.md); backup rule in 04/11; audit in 16. |
| R-09 | Scope creep: RDP/VNC/native work destabilises the SSH core before it is solid. | Core regressions. | Milestone order A → B → C is enforced: no native module before prompt 10's core is green. |
| R-10 | No device/emulator available for manual checks in this environment. | UX claims unverified. | Manual checklist (prompt 14) is marked NOT RUN until a device/emulator run provides evidence; CI unit tests do not substitute for it. |

## Supported / unsupported combinations (to be completed with evidence in prompts 05–13)

Supported in principle (server prerequisites apply):

- Linux/Unix + OpenSSH server: SSH, shell, SFTP (if the SFTP subsystem
  is enabled), tunnels, jump hosts.
- Windows + Win32-OpenSSH configured: SSH, shell (PowerShell/cmd as
  configured), SFTP if the server offers it.
- Windows + RDP enabled, compatible edition: RDP BLOCKED after prompt 12
  (POC gate failed in this environment: no NDK, no CI build, no Windows
  endpoint — see docs/RDP_NOTES.md). It is not a shipped feature.
- Linux + VNC server/session configured: VNC session BLOCKED after prompt 13
  (protocol core implemented but server-unproven — see docs/VNC_NOTES.md).
  It is not a shipped feature.

Unsupported / not promised:

- SFTP on a server without an SFTP subsystem; RDP on a Windows edition
  or configuration without remote access; Linux desktop access without
  a desktop server/session; environment variables in login modes where
  the SSH protocol/shell does not apply them; any "works everywhere"
  claim for any protocol.

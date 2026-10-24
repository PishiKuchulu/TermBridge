# TermBridge — Roadmap and Phase Gates

Source: the TermBridge prompt pack roadmap, adapted to this project tree.
Product documentation only — it contains no governance-framework internals.

## Non-negotiable principles

- Security and verification are never downgraded to meet a deadline.
- No fake capabilities: a screen or mock transport is not a working feature.
- Host identity must be verified; secrets must be protected; logs must
  redact sensitive values.
- No routine root requirement on Android.
- Protocol support is conditional on server-side configuration and
  compatible protocol services.
- Build and test evidence must be reproducible and honestly reported.

## Phase sequence

| Prompt | Phase | Main deliverable | Exit gate |
|---|---|---|---|
| 00 | Product brief / reconnaissance | Brief + baseline report (this phase) | Repo / governance / environment actually understood; blockers stated |
| 01 | Requirements and feasibility | Requirements matrix, architecture record, risk register | Every major capability has a verified feasibility path or an explicit blocker |
| 02 | Project foundation | Buildable app foundation, conventions, CI/test baseline | Clean reproducible build; no unnecessary dependency or scaffold churn |
| 03 | Navigation and UI foundation | Phone-first Compose shell, accessible design system | Screens navigate; UI responsive; no vertical/overflow text |
| 04 | Hosts and credential security | Host profiles, secure credential model, host-key trust design | Persistence and secret-handling tests pass; no plaintext secret storage |
| 05 | SSH transport | Real SSH connection/authentication behind interfaces | Integration test against a controlled SSH endpoint; host-key checks enforced |
| 06 | Interactive terminal | Stateful interactive shell, input/output, lifecycle | Real shell session works; reconnect/disconnect and error paths tested |
| 07 | SFTP and file manager | Bidirectional browsing and transfer | Upload/download, cancellation, progress, permission/error cases tested |
| 08 | Command buttons | Custom actions: insert-only vs execute | No accidental execution; parameter validation and dangerous-action confirmation |
| 09 | Environment and paths | Per-host/session environment and path settings | Behaviour scoped/documented; unsupported behaviour is not faked |
| 10 | Advanced SSH | Tunnels, jump hosts, proxy options if feasible | Each supported path tested end-to-end; unsupported cases documented |
| 11 | Import/export | Validated profile/key import, safe encrypted export/backup | Preview, validation, secret-safe behaviour, round-trip tests |
| 12 | RDP | Remote desktop to compatible Windows endpoints | Real protocol proof-of-concept + lifecycle/security tests; license/ABI reviewed |
| 13 | VNC / Linux desktop | Remote desktop to compatible Linux sessions | Real protocol proof-of-concept; supported servers and limitations documented |
| 14 | Mobile UX refinement | Usability, accessibility, performance, orientation polish | Key workflows pass a documented manual test checklist |
| 15 | Integration and regression | Cross-feature test suite, fixed regressions | Critical test matrix passes; failures are not waived silently |
| 16 | Independent security audit | Threat model, findings, remediation evidence | No unresolved critical/high issue without explicit risk acceptance |
| 17 | Capability audit | Claim-to-evidence matrix | Every advertised capability is classified and evidence-linked |
| 18 | Release and handoff | Clean source ZIP, APK if the build environment permits, docs, hashes | Reproducible build/release gate passes, or blocked status is explicit |

## Milestones

- **A — Foundation:** prompts 01–04. No protocol features until the
  architecture, security model, and app shell are coherent.
- **B — Core remote work:** prompts 05–09. First meaningful usable
  product: SSH, terminal, SFTP, command buttons, scoped environment/paths.
- **C — Advanced connectivity:** prompts 10–13. Advanced SSH and remote
  desktop only after feasibility prototypes validate libraries, licenses,
  ABI/build impact, and protocol compatibility.
- **D — Release readiness:** prompts 14–18. UX, regression tests,
  security audit, capability audit, release packaging.

## Protocol feasibility gates (before any SSH/SFTP/RDP/VNC library is chosen)

1. Verify active maintenance, license, and attribution obligations.
2. Verify Android SDK compatibility, supported ABIs, native build
   requirements, and package-size impact.
3. Confirm whether the library is a real protocol implementation or only
   a UI wrapper.
4. Build a minimal proof-of-concept on the actual target environment.
5. Test cancellation, timeouts, reconnect, background/foreground
   transitions, and error reporting.
6. Record unsupported server types and limitations.
7. Do not claim parity with Termius or universal support.

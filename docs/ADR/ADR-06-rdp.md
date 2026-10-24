# ADR-06 — RDP library: FreeRDP as the POC candidate (NOT locked, NOT proven)

- Status: OPEN / DEFERRED to prompt 12. This ADR names the only
  candidate to be proven; it does not approve RDP.
- Candidate: FreeRDP (libfreerdp + WinPR), the open RDP implementation
  with an existing Android port (Java GUI + native libraries via NDK
  and CMake + JNI bindings).
- Evidence (web search, 2026-10-24):
  - FreeRDP's Android build documentation describes NDK/CMake builds
    for armeabi-v7a, arm64-v8a, x86, x86_64, with OpenSSL/OpenH264/
    libjpeg as native dependencies, and a default target of API 21+.
  - Licence is mixed in distribution packaging (Apache-2.0 core plus
    LGPL-2.1-or-later and other components per Fedora packaging);
    prompt 12 must produce an exact licence inventory for the exact
    FreeRDP version and components actually linked — a single "Apache"
    label is NOT sufficient.
- Why it is only a candidate: native build complexity, APK size impact,
  certificate/NLA handling, and touch/keyboard input mapping are all
  unproven for this app. A failed POC means an evidence-based blocker
  report in prompt 12, not a shipped stub.
- Rejected for MVP: a pure-Java RDP reimplementation (protocol scope
  far exceeds a POC phase); Guacamole as an Android client (it is a
  gateway/web architecture, not an Android RDP library).
- Gate (prompt 12): real connection to a compatible Windows endpoint,
  lifecycle + security tests, licence/ABI/size report, and a supported /
  unsupported Windows-edition list.

## Prompt 12 verdict (2026-10-24) — GATE FAILED, feature blocked

- FreeRDP 3.x is actively maintained and its Android port is real
  (NDK/CMake build, 4+ ABIs, JNI bindings), but it ships no
  Android library artifact: integration requires building native
  code from source and owning the JNI bridge.
- The gate failed on environment, not on library existence: no
  NDK in this environment, no CI build, no Windows endpoint to
  test against, no native infrastructure in the app.
- Licence status corrected: the core is Apache-2.0 per the
  project and multiple distributors; the prompt-01 "mixed Apache
  + LGPL" note attributed to Fedora packaging could NOT be
  reproduced in this re-check and is unconfirmed — the exact
  inventory must come from the pinned source tree at integration
  time. Full evidence: docs/RDP_NOTES.md. RDP stays blocked
  until that document's §7 checklist is satisfied.

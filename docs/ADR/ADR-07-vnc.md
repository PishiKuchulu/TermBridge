# ADR-07 — VNC library: NO library locked; POC must choose in prompt 13

- Status: OPEN / DEFERRED to prompt 13. Deliberately no choice is made
  here, because the prompt-01 search found no maintained pure-Kotlin
  Android VNC client library to lock.
- What was found (and why none is locked):
  - jfxvnc: a Java VNC client (RFB 3.3–3.8, Netty-based protocol layer,
    Raw/Zlib/Hextile/Cursor encodings, VNC Auth, SSL) — but built on
    JavaFX/Swing UI layers, not Android/Compose; reusing its protocol
    layer would be a port, not a drop-in.
  - TightVNC Java Viewer: Swing-based; same Android-UI problem.
  - LibVNCServer/LibVNCClient (native C): a real RFB stack, but it
    would be a second native module with its own NDK/ABI/size cost,
    like FreeRDP — only justifiable after a POC.
  - RFB itself is a documented, platform-independent protocol, so a
    scoped Kotlin RFB client (Raw + Zlib/CopyRect + VNC Auth first) is
    a legitimate POC option alongside a native client.
- Decision rule for prompt 13: the POC must compare (a) scoped Kotlin
  RFB client vs (b) native LibVNCClient on: encodings actually needed,
  auth methods, ABI/size, licence, and input handling — and pick only
  with a working connection to a real Linux VNC server. If neither
  passes, the output is a blocker report, not a VNC screen.
- Supported servers are therefore NOT listed yet; any list written
  before prompt 13 would be invented.

## Prompt 13 verdict (2026-10-24) — SPLIT: core chosen, session blocked

- Architecture decided: (a) scoped Kotlin RFB client. The
  core is implemented (protocol 3.3/3.7/3.8, None + VNC
  Auth, Raw/CopyRect/RRE/CoRRE/Hextile, input messages)
  with unit tests for the protocol logic — but it has
  never talked to a real server, so the ADR-07 decision
  rule's "working connection" half is NOT satisfied.
- Session blocked: no controlled Linux VNC server, no
  device, no CI run in this environment — the pack's
  proof-before-integration requirement cannot be met
  here. No session UI, connect button, host field, or
  Room change exists. Full evidence: docs/VNC_NOTES.md.
- Native LibVNCClient (b) rejected this phase: GPLv2
  (linking makes the app a GPL derivative — a
  distribution-licence decision, not a phase default)
  and it needs the same absent NDK/CI as FreeRDP.
- Supported servers remain unlisted as tested fact; the
  server list a fixture must cover (TigerVNC, x11vnc,
  TightVNC, RealVNC — with the configuration each needs
  before this core can talk to it) is in VNC_NOTES.md
  §7. Until a fixture run proves a real-server
  connection, every VNC statement keeps the
  "server-unproven" qualifier.

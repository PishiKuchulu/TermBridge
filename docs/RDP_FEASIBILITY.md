# RDP feasibility re-evaluation — item F-03c (2026-11-04)

Status: **REPORT ONLY. No RDP code was written. Implementation
requires the owner's written approval (prompt section 5B/F-03).**

This document re-evaluates the earlier RDP gate (see
`docs/RDP_NOTES.md`, which failed at the time) against the
current state of the upstream ecosystem.

## What changed since the first gate

- **FreeRDP is actively maintained and still ships an Android
  client.** The 3.x line continues to develop `client/Android`
  (Java UI + JNI over libfreerdp): build updates, a UI
  overhaul, camera channel (RDPECAM) and RemoteApp (RAIL)
  support landed in recent 3.x releases. The Android target
  is a first-class upstream citizen again, not a fossil.
- **In-production proof exists.** aRDP (Iordan Iordanov) is a
  shipping Android RDP client built on the FreeRDP library
  plus parts of aFreeRDP, with SSH tunnelling — the exact
  shape TermBridge would want. Its own code is GPL, so it is
  evidence, not a code source.
- **FreeRDP itself is Apache-2.0**, compatible with this
  project's licensing posture.

## The realistic route

Embed **libfreerdp + WinPR** via the NDK, driven from Kotlin
through a thin JNI bridge modelled on upstream's own Android
client structure:

1. Native build: CMake toolchain file from upstream Android
   support; dependencies are OpenSSL (bundled build), zlib,
   and optionally FFmpeg (H.264 — can be deferred; the
   fallback codecs work without it).
2. JNI surface: session create/connect/disconnect, a
   framebuffer callback into an Android Bitmap (the same
   shape the VNC screen already consumes), pointer/key input
   forwarders, and the certificate-verification callback.
3. UI: reuse the F-03b desktop screen wholesale (geometry,
   zoom/pan, sticky modifiers, clipboard, profiles,
   through-SSH connect via a local forward — RDP would ride
   `127.0.0.1:<forward>` exactly like VNC does).

## Hard requirements (non-negotiable)

- **Certificate verification is never silently disabled.**
  FreeRDP calls back for unknown/changed certificates; the
  bridge must surface the fingerprint to the user and store
  the decision with the same posture as SSH host keys
  (stored-trust wins; unknown = prompt or decline, never
  accept-all).
- **NLA/CredSSP** must be enabled — most modern Windows
  servers require it; it comes with FreeRDP but pulls the
  full TLS stack into the build.
- **16 KB page alignment** for all native libraries
  (Android 15+ requirement) — an upstream/NDK flag concern
  that must be proven in the spike, on the newest NDK.
- **APK size**: expect roughly +6–12 MB per ABI for
  libfreerdp+WinPR+OpenSSL. The project currently ships a
  single universal APK with no splits; the size consequence
  needs an owner decision (ABI splits vs universal).

## Cost estimate

- Spike (below): 2–4 focused days.
- Full integration to the VNC screen's standard (input,
  clipboard, profiles, SSH tunnel, certificate UX, tests,
  CI native build): 2–4 weeks of focused work, plus a
  permanent CI cost — every build job gains an NDK native
  build (minutes per ABI) and a new failure class.

## The minimal spike (defined, NOT executed)

1. Pin a FreeRDP release; build `libfreerdp` + `WinPR` for
   arm64-v8a with the project's CI (separate job, NDK
   installed there only) — proves the native build + 16 KB
   alignment without touching the app.
2. A throwaway JNI bridge in a scratch module: connect to a
   test Windows/xrdp endpoint, render one frame to a Bitmap,
   send one pointer event. Proves the callback surface.
3. Report: build times, artifact sizes per ABI, the exact
   certificate callback behaviour, and a go/no-go for the
   full integration.

The spike itself needs the owner's go-ahead: it introduces
the NDK into CI and spends real build time, even though it
ships nothing.

## Alternatives considered and rejected

- **Pure-Kotlin/Java RDP implementation**: none exists at a
  maintainable maturity; writing one is a multi-month
  protocol project (MS-RDPBCGR + channels + codecs). No.
- **Guacamole**: a server-side gateway (the phone would talk
  to a guacd the user must run) — changes the product's
  shape from "client" to "client + your server". No.
- **aRDP code reuse**: GPL; also a whole app, not a library.
  Evidence only.
- **Microsoft's own client**: a separate app; cannot embed.

## Recommendation

Technically feasible via FreeRDP JNI — the earlier gate's
"no maintained Android path" premise no longer holds. The
cost is a permanent native toolchain in the build and a
meaningful APK size increase. **Decision needed from the
owner:** approve the spike (step 1–3 above), approve it
with ABI splits pre-decided, or decline RDP for this
product and keep VNC as the only desktop protocol.

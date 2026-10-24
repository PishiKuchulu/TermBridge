# TermBridge — RDP proof-of-concept evaluation (prompt 12)

Date: 2026-10-24. Verdict: **POC GATE FAILED — RDP is blocked, not
shipped.** No RDP connection code exists in this tree, no RDP
connection has been demonstrated, and the Desktop screen says so
explicitly. This document is the evidence-based blocker report the
pack requires for a failed gate. It is not a feature description.

## 1. The gate, as specified

Prompt 12's pre-implementation gate requires, before any RDP code
is integrated into the app:

- re-check of official project docs, licence/attribution,
  maintenance, native library/ABI requirements, Android support,
  packaging impact, and security history;
- a minimal proof-of-concept built before integration;
- acceptance: a real connection to a controlled compatible Windows
  endpoint demonstrated, or the phase explicitly blocked with
  evidence.

The gate's acceptance criterion is binary: a demonstrated
connection, or a blocker report. There is no third outcome in
which an unconnected RDP screen ships as a feature.

## 2. Candidate: FreeRDP — what was verified

FreeRDP (libfreerdp + WinPR) remains the only candidate, per
ADR-06. What the 2026-10-24 re-check established:

- **Actively maintained.** Upstream released 3.26 (Android client
  overhaul: UI modernisation, SQLCipher support), 3.27.1 and 3.28
  (Android touch-pointer redesign, RISC-V 64-bit support, release
  builds made configurable, external dependencies moved to their
  own CMake file, RAIL/RemoteApp window support on Android).
  FreeBSD ports list FreeRDP 3.32.x in the 2026Q3 branch. The
  library is not dead or abandoned — that is NOT the blocker.
- **A real Android port exists upstream.** Three parts, per
  FreeRDP's own Android build documentation: a Java GUI in
  `client/Android/Studio`, native libraries built with the Android
  NDK and CMake, and JNI bindings in
  `client/Android/android_freerdp.c` with the Java class
  `LibFreeRDP.java`. The build script fetches and builds OpenSSL,
  OpenH264 and libjpeg, then the FreeRDP native libraries.
  Default ABIs: armeabi-v7a, arm64-v8a, x86, x86_64 (RISC-V 64-bit
  added in 3.28). Default target is API 21+ debug builds; release
  builds became configurable upstream in 3.28. TermBridge's
  minSdk 26 sits above that floor, so the API floor is not a
  problem.
- **Existence proof on Android.** Third-party Android clients
  ship on FreeRDP — aRDP (current release 6.4.9) is an open-source
  Android RDP client built on FreeRDP and parts of aFreeRDP. It
  proves the FreeRDP-on-Android path works in production. It is
  NOT a reusable option for TermBridge: it is a separate
  GPL-licensed application, not a library, and adopting its code
  would import a licence decision TermBridge has not made (see
  §3). It is cited here only as existence proof, not as a
  dependency.
- **No published Android library.** FreeRDP publishes no
  maintained AAR on Maven Central or any other artifact
  repository found in this re-check. There is no dependency to
  add to `libs.versions.toml`. Integration means building the
  native libraries from source and owning the JNI bridge.

## 3. Licence inventory — status: OPEN, not closed

This is a correction to prompt 01's shorthand, stated plainly:

- Multiple distributors label the FreeRDP core **Apache-2.0**:
  the project itself ("released under the Apache license"),
  FreeBSD ports (APACHE20 for both the 2.x and 3.x ports), and
  the openSUSE RPM (Apache-2.0).
- Prompt 01 recorded a "mixed (Apache + LGPL components)" note
  attributed to Fedora packaging. This re-check could **not**
  reproduce a Fedora freerdp LGPL tag from search results — the
  Fedora queries returned only generic packaging guidelines, not
  the freerdp spec's licence field. The mixed-licence claim is
  therefore **unconfirmed**, not confirmed, and must not be
  repeated as fact.
- What an exact inventory must still cover, from the pinned
  FreeRDP source tree at integration time (not from packaging
  summaries): the core and WinPR per-file headers; OpenSSL
  (Apache-2.0); OpenH264 (BSD-2-Clause licence plus Cisco's
  patent licence terms for the prebuilt binary — building from
  source changes the patent position, and that distinction must
  be recorded); libjpeg-turbo (BSD-style); Kerberos, if enabled
  (MIT or Heimdal variants); FFmpeg, if the ffmpeg codec path is
  enabled (LGPL/GPL — this is the one optional component that
  could introduce copyleft into the linked set, so the codec
  selection is a licence decision, not just a build flag).
- Consequence: the licence question does not block the POC by
  itself (Apache-2.0 core is compatible with the app's
  permissive dependencies), but no licence statement for a
  shipped RDP build may be written until that inventory exists.
  The app's NOTICE/attribution file must gain a FreeRDP entry
  before any RDP release.

## 4. Integration cost — why this is a native module, not a prompt

Even with a working build machine, RDP is not a dependency swap.
It is a new native module plus a new UI surface:

- **Build:** NDK + CMake wired into Gradle (`externalNativeBuild`
  does not exist in this app today — grep confirms no ndk/cmake
  configuration), the upstream Android build script run for a
  pinned FreeRDP version, per-ABI `.so` packaging, and a CI job
  that installs the NDK. APK size impact per ABI is unmeasured
  and must be measured at POC time; an ABI-split strategy would
  have to be decided then (the app has no splits today).
- **JNI bridge:** upstream's `android_freerdp.c` /
  `LibFreeRDP.java` belong to FreeRDP's own Android app, not to a
  library. TermBridge would adapt and own that layer: session
  lifecycle, framebuffer delivery into a Compose-hosted view
  (AndroidView interop), scaling, and orientation changes.
- **Input:** touch-pointer and gesture mapping plus soft-keyboard
  injection. Upstream redesigned its Android touch pointer as
  recently as 3.28, which shows this surface is still being
  iterated upstream — TermBridge would inherit that churn.
- **Authentication and trust:** NLA/CredSSP plus TLS. The
  certificate-verification callback must be wired to a
  user-visible trust decision in the style of the SSH trust
  dialog (prompt 05). Silently accepting a mismatched server
  certificate is forbidden by the pack and by this project's own
  rules, and FreeRDP's defaults must be audited against that
  rule at integration time — not assumed.
- **Security treadmill:** RDP parsers are a frequent CVE source.
  Both 3.26 and 3.28 shipped CVE fixes. Vendoring native FreeRDP
  means owning rebuilds on every upstream security release, for
  every shipped ABI. That is a standing maintenance cost, not a
  one-time integration.
- **Redirection claims:** clipboard and file/drive redirection
  are supported by FreeRDP in principle, but the pack forbids
  claiming file transfer through RDP unless verified. Neither is
  claimed here; both need their own verification and security
  review after a basic desktop connection works.

## 5. Server compatibility — what a future RDP feature must support

Documented here so a future POC tests the right endpoints, not
so any of it is claimed working:

- **Host editions:** Remote Desktop hosting requires Windows Pro,
  Enterprise, or Education (Windows 10 and 11). Windows Home
  cannot host a Remote Desktop session — it can only be a
  client. Any "Windows" claim that omits this is false.
- **Configuration:** Remote Desktop must be enabled on the host;
  the connecting account must be permitted for remote access;
  the host must be reachable on the RDP port (default 3389) or
  through a gateway. NLA (authentication before session
  establishment) is the expected server setting on Pro/
  Enterprise/Education hosts.
- **Credential handling (design, not implementation):** RDP
  credentials would reuse the prompt-04 model — Keystore
  AES-256-GCM storage, never logged, never in diagnostics — and
  would be subject to the same rules as SSH credentials
  (passphrase-style secrets never persisted where the model
  forbids it). No RDP credential field, table, or screen exists
  yet, and prompt 11 exports no remote-desktop state.
- **Unsupported, explicitly:** Windows Home as a host; any host
  without Remote Desktop enabled; RDP through a gateway (not
  evaluated); smart-card logon (not evaluated); RemoteApp as a
  separate feature (upstream supports RAIL, but it is not part
  of any TermBridge plan).

## 6. Why the gate fails HERE — the exact failed conditions

The library is viable in principle (§2). The gate fails on
environment, not on library existence. Each failed condition is
independently sufficient:

1. **No NDK in this environment.** The staged Android SDK at
   `~/workspace/tools/android-sdk/` contains build-tools,
   cmdline-tools, platform-tools and platforms only — no NDK
   directory. The FreeRDP Android build script's first
   requirement (Android NDK) cannot be satisfied here.
2. **No CI build exists.** No repository has been created or
   pushed (not authorised), so no GitHub Actions run exists.
   The Gradle daemon failure that blocks local Android builds
   (prompts 02–11) also blocks any local native build attempt.
3. **No Windows endpoint to connect to.** There is no controlled
   compatible Windows endpoint (Pro/Enterprise/Education, RD
   enabled) available to this environment, and no device or
   emulator on which a client could run.
4. **No native infrastructure in the app.** No CMake/NDK
   configuration, no JNI code, no framebuffer view. A "skeleton"
   native module written without the ability to compile it
   would be uncompilable decoration, and a Kotlin-only "RDP
   manager" with no native backend would be a pretend client.
   Both were rejected; the honest deliverable is this report
   plus the updated Desktop screen.

Alternatives considered and rejected (carried from ADR-06,
re-confirmed):

- **Pure-Kotlin RDP reimplementation:** rejected. The protocol
  scope (NLA/CredSSP, TLS, GFX/RemoteFX/H.264 graphics pipelines,
  virtual channels, input) far exceeds a POC phase.
- **Guacamole as an Android client:** rejected. It is a
  gateway/web architecture, not an Android RDP library, and
  would introduce a server component the app does not have.
- **Vendoring aRDP's code:** rejected as a dependency path. It
  is a GPL application, not a library; see §2.

## 7. What would unblock RDP (checklist for a future attempt)

1. A build machine or CI job with the Android NDK and CMake
   that runs the upstream Android build script for a pinned
   FreeRDP 3.x version, release configuration, for at least
   arm64-v8a (plus armeabi-v7a if that ABI ships), with
   per-ABI APK size measured.
2. A licence inventory from that exact source tree and linked
   set (§3), with attribution added to the app.
3. A minimal JNI bridge + framebuffer view that connects to a
   controlled Windows endpoint (Pro/Enterprise/Education, RD
   enabled, NLA on), shows a desktop, survives orientation
   change, and demonstrates certificate-mismatch behaviour
   (user-visible decision, never silent acceptance).
4. Lifecycle tests (connect, disconnect, network loss), error
   tests, and credential-handling tests under the prompt-04
   model, plus a supported/unsupported edition list in docs.
5. Only then: host-profile RDP fields, display settings, and
   input mapping as a real feature phase — not as part of the
   POC.

Until that checklist is satisfied, RDP stays blocked and the
Desktop screen must keep saying so. Prompt 13 (VNC) is a
separate gate and is not prejudged by this verdict.

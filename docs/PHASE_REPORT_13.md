# TermBridge — Phase Report, Prompt 13

Date: 2026-10-24. Phase: 13 — VNC for Compatible Linux Endpoints.
Process: autonomous continuation per the user's instruction
(no per-prompt widget).

## 1. Actual changes and touched files

This is a POC-gated phase, and its verdict is SPLIT — so the
change list has two halves, and the report keeps them
separate. Nothing here claims a VNC session works.

Protocol core (new, pure Kotlin + JDK, no new dependency):

- `domain/RfbProtocol.kt` — version negotiation (3.3 / 3.7 /
  3.8, with the documented 3.3 fallback for other 3.x
  versions), security-type and encoding constants, message
  types, framebuffer dimension caps.
- `domain/RfbAuth.kt` — VNC Authentication: DES/ECB
  challenge-response with bit-reversed password bytes
  (truncated to 8 bytes or zero-padded), key/response
  wiping, test-only decrypt.
- `domain/RfbFramebuffer.kt` — pixel format (wire-value
  reading honouring the endianness flag, true-colour and
  colour-map conversion to ARGB), bounds-checked
  framebuffer with fill/copy/resize.
- `domain/RfbMessages.kt` — ServerInit / update-header /
  rect-header parsing; byte-exact builders for
  SetPixelFormat, SetEncodings, FramebufferUpdateRequest,
  KeyEvent, PointerEvent, ClientCutText.
- `domain/RfbDecoder.kt` — update application for Raw,
  CopyRect (overlap-safe), RRE, CoRRE, Hextile (full
  background/foreground carry-over rules), DesktopSize
  resize; every other encoding rejected, every
  out-of-bounds rect/subrect rejected, truncated streams
  fail on EOF.
- `transport/RfbConnection.kt` — `RfbConnector.connect`
  (TCP connect, version + security handshake per version,
  no-silent-downgrade auth rules, ClientInit, ServerInit,
  SetPixelFormat + SetEncodings) and `RfbSession`
  (update requests, server-message loop, pointer/key/
  clipboard sending, deterministic close). Deliberately
  NOT wired to any screen, ViewModel, or navigation route.

Tests (new, all written NOT RUN):

- `RfbAuthTest` (6 cases) — hand-computed bit reversals,
  key derivation (truncate/pad/reverse), the FIPS DES
  test vector through the real encrypt function, encrypt/
  decrypt round-trip, determinism, wiping.
- `RfbDecoderTest` (16 cases) — Raw, CopyRect, RRE, CoRRE,
  Hextile raw tile / bg+fg+subrect / carry-over / coloured
  subrects, DesktopSize resize, and eight failure cases
  (first tile without background, foreground+coloured,
  unknown mask bit, unsupported Tight encoding,
  truncated stream, out-of-bounds rect, RRE count above
  area).
- `RfbMessagesTest` (10 cases) — ServerInit parsing,
  absurd-dimension rejection, version selection (exact,
  fallback, non-3 rejection, malformed rejection), and
  byte-exact builders (SetPixelFormat, SetEncodings,
  update request, key + pointer events).
- `RfbConnectionTest` (4 cases) — scripted loopback-peer
  handshake: None auth + Raw update applied; VNC Auth +
  password wiped after connect; None offered but not
  allowed fails; VeNCrypt-only fails instead of
  downgrading. The peer is a scripted socket in the test
  file — it proves handshake sequencing, NOT real-server
  interop (stated in the test file's KDoc).

Documentation (new):

- `docs/VNC_NOTES.md` — the full POC evaluation: split
  verdict, gate as specified, re-checked protocol evidence,
  implemented-vs-refused matrix, rejected alternatives
  (native LibVNCClient on licence + environment grounds,
  JVM Swing clients as ports not drop-ins, stub/session
  UI as the forbidden fake), the exact failed gate
  conditions, the server-compatibility list a fixture
  must cover, and the §8 unblock checklist.

Documentation (updated, not rewritten):

- `docs/ADR/ADR-07-vnc.md` — prompt-13 verdict appendix:
  scoped Kotlin RFB core chosen as the architecture, with
  the session still gated on a real-server connection.
  The original ADR text is untouched.
- `docs/FEASIBILITY_AND_RISKS.md` — the VNC feasibility
  line now reads "protocol core implemented, session
  blocked pending fixture" (pointing at VNC_NOTES.md),
  and the supported-combinations line for Linux + VNC
  now states the split explicitly instead of "only after
  prompt 13" (which could be misread as "coming").

UI (one screen, honesty update only):

- `ui/screens/RemoteDesktopScreen.kt` — the VNC row keeps
  its UnavailableRow (NO connect button: a button that
  has never connected would be a decorative control),
  and is now followed by three text blocks (protocol-core
  status, blocked reason, what-would-unblock text). The
  RDP rows are byte-for-byte untouched — prompt 12's
  verdict is not revisited here.
- Strings: 304 + 3 new desktop VNC strings = 307 total,
  0 missing references in static check (only `app_name`
  unused in Kotlin, used by the manifest).

Deliberately NOT changed: no build file, no dependency,
no native module, no CMake/NDK configuration, no JNI
code, no Room schema change (database stays at version
4), no host profile field, no navigation route, no
ViewModel, no SSH/terminal/SFTP/buttons/env/tunnels/
backup code. No third-party code or binary was downloaded
or committed. Prompt 11's export format is unaffected:
there is no VNC session state to export.

## 2. Commands executed and exact results

- Web re-check (2026-10-24) of RFB evidence: RFC 6143
  (RFB 3.8, March 2011) security types and VNC
  Authentication weakness statement; the community RFB
  protocol document (version strings, 3.3 vs 3.7/3.8
  handshakes, SecurityResult rules, VNC Auth bit-order
  note, ServerInit layout, RRE/CoRRE/Hextile wire
  formats, message-type and encoding tables, VeNCrypt
  subtypes); TigerVNC's encoding/security-type tables;
  LibVNCServer/LibVNCClient licence (GPLv2) and stable
  release (0.9.14, December 2022) summaries, plus its
  own FAQ statement that linking makes a program a GPL
  derivative.
- Environment checks (exact results): `~/workspace/tools/`
  contains no NDK directory (same finding as prompt 12,
  but not a blocker for this core — it is pure Kotlin);
  grep over the app tree finds no VNC code outside the
  new `Rfb*` files, the desktop screen strings, and
  navigation; no repository exists, so no CI run exists;
  no controlled Linux VNC server and no device/emulator
  is available here.
- Static verification (passed): string-reference check —
  306 Kotlin references, 0 missing (307 strings defined;
  the only Kotlin-unused one is `app_name`, used by the
  manifest); strings.xml and manifest parse as XML;
  grep — no PFP mention in any prompt-13 file and no PFP
  content copied anywhere, no AI wording, no cloud/
  sign-in claims, no `Log.` call and no `println` in new
  or touched code, no TODO markers in new files, no
  broad storage permission, `allowBackup` still false;
  brace/paren/bracket balance — 0 imbalance in all new
  and touched files under the corrected check (strings
  stripped before line comments).
- One real defect caught and fixed during writing: the
  first draft of `RfbConnection` threaded the socket to
  the session through a placeholder that threw
  IllegalStateException. It was replaced before any
  verification step with a direct socket parameter
  through the handshake — the session now owns the
  socket it was created with. No placeholder, stub, or
  throwing helper remains in the shipped code (verified
  by grep for `currentSocket` / `IllegalStateException`
  in the Rfb files: no matches).
- No Gradle build, no test run: NOT RUN, same sandbox
  reasons as prompts 02–12 (daemon protocol + Java
  HTTPS). CI is still the first real build/test gate.

## 3. Tests added/run

- Added: 36 test methods across 4 files (RfbAuthTest 6,
  RfbDecoderTest 16, RfbMessagesTest 10,
  RfbConnectionTest 4). Coverage intent: every decoder
  branch that can be driven from bytes (including all
  six Hextile failure modes found in the spec's
  carry-over rules), every builder byte-for-byte, the
  auth primitive against a published vector (not just a
  round-trip), and the handshake sequencing against a
  scripted peer (including the two must-fail auth
  cases).
- Not added, deliberately: no session UI test, no
  "VNC manager" stub test, no test claiming a real
  server accepted anything. The scripted-peer tests are
  labelled in their own KDoc as sequencing proofs only.
- Run: none. Pass/fail: NOT RUN (all). No VNC behaviour
  on a device is claimed, and no VNC test is claimed to
  have passed. The first CI run executes these tests;
  the fixture in VNC_NOTES.md §8 is what turns them
  from protocol tests into a session proof.

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet (same as prompts
  02–12 for the whole app). For VNC specifically: no
  native toolchain is needed, so unlike RDP the build
  blocker is only the standing one (no CI run yet) —
  but the SESSION blocker is independent of the build
  (no controlled server, no device).
- Compile risks to watch on the first CI run: `javax.crypto`
  DES on API 26+ (the minSdk floor; DES is available in
  Android's crypto providers, but the first CI + device
  run must confirm `DES/ECB/NoPadding` resolves — a
  missing algorithm would fail at connect time, not at
  compile time, so the fixture must cover it); `DataInput`
  / `DataOutputStream` use in pure domain code (JDK
  only, no Android dependency); `RfbSession`'s internal
  constructor visibility to the test source set (same
  module, so visible — but a test-source-set split in a
  future build file change could break it); the
  `RfbConnectionTest` loopback threads (10-second test
  timeouts bound any hang; a failed peer assertion is
  captured via Throwable, not lost as a dead thread).
- Previews: the existing `RemoteDesktopScreenPreview`
  still compiles against the updated screen (same
  composable signature, no new parameters); it was not
  run (no build).
- Database stays at version 4 with MIGRATION_1_2/2_3/3_4
  untouched: no VNC host field or table was added,
  because adding schema for a blocked session would
  imply it exists.

## 5. Security properties: proven vs assumed

- Proven by construction: no VNC session can be started
  from the app (no UI path reaches `RfbConnector`), so
  the session attack surface added by this phase is
  zero; the core's failure modes are all closed (unknown
  encodings, out-of-bounds rects, absurd dimensions,
  unsupported auth all throw fixed messages). No secret
  appears in any new text (the screen and docs contain
  no credential, address, or key material); the password
  is wiped in a finally block and the DES key as soon as
  the response is sent; clipboard text is capped and
  never logged.
- Proven by the re-check (documentary evidence, §2):
  RFB 3.8 wire facts used by the core; VNC Auth's
  DES/bit-reversal construction; LibVNCClient's GPLv2
  licence and its linking consequence.
- Proven by test design (not yet by execution — tests
  are NOT RUN): the DES primitive against the FIPS
  vector, the bit reversal against hand-computed values,
  every builder byte-for-byte. These are the strongest
  claims this phase can make without a server, and they
  are labelled as exactly that.
- Assumed, not proven: that a real TigerVNC/x11vnc
  server, configured for VNC Auth + the advertised
  encodings, accepts this handshake and sends updates
  this decoder applies correctly (the core follows the
  documented wire format, but no server has confirmed
  it); that JCE DES resolves on a real Android device
  (see §4); that the 3.3 fallback for other 3.x versions
  is accepted by servers advertising them (standard
  robust-client behaviour, unconfirmed against any
  specific server); that servers never send a
  FramebufferUpdate rectangle the decoder's bounds
  checks reject for legitimate reasons (the checks
  follow the spec, but a quirky server is a fixture
  finding, not a code assumption).
- Documented non-claims and limits: no VNC session, no
  desktop rendering, no touch/keyboard input path in the
  UI, no scaling/zoom/pan/rotation, no reconnect, no
  Tight/ZRLE/TRLE/zlib decoding, no VeNCrypt/TLS/X.509,
  no remote cursor, no "works on all Linux
  distributions or desktops" claim, no SSH-alone-
  provides-a-desktop claim (the opposite is documented).

## 6. Requirements implemented vs partial vs blocked

- Implemented (code + docs scope): the pure-Kotlin RFB
  protocol core (handshake, None + VNC Auth, five
  decoders, input/clipboard messages, DesktopSize
  resize, colour-map handling); the POC evaluation
  (VNC_NOTES.md); the honest Desktop screen update
  (core status + blocker + unblock text, no connect
  button); the ADR-07 verdict appendix; the feasibility
  -doc correction.
- Partial, explicitly: the VNC feature as a whole. The
  core is complete for its documented subset; the
  session (UI + proven connection + device behaviours)
  is not partial, it is blocked. Calling the whole
  feature "implemented" would be the false claim this
  phase is structured to avoid.
- Blocked: the VNC session in full (connect flow,
  rendering, input, scaling, lifecycle, reconnect,
  clipboard round-trip) — blocked by the four
  environment conditions in VNC_NOTES.md §6, each
  independently sufficient: no controlled server, no
  device, no CI run, no session UI by design.
- Not implemented, correctly: any session UI, any
  connect button, any VNC host-profile field, any
  navigation route or ViewModel for VNC, any Tight/
  ZRLE/VeNCrypt/TLS code, any native LibVNCClient
  module, any mocked "successful connection" test, any
  server-compatibility claim.

## 7. Evidence for claimed capabilities

- No VNC session capability is claimed. Claims with
  evidence are: the static checks (§2), the environment
  checks (§2), the documentary re-check (§2, sources:
  RFC 6143, the community RFB protocol document,
  TigerVNC's tables, LibVNCServer licence summaries),
  and the code itself (every decoder branch and builder
  is exercised by a written — NOT RUN — test). The
  protocol core's server interop is explicitly labelled
  UNPROVEN in code comments, docs, UI text, and this
  report. Everything needing a run is labelled NOT RUN,
  and the session itself is labelled BLOCKED in both
  docs and UI.

## 8. Remaining issues and next prompt

- Prompt 14 — Mobile UX & Performance: its own phase,
  not prejudged by this verdict. The VNC session UI
  belongs to a future unblocked VNC phase, not to 14's
  UX pass over existing features — 14 must not present
  the VNC row as a feature to polish. Open decisions
  carried forward: repository + CI push, application ID
  confirmation, UI-language confirmation (before prompt
  14 — this phase's new strings are English, consistent
  with v1), SSH/SFTP integration fixture, connection
  unification (prompt 15), Room transaction for
  button-child replacement (prompt 08 item), form-dialog
  -level save-error surfacing (prompt 08 item),
  room-testing for the v2->v3 and v3->v4 migrations,
  ViewModel-message localisation (prompt 14), upload
  picker starting in the local folder (blocked on the
  OpenDocument contract), on-device SAF picker +
  encrypted round-trip run (prompt 11 item).
- VNC unblock checklist (full text in VNC_NOTES.md §8):
  controlled server fixture with documented config;
  device-run proof (handshake, auth, one full update
  per implemented encoding, disconnect/reconnect, one
  pointer + one key event acted on); only then session
  UI with the VNC-Auth weakness warning and SSH-tunnel
  option; follow-ups (ZRLE/Tight, VeNCrypt/TLS with
  certificate trust, remote cursor, clipboard tests,
  LibVNCClient licence decision) strictly after.
- Continuing autonomously to prompt 14 per the user's instruction.

# TermBridge — VNC (Linux Remote Desktop) POC Evaluation, Prompt 13

Date: 2026-10-24. This is the prompt-13 proof-of-concept
evaluation. Read it as the verdict document: §1 states the
verdict first, and the rest is the evidence for it. Nothing in
this document claims a VNC session works — see §6 for the exact
gate conditions that failed.

## 1. Verdict: SPLIT — protocol core implemented, session BLOCKED

- **Protocol core: IMPLEMENTED (code scope, server-unproven).**
  A pure-Kotlin RFB client core is in the app: protocol
  versions 3.3 / 3.7 / 3.8, No Authentication and VNC
  Authentication, Raw / CopyRect / RRE / CoRRE / Hextile
  framebuffer decoding, SetPixelFormat / SetEncodings /
  FramebufferUpdateRequest / KeyEvent / PointerEvent /
  ClientCutText building, and server-message parsing
  (FramebufferUpdate, SetColourMapEntries, Bell,
  ServerCutText, DesktopSize resize). It is pure Kotlin +
  JDK (JCE for DES, java.net for sockets): no new dependency,
  no native code, no NDK needed — which is exactly why this
  much could be built honestly here, where RDP could not.
- **VNC session: BLOCKED, not shipped.** The pack's
  pre-implementation gate requires proof of "a minimal
  connection against a controlled compatible server before
  full integration". No controlled Linux VNC server, no
  device/emulator, and no CI run exist in this environment,
  so that proof cannot be produced here. There is therefore
  NO session UI, NO connect button, NO VNC host field, and
  NO Room change in this phase: adding any of those around
  an unproven connection would be the fake the gate exists
  to prevent. The Desktop screen's VNC row stays an
  UnavailableRow, now followed by the honest split status.
- **Native LibVNCClient path: REJECTED for this phase**
  (§5), on licence and on environment grounds — it inherits
  RDP's no-NDK/no-CI blocker and would additionally make the
  app a GPL derivative on linking. It is documented, not
  implemented, and not silently substituted.

## 2. The gate, as specified

From prompt 13 and ADR-07: verify library maintenance,
licence, Android/ABI compatibility, security behaviour,
supported VNC variants/encodings, and package impact; prove
a minimal connection against a controlled compatible server
before full integration; if the POC fails or the
licence/security profile is unacceptable, document the
blocker and do not substitute a mock. ADR-07's decision
rule: pick (a) scoped Kotlin RFB client vs (b) native
LibVNCClient only "with a working connection to a real
Linux VNC server. If neither passes, the output is a
blocker report, not a VNC screen."

Applied strictly, that rule yields the split verdict above:
(a) is the right architecture (proven below), but its
"working connection" half is unmet, so the session half of
the feature is blocked. The protocol core is not presented
as satisfying the gate — it is groundwork that makes the
future gate run a fixture exercise instead of a rewrite.

## 3. Protocol evidence (RFB, RFC 6143 + protocol document)

All wire facts below were re-checked 2026-10-24 against
RFC 6143 and the community RFB protocol document
(rfbproto), not copied from memory:

- **Versions:** server sends a 12-byte version string
  ("RFB 003.008\n"); the client must never reply higher
  than offered. 3.8 and 3.7 use a security-type LIST (U8
  count, then U8 types; count 0 = failure + reason).
  3.3 uses a SINGLE U32 security type.
- **SecurityResult (U32, 0 = OK):** always in 3.7/3.8,
  and in 3.3 only for VNC Authentication — for 3.3 + None
  there is no SecurityResult. Reading one there would eat
  the next message and desynchronise the stream; the
  connector implements this distinction explicitly.
- **Security types:** None = 1, VNC Authentication = 2,
  Tight = 16, TLS = 18, VeNCrypt = 19, X.509 variants =
  260–265. Only 1 and 2 are implemented; the rest fail
  cleanly (see §4).
- **VNC Authentication:** server sends a random 16-byte
  challenge; the client DES-encrypts it (ECB, each 8-byte
  half independently) with the password as key, truncated
  to 8 bytes or zero-padded. Bit order: "The lowest bit
  of each byte is considered the first bit and the highest
  discarded as parity. This is the reverse order of most
  implementations of DES so the key may require
  adjustment" — the implementation reverses every password
  byte's bits before DES (DES ignores parity bits, so the
  reversal is the whole adjustment). This is validated by
  a FIPS DES test vector plus hand-computed reversal
  values in RfbAuthTest — not by a round-trip alone.
- **Messages:** client — SetPixelFormat (0), SetEncodings
  (2), FramebufferUpdateRequest (3), KeyEvent (4),
  PointerEvent (5), ClientCutText (6). Server —
  FramebufferUpdate (0), SetColourMapEntries (1), Bell
  (2), ServerCutText (3). All multi-byte integers
  big-endian except pixel values, which follow the
  negotiated format's endianness flag.
- **ServerInit:** U16 width, U16 height, 16-byte pixel
  format (bits-per-pixel, depth, big-endian flag,
  true-colour flag, red/green/blue max, red/green/blue
  shift, 3 padding), U32 name length, name bytes.
- **Encodings:** Raw = 0, CopyRect = 1, RRE = 2, CoRRE =
  4, Hextile = 5, zlib = 6, Tight = 7, zlibhex = 8,
  TRLE = 15, ZRLE = 16; pseudo-encodings DesktopSize =
  -223, Cursor = -239. The client advertises Hextile,
  RRE, CopyRect, Raw (in that preference order); Raw is
  always acceptable even unlisted, and every server must
  support it.
- **RRE:** U32 subrect count, background pixel, then
  subrects (pixel + U16 x, y, w, h). **CoRRE:** identical
  with U8 coordinates.
- **Hextile:** rect split into 16×16 tiles (edge tiles
  smaller). Per tile, a mask byte: 1 = Raw, 2 =
  BackgroundSpecified, 4 = ForegroundSpecified, 8 =
  AnySubrects, 16 = SubrectsColoured. Background and
  foreground carry over from the previous tile unless
  specified — EXCEPT after a raw tile (carry invalid) and
  a foreground may never be carried over a
  SubrectsColoured tile; ForegroundSpecified +
  SubrectsColoured together is invalid. Subrect geometry
  is two bytes: high nibble x / low nibble y, high
  nibble width-minus-one / low nibble height-minus-one.
  The decoder enforces all of these, failing closed on
  violations instead of rendering carried-over colours
  it does not have.

## 4. What the protocol core does, and what it refuses

Implemented (code, in `domain/Rfb*.kt` +
`transport/RfbConnection.kt`):

- Version negotiation incl. the 3.3 fallback for other
  3.x versions (documented in code; a non-3 major version
  or malformed string is rejected).
- Security selection with NO silent downgrade: VNC Auth
  when a password is supplied and offered; None ONLY when
  the caller explicitly allows unauthenticated access. A
  server offering only unimplemented types, or only None
  to a caller that did not opt in, fails with a fixed
  message. A server offering VNC Auth while no password
  was supplied fails (the client cannot invent one).
- SecurityResult handling per version (see §3), with the
  server's failure-reason string read and discarded —
  fixed messages only, never untrusted server text.
- ClientInit (shared desktop), ServerInit parsing with
  dimension caps (each side ≤ 16384, total ≤ 33,554,432
  pixels — a hostile server cannot force a huge
  allocation), SetPixelFormat requesting 32-bit
  true-colour (8/8/8, little-endian), SetEncodings for
  the implemented set.
- Decoders for Raw, CopyRect (overlap-safe via a
  snapshot), RRE, CoRRE, Hextile (full carry-over rules).
  Rectangles and subrectangles outside their parent are
  rejected, not cropped; RRE counts above the rect area
  are rejected; truncated streams fail on EOF. A
  DesktopSize rectangle resizes the framebuffer and
  discards contents (the spec's invalidation rule).
- Input messages: pointer (button mask bit 0 = left,
  1 = middle, 2 = right) and key (X keysym) events,
  clipboard text capped at 1 MB.
- Credential hygiene: the password is a CharArray, wiped
  by the connector in a finally block whatever the
  outcome; the DES key is wiped as soon as the response
  is sent; password bytes are encoded without building
  an intermediate String (the prompt-04 residual, avoided
  here); nothing is logged.

Refused (clean failure, never silent fallback):

- Tight (7), ZRLE (16), TRLE (15), zlib (6), zlibhex
  (8): real servers WILL send these if advertised — so
  they are simply not advertised. If one arrives anyway
  (desynchronised or hostile stream), decoding fails
  rather than misrendering. Tight in particular needs
  zlib streams + JPEG (and its 3-byte TPIXEL form);
  ZRLE needs zlib + run-length decoding; both are
  follow-up work, not phase-13 scope.
- VeNCrypt (19), TLS (18), Tight security (16), X.509
  (260+): not implemented. A TLS/X.509 handshake is a
  certificate-trust feature in its own right (user-visible
  trust decision, like SSH host keys in prompt 05) and
  must not be bolted on unreviewed. Servers requiring
  only these fail cleanly.
- Cursor pseudo-encoding (-239): not requested, so a
  well-behaved server never sends it; if one arrives it
  is rejected as unsupported (remote cursor rendering is
  a session-UI concern, not a protocol-core one).
- Colour-mapped (non-true-colour) servers: the client
  always requests true-colour, so this should not occur;
  the framebuffer model still supports an 8-bit colour
  map via SetColourMapEntries for completeness.

Security posture, stated plainly:

- VNC Authentication is weak BY DESIGN (8-character
  password, DES, no transport encryption — RFC 6143:
  "not intended for use on untrusted networks"). It is
  implemented because it is what real servers offer, not
  because it is strong. The screen text and this document
  both say so, and the future session UI must carry the
  same warning plus the SSH-tunnel option (prompt 10's
  local forwarding can carry VNC over an SSH connection
  — a documented future integration, not a claim made
  now).
- None authentication means no authentication. The
  connector requires an explicit opt-in for it; a future
  UI must surface that choice, not bury it.

## 5. Alternatives considered and rejected (this phase)

- **Native LibVNCClient (C, GPLv2):** a real, complete
  RFB stack (RFB 3.8, most encodings, encrypted
  connections; stable release 0.9.14, December 2022 per
  Wikipedia's LibVNCServer summary). Rejected for this
  phase on two independent grounds: (1) LICENCE — the
  project itself states that merely linking makes a
  program a GPL derivative; adopting it would decide
  TermBridge's distribution licence by side effect, which
  is Farzad's decision, not a phase's silent default.
  (2) ENVIRONMENT — it is native C, so it needs exactly
  the NDK/CI setup whose absence blocked RDP in prompt
  12; it buys nothing on the environment axis. Android
  clients built on it (bVNC, GPL) prove the path works,
  but they are GPL applications, not a library this app
  can depend on.
- **Existing JVM VNC clients (jfxvnc, TightVNC Java
  Viewer):** recorded in ADR-07 — JavaFX/Swing UI layers,
  not Android/Compose; reusing them would be a port of
  their protocol layers, not a drop-in, and jfxvnc's
  Netty dependency would add a whole networking stack
  for a protocol this core implements in ~600 lines.
  The scoped Kotlin core was the cheaper, licence-clean
  option, and it is what was built.
- **A Kotlin-only "VNC manager" stub, or a session UI
  around the unproven core:** rejected as the fake this
  gate exists to prevent. The core is labelled
  server-unproven in code, docs, and UI alike.

## 6. The exact failed gate conditions (environment)

Each independently sufficient to block the SESSION (the
protocol core needed none of these):

1. No controlled Linux VNC server here — no TigerVNC,
   TightVNC, RealVNC, or x11vnc instance to connect to,
   and no server version/auth/encoding combination
   documented from a real run.
2. No device or emulator here to run a session build on
   (framebuffer rendering, touch-to-pointer mapping, and
   keyboard input are all device behaviours).
3. No CI run exists (no repo pushed), so even the
   written unit tests are NOT RUN — the same standing
   sandbox limits as prompts 02–12 (Gradle daemon
   protocol failure + Java HTTPS proxy failure).
4. No session UI exists yet by design (see §1): scaling,
   zoom/pan, rotation, reconnect, and
   background/foreground handling are all unbuilt, and
   building them around an unproven connection would
   invert the pack's order (proof first, integration
   second).

What is NOT a blocker: the protocol itself (documented,
stable since RFB 3.8 in 2011), the toolchain (pure
Kotlin/JDK, same CI job as the rest of the app — no
NDK, no new dependency, no ABI matrix), or the licence
(the core is original code; no third-party code was
downloaded or committed).

## 7. Server compatibility — what a future session must prove

NOT a compatibility claim: no server below has been
tested. This is the list the fixture must cover, with the
settings a fixture run must record (server, version,
auth, encodings offered/used, pixel format):

- TigerVNC server (Xvnc): the reference modern
  implementation; typically offers VeNCrypt + VNC Auth
  and Tight/ZRLE encodings — so a fixture MUST configure
  it to offer plain VNC Auth + the core's encodings, or
  the connection will correctly fail (unimplemented
  types) and the fixture will have proven nothing about
  the core. Record the exact configuration.
- x11vnc: attaches to a live X display; default port
  5900, password via -rfbauth; a good fixture for the
  "existing desktop" case, with the same auth/encoding
  configuration requirement.
- TightVNC server: Tight encoding by default — the
  fixture must disable Tight or the core will correctly
  refuse; that refusal is a testable behaviour, not a
  failure, but it does not prove decoding.
- RealVNC Server: typically requires its own auth/encryption
  modes; expected to fail cleanly against this core
  unless configured for plain VNC Auth. That failure
  must be recorded as "unsupported configuration", not
  as a bug.
- SSH alone does NOT provide a graphical desktop: the
  docs and the future UI must keep saying so. A Linux
  host needs a running desktop session AND a VNC server
  attached to it; neither is implied by SSH access.
- No "works on all Linux distributions or desktops"
  claim may be written until the fixture covers more
  than one server and desktop.

## 8. Unblock checklist (what the next VNC phase must do)

1. Controlled server fixture: one documented server
   (TigerVNC or x11vnc first) configured for VNC Auth +
   Raw/CopyRect/RRE/Hextile, with its version, auth, and
   encodings recorded in docs.
2. Device-run proof: a device build connects, completes
   the handshake, authenticates, receives and renders at
   least one full framebuffer update per implemented
   encoding, survives a disconnect/reconnect, and sends
   one pointer and one key event the server acts on.
   Record the run (server, build, result) — a passing
   unit-test run on CI does not substitute.
3. Only then: session UI (framebuffer view, scaling,
   zoom/pan, rotation, pointer/keyboard input, lifecycle)
   and a real connect flow — with the VNC-Auth weakness
   warning and the SSH-tunnel option in the UI.
4. Follow-ups, in order, not bundled into the unblock:
   ZRLE/Tight decoders (with zlib + JPEG handling),
   VeNCrypt/TLS with a user-visible certificate trust
   decision, remote cursor rendering, clipboard
   round-trip tests, and the LibVNCClient licence
   decision (only if the Kotlin core hits a wall that
   justifies revisiting GPL + NDK).
5. The ADR-07 decision rule is satisfied at step 2, not
   before: until a real-server connection exists, every
   VNC statement in docs and UI must keep the
   "server-unproven" qualifier.

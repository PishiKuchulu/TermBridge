# Proposal T-06 — Serial / USB console (owner decision)

**Status: PROPOSAL ONLY. No code. Implementation requires
the owner's written approval.**

## The question

Should TermBridge talk to serial consoles (routers,
switches, embedded boards) through a USB-OTG adapter?

## Facts

- Android's USB host API (`android.hardware.usb`) needs no
  manifest permission; access is granted per device through
  the system's USB permission dialog, and the app should
  declare `android.hardware.usb.host` as an optional
  feature so non-host devices simply never see the code
  path.
- Android has NO built-in USB-serial driver for apps. Each
  chipset family (FTDI, CP210x, CH340/CH341, PL2303,
  CDC-ACM) needs a userspace driver speaking USB bulk
  transfers. Writing these from scratch is months; the
  established route is the `usb-serial-for-android` library
  (MIT licence, maintained, covers all five families).
  Adding it is a new-dependency decision.
- The app's VT engine and session UI are transport-agnostic
  in shape (bytes in, bytes out); a serial session would
  reuse the renderer, extra-keys bar, and transcript, with
  a small connection form (device, baud, data/stop bits,
  parity, flow control).

## Options

1. **Decline.** Out of the remote-management scope.
2. **Spike first (~2 days):** add the library behind a
   throwaway build, prove connect/echo at 9600 and 115200
   baud against two chipset families on a physical device,
   measure APK growth, then decide with evidence.
3. **Full implementation (~1 week after a green spike):**
   serial profiles saved like desktop profiles (endpoint
   only), session screen reuse, instrumented tests are
   impossible without hardware — verification is a device
   test plan entry (F-04) plus JVM tests for the framing
   and profile logic.

## Recommendation

**Option 2.** Serial is a genuinely adjacent need for the
app's audience (network gear consoles), the permission
story is clean (no new manifest permission), and the spike
bounds the risk before the dependency becomes permanent.

## What approval unlocks

The spike (option 2) or a decline (option 1), recorded in
the traceability file either way.

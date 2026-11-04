# Proposal T-05 — Local terminal (owner decision)

**Status: PROPOSAL ONLY. No code. Implementation requires
the owner's written approval.**

## The question

Should TermBridge offer a terminal into a shell ON the
phone itself (no server), like Termux's local session?

## Facts

- Android lets an app spawn processes from its own context
  (e.g. `/system/bin/sh`) with no permission. The shell runs
  with the APP's sandbox identity: it sees the app's private
  files, not the user's storage (beyond existing SAF grants),
  and cannot do anything the app itself cannot.
- A *faithful* terminal needs a real PTY (job control,
  full-screen programs, window-size signals). Android
  exposes no public PTY API to apps; a PTY requires native
  code (forkpty via JNI) — the app currently ships ZERO
  native code, and adding the NDK changes the build, the CI
  (16 KB page-alignment proof per ABI), and the release
  shape.
- A pipe-based pseudo-shell (no PTY) is pure Kotlin/JVM and
  cheap, but line-oriented only: no vim/top/htop, no Ctrl-C
  semantics beyond killing the process, no resize. It would
  feel broken next to the app's real SSH terminal.
- Shipping a userland (PRoot-style proot + a distro rootfs)
  is a different product: tens of MB of downloads, ongoing
  rootfs maintenance, and it clashes with the app's
  offline/no-account posture.

## Options

1. **Decline.** TermBridge stays a remote-management app;
   local-shell demand is served by dedicated apps.
2. **Pipe shell (pure Kotlin).** A clearly-labelled "Local
   shell (limited)" session reusing the VT renderer for
   output. ~2–3 days. Honest limits in the UI copy.
3. **PTY shell (JNI).** Real local terminal. Native module
   + NDK in CI + ABI size cost. ~1–2 weeks including the
   release-shape changes; also the first native attack
   surface in the app.
4. **Full userland.** Out of scope for this app's shape;
   not recommended at any budget.

## Recommendation

**Option 1 (decline), or option 2 if you want the checkbox.**
The remote terminal is the product; a crippled local shell
(option 2) risks looking like a defect, and the real one
(option 3) imports the whole native-build cost for a
side feature.

## What approval unlocks

A reply naming the option. Option 2/3 then get a mini-plan
and their own group; option 1 closes T-05 as DECLINED in
the traceability file.

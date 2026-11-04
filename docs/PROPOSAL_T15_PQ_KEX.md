# Proposal T-15 — Post-quantum KEX toggle (owner decision)

**Status: PROPOSAL ONLY. No code. The support question the
work order asked to verify FIRST is answered below from the
pinned dependency itself.**

## Verified fact (2026-11-04, from the project's own jar)

The app pins sshj 0.40.0. Inspecting that jar's KEX
classes, the complete key-exchange offer set is:

- diffie-hellman-group1-sha1, group14-sha1,
  group-exchange-sha1, group-exchange-sha256
- ecdh-sha2-nistp256 / nistp384 / nistp521
- curve25519-sha256, curve25519-sha256@libssh.org

**Neither `mlkem768x25519-sha256` nor
`sntrup761x25519-sha512` exists in sshj 0.40.0.** There is
nothing to toggle: the algorithms are absent from the
library, not merely unconfigured.

## What a PQ toggle would therefore require

1. **A dependency change:** upgrade sshj to a line that
   ships a hybrid PQ KEX, or hand-roll a KEX factory (a
   custom `KeyExchange` implementation plugged into sshj's
   config). Hand-rolling hybrid ML-KEM key exchange —
   including the Bouncy Castle ML-KEM side — is security-
   critical code with no local test oracle strong enough to
   bless it; not advisable in this codebase.
2. **A server to test against:** the CI fixture's OpenSSH
   would need a version offering the same algorithm
   (OpenSSH ≥ 9.9 offers sntrup761x25519-sha512 by default;
   ML-KEM hybrids arrived in OpenSSH 10.0) — the fixture
   can do this, but only once the client side exists.
3. **Interop posture:** with a PQ-preferred offer list,
   negotiation against older servers must fall back
   cleanly; the toggle would be off by default and the
   fallback behaviour needs its own test.

## Options

1. **Decline for now; revisit on the sshj-upgrade track.**
   PQ support then arrives (or not) as a verified property
   of the new library version, with the toggle as a small
   follow-up. This pairs naturally with item R-07 (the
   full dependency scan / sshj evaluation already owed).
2. **Force the upgrade now** purely for PQ: a full
   re-verification of the transport (the 0.40 line is
   load-bearing and fixture-proven) for one opt-in
   algorithm most servers cannot yet negotiate.
3. **Hand-rolled KEX:** rejected above on safety grounds.

## Recommendation

**Option 1.** The honest state today is "not supported by
the pinned library" — record T-15 as DECISION-DEFERRED
with this evidence, and let the R-07 dependency evaluation
report whether a newer sshj changes the answer.

## What approval unlocks

Nothing to build either way until the dependency question
moves; your call records whether T-15 waits with R-07
(recommended) or forces the upgrade evaluation early.

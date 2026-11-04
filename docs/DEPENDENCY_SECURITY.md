# Dependency security scan (item R-07)

**Method:** every direct dependency at its pinned version,
plus the security-relevant transitive line (Bouncy Castle
via sshj), queried against the OSV database
(api.osv.dev querybatch) on 2026-11-04. This replaces the
2026-10-25 spot check. Build-toolchain components (Gradle
8.10.2, AGP 8.7.3, Kotlin 2.0.21, KSP) run at build time
only and are not app-shipped attack surface; they are
listed for completeness.

## Results

| Dependency | Pinned | OSV |
|---|---|---|
| com.hierynomus:sshj | 0.40.0 | CLEAN |
| androidx.room:room-runtime | 2.6.1 | CLEAN |
| org.jetbrains.kotlinx:kotlinx-coroutines-android | 1.8.1 | CLEAN |
| org.jetbrains.kotlinx:kotlinx-serialization-json | 1.7.3 | CLEAN |
| androidx.biometric:biometric | 1.1.0 | CLEAN |
| androidx.core:core-ktx | 1.15.0 | CLEAN |
| androidx.lifecycle:lifecycle-runtime-ktx | 2.8.7 | CLEAN |
| androidx.activity:activity-compose | 1.9.3 | CLEAN |
| androidx.navigation:navigation-compose | 2.8.0 | CLEAN |
| org.jetbrains.kotlin:kotlin-stdlib | 2.0.21 | CLEAN |
| androidx.compose.ui:ui (via BOM 2024.09.00) | 1.7.2 | CLEAN |
| androidx.compose.material3:material3 | 1.3.0 | CLEAN |

## The Bouncy Castle transitive line (via sshj 0.40.0)

sshj 0.40.0 pins bcprov-jdk18on 1.80.2 and bcpkix-jdk18on
1.80. Three advisories apply to those exact versions:

| Advisory | Artifact | Fixed in | Exposure in this app |
|---|---|---|---|
| GHSA-9pwp-9qqc-pr26 — X.509 name-constraints bypass (trailing dot) | bcprov | 1.85 | **Minimal.** The vulnerable code parses X.509 certificates; this app never handles X.509 (SSH host keys are not certificates; no cert auth, no TLS). |
| GHSA-qp49-qgx5-5m26 — ASN.1 lazy-sequence nesting-guard reset (DoS) | bcprov | 1.85 | **Low.** BC ASN.1 parsing touches user-supplied key files and some key decodes. Worst case is a local crash from crafted key material; no data exposure. |
| GHSA-wg6q-6289-32hp — broken/risky crypto algorithm use | bcpkix | 1.84 | **Low.** bcpkix serves PEM/PKCS#8 parsing for key import; the app's acceptance rules sit in front of it (KeyFileParser + load-proof). |

A fourth advisory sometimes listed for this line,
GHSA-c3fc-8qff-9hwx (LDAP injection), does **not** apply:
its bcprov-jdk18on range is fixed AT 1.80.2, the pinned
version.

## Upgrade arithmetic (verified from Maven Central)

- sshj 0.41.2 is itself OSV-clean and pulls Bouncy Castle
  **1.84** — that clears GHSA-wg6q but NOT the two bcprov
  advisories (both need 1.85).
- Clearing everything therefore means sshj 0.41.2 **plus**
  a BC 1.85 constraint, or staying on sshj 0.40.0 with a
  BC 1.85 constraint alone. Either is a transitive-version
  override on the app's most security-critical dependency
  chain and must be proven by the full CI (unit +
  instrumented fixture incl. key-auth and crypto-provider
  tests) before it ships — never by assumption.

## Recommendation (owner decision)

1. **Preferred:** one dedicated change — sshj 0.41.2
   evaluation + BC 1.85 constraint, gated on a fully green
   CI run — as its own PR after the current batch lands.
2. **Acceptable interim:** stay on 0.40.0 / BC 1.80.x with
   the exposure table above (no remotely-reachable issue;
   the reachable surface is local key-file parsing).

Status: scan DONE; the upgrade decision is the owner's.

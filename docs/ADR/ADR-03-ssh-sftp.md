# ADR-03 — SSH/SFTP library: sshj (primary), Apache MINA SSHD (fallback)

- Status: LOCKED as the primary candidate, subject to the prompt-05
  proof: a real connection to a controlled sshd, with host-key
  verification enforced. If that proof fails, this ADR is reopened —
  the choice is not "proven" by this document.
- Decision: sshj as the SSH + SFTP implementation behind the
  `SshTransport` / `SftpTransport` interfaces.
- Evidence (web search, 2026-10-24; to be re-verified against Maven
  Central in the build environment in prompt 02/05):
  - sshj is a pure-Java SSH-2 implementation (no native code, so no
    ABI risk for the core); Apache License 2.0.
  - Version 0.40.0 dated 13 May 2025 in the sources found; it includes
    Terrapin (CVE-2023-48795) mitigation via strict key exchange,
    Ed25519/ECDSA/RSA-SHA2 support, and SFTP support.
  - BouncyCastle is optional in recent versions, which reduces the
    dependency surface; earlier releases explicitly improved Android
    compatibility.
  - It covers, in one library, password/key auth, shell sessions,
    exec, SFTP, and port forwarding — matching FR-02/04/05/08 without
    mixing two SSH stacks.
- Alternatives:
  - Apache MINA SSHD (Apache 2.0, pure Java, client + server): viable
    fallback, but a larger framework surface for a client-only app;
    kept as the named fallback if sshj's prompt-05 proof fails.
  - mwiede JSch fork (BSD-style): actively maintained fork of the
    abandoned original, pure Java, SFTP included; rejected as primary
    because sshj's API and unified session/SFTP model fit the
    transport interfaces better — not because JSch is unusable.
  - Original JSch 0.1.55: REJECTED (abandoned upstream).
  - Commercial SSH libraries (e.g. Maverick/IPWorks): REJECTED for MVP
    (licence/subscription cost conflicts with the no-paywall core).
- Risks to prove in prompt 05: Android desugaring/BouncyCastle
  interaction, exact pinned version from Maven Central, algorithm
  negotiation against modern OpenSSH, and behaviour on timeout/cancel.
- Forbidden: any "promiscuous"/trust-all host-key verifier in production
  code, including in examples shipped in the app.

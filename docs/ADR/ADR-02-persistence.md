# ADR-02 — Persistence: Room for structured data, secrets encrypted separately

- Status: LOCKED direction; implementation details OPEN to prompt 04.
- Decision: Room for structured, non-secret data (hosts, command
  buttons, settings, host-key fingerprints as trust data). Secret
  values are stored only as AES-256-GCM ciphertext (ADR-05), never as
  Room plaintext columns.
- Rationale: Room is the standard Android structured store, works
  offline, and keeps queries testable without a server. DataStore alone
  is a poor fit for lists of hosts/commands with relations.
- Alternatives considered: DataStore for everything (rejected for
  relational lists); raw SQLite (rejected: Room gives the same engine
  with compile-time checks); SQLCipher (OPEN — only if prompt 04
  evidence shows the extra native dependency and size cost are
  justified on top of per-value GCM encryption; do not add it by default).
- Verification in prompt 04: persistence tests, migration strategy,
  and the auto-backup rule for encrypted blobs must be documented.

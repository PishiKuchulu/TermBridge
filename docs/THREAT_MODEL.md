# TermBridge — Threat Model (Prompt 01, boundaries)

## Assets

- Host credentials: passwords, private keys, passphrases.
- Host trust data: accepted host-key fingerprints.
- Session content: terminal output/input, transferred files, command
  history/buttons.
- Exported backups.

## Threats and mitigations (each maps to a requirement + a test)

| Threat | Mitigation | Requirement / test prompt |
|---|---|---|
| Host impersonation (MITM) via a changed or fake host key | Fingerprint shown on first trust; changed key = hard stop; no silent bypass, no trust-all verifier in production code | FR-03 → 05, audit 16 |
| Credential theft from device storage | Keystore-backed AES-256-GCM at rest; no plaintext storage; wipeable buffers where possible; no secret in logs/crash reports | NFR-01 → 04, 16 |
| Credential theft via backup/restore | Encrypted export only; auto-backup rule for encrypted blobs decided explicitly in 04/11 (never silently restorable as usable) | 04, 11 |
| Malicious command execution via an imported button/profile | Import is validated + previewed; imported commands never auto-run; risky actions need confirmation; insert mode never executes | FR-06, FR-09 → 08, 11 |
| Malicious import file (malformed key/profile) | Parser fails closed; partial import never stores secrets; file content never executed | 11 |
| Transfer interruption / partial file mistaken for complete | Progress + cancellation + integrity/complete-vs-partial reporting; partial files are not silently claimed complete | FR-05 → 07 |
| Shoulder-surfing / screenshot exposure of terminal or secrets | Secrets masked in UI; terminal content is session content, not logged; screenshot policy decided in 04 (no silent default assumed here) | 04, 14 |
| Local device compromise (rooted device, other app) | Out of scope for full defence: Keystore raises the bar but cannot protect secrets on a fully compromised device; this limit is documented, not hidden | 16 (documented limitation) |
| Server-side compromise / malicious server output | Terminal output is rendered, never executed locally; escape sequences are parsed by the terminal engine, not passed to a local shell | 06 |
| Dependency supply-chain risk | Every dependency recorded with licence + maintenance evidence; versions pinned; no dependency without a purpose | 02, 16, 17 |

## Explicitly out of scope

- Protecting secrets on a fully compromised (rooted/malware-controlled)
  device beyond what Keystore provides.
- Server-side security of the user's own servers (the app verifies the
  host it connects to; it does not audit the server).
- Anonymity or traffic-analysis resistance (no Tor/VPN claims).

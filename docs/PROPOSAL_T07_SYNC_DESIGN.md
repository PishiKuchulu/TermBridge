# Proposal T-07 — Accounts / sign-in / sync (design only)

**Status: DESIGN ONLY, per the work order. Implementation
requires the owner's explicit written approval, and no
account system is designed here beyond the sync question.**

## The constraint that shapes everything

The product's standing rules: core features need no cloud
account and no paywall, and nothing leaves the device
except the user's own SSH/SFTP/VNC traffic. A first-party
sync service breaks all three (backend to run, accounts to
hold, user data on our infrastructure). Any sync design
that survives the rules must therefore ride on storage the
USER controls.

## What would sync

Hosts, keychain entries, snippets, settings, known-host
records — i.e. exactly the existing backup document. The
encrypted backup format (PBKDF2 + AES-256-GCM) already
exists and is the obvious sync payload: sync never handles
plaintext.

## Design (user-owned storage, file-based)

- **Transport:** a single user-configured WebDAV endpoint
  (Nextcloud, a NAS, any WebDAV server) — or, simplest of
  all, a folder the user points at through SAF and fills
  with any file-sync tool they already run (Syncthing etc.).
  The WebDAV variant is the only one needing new network
  code; the SAF variant needs none.
- **Payload:** one encrypted backup file per device label
  (`termbridge-sync/<device>.tbbackup`) plus a tiny manifest
  with timestamps. Devices pull peers' files, decrypt
  locally with the user's backup passphrase, and merge.
- **Merge rule (v1, honest and simple):** per-section
  last-writer-wins by device timestamp, with conflicts
  surfaced in the import preview (the preview machinery
  already exists) — never silent overwrite of credentials:
  on a credential conflict the incoming item is offered as
  a copy, matching the keychain "(imported)" pattern.
- **Passphrase:** the backup passphrase is the sync key.
  There is no recovery — losing it means losing sync, by
  design; stated plainly in the setup copy.
- **Scheduling:** manual "Sync now" first; any background
  scheduling is a separate decision (it interacts with the
  T-17 foreground-service question).

## Options

1. **Decline sync.** The encrypted backup already covers
   device migration by hand.
2. **SAF-folder variant.** ~3–4 days on top of the backup
   code; zero new network surface; the user brings their
   own file sync.
3. **WebDAV variant.** ~1–2 weeks (client, auth handling,
   TLS posture, conflict UX); the endpoint credential
   becomes a new stored secret class.
4. **First-party cloud/accounts.** Not recommended:
   contradicts the product rules above and creates a
   permanent operating obligation.

## Recommendation

**Option 2 if sync is wanted at all.** It keeps every
standing rule, reuses proven code paths, and its failure
modes are the user's own file tool's, not ours.

## What approval unlocks

A chosen option gets a full design document (data flow,
threat model, merge spec) before any code — this page is
the decision brief, not that document.

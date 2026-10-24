# TermBridge — Key Lifecycle, Backup, and Migration (Prompt 04)

## What is stored, and how

| Item | Storage | Protection |
|---|---|---|
| Host name, hostname, port, username, auth type, notes, tags | Room `hosts` table, plaintext columns | Not secret; a hostname is not a credential, but it is never sent anywhere except to the server the user connects to (prompt 05). |
| Password (password auth) | Room `hosts.credentialBlob` | AES-256-GCM, key in Android Keystore (alias `termbridge_master_key`), fresh IV per encryption. |
| Private key PEM (key auth) | Room `hosts.credentialBlob` | Same Keystore-GCM protection, on top of whatever passphrase protection the PEM itself already has. |
| Private-key passphrase | NOT STORED, anywhere | Asked again at connection time (prompt 05). A passphrase field left blank on edit means "keep the stored key", never "store an empty passphrase". |
| Accepted host-key fingerprints | Room `trusted_host_keys` table | Trust data, not a secret. Fingerprint strings only — no key material. |
| Private key / password in memory | Wipeable buffers where possible | Repository wipes CharArray/ByteArray copies in finally blocks. Residual limitation (below): Compose text fields hold Strings, which cannot be wiped; field strings are cleared on save, but a String copy may linger until garbage collection. This is documented, not hidden. |

## Key lifecycle

- The Keystore key is generated on first credential save, with
  `setUserAuthenticationRequired(false)`. Rationale: a biometric or
  device-credential change must not lock the user out of their own
  hosts. The device lock screen remains the boundary; a fully
  compromised device is out of scope (see THREAT_MODEL.md).
- There is no key rotation in prompt 04. Rotation would require
  re-encrypting every stored blob under a new key and is a deliberate
  prompt-11/18 decision, not a silent background job. If rotation is
  ever added, it must be atomic per host and must never leave a
  plaintext copy behind.
- If the Keystore key is lost or invalidated (device reset, key
  deletion, some OEM behaviours), every stored blob becomes
  undecryptable. The app surfaces this as "stored credential cannot
  be decrypted, please re-enter it" — it never silently deletes the
  host, never re-encrypts with a new key pretending the old secret
  survived, and never falls back to plaintext.
- `AndroidKeystoreSecretCrypto.deleteKey()` exists only for recovery
  when the user re-saves a credential. Deleting the key without
  re-saving destroys access to all stored credentials.

## Backup, restore, and device migration

- `allowBackup = false` (manifest, prompt 02). The Room database is
  NOT included in Android auto-backup. This is deliberate: a backup
  without the Keystore key would restore undecryptable blobs and
  look like data loss, and a backup with a key would be a secret
  export the user never asked for.
- Consequence, stated plainly: reinstalling the app or moving to a
  new device loses stored hosts and credentials. The migration path
  is the encrypted export in prompt 11 (passphrase-encrypted, not
  Keystore-encrypted, so it can move between devices). Until prompt
  11 lands, there is no supported migration — and no claim otherwise
  is made in the app.
- Trust records are endpoint-scoped, not device-bound secrets. They
  are lost with the database on reinstall, which is the safe default:
  a new device must re-verify host fingerprints on first connection.
- BEHAVIOUR CHANGE (security fix 16, finding M2 — this reverses
  the prompt-04 rule): deleting a host now deletes its endpoint's
  trust record with it, in the same transaction as the host
  delete. The old rule ("trust records survive deletion") meant
  a delete-and-re-add cycle silently re-trusted a possibly
  compromised host without ever showing a fingerprint again —
  exactly the scenario where a user deletes a host because they
  suspect compromise. A re-added host now always re-verifies
  with a dialog.

## What is never done

- No secret in plaintext preferences, ordinary database columns,
  logs, analytics, crash reports, clipboard, or exported profiles
  (prompt 11 export is encrypted before it leaves private storage).
- No "accept every host key" mode, in production code or in tests
  shipped in the app. Test doubles that bypass verification live in
  test sources only and are labelled as such.
- No secret in an error message. Repository errors are constructed
  from validation field names and generic text; `SecretRedactor`
  is the second net for free-text fields.

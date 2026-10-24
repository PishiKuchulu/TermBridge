# TermBridge — Import, Export, and Backup (Prompt 11)

## The one supported format

Only one format is supported, in either direction: **TermBridge
backup JSON, version 1**. No PuTTY file, no other profile format,
and no archive format is parsed. A file that is not this format
is rejected, not reinterpreted. Because no archive is ever
opened, the zip-slip and decompression-bomb surfaces do not
exist in this feature at all — there is nothing to defend there,
because there is nothing to attack there.

### Envelope (the file itself)

Non-secret export:

```json
{"format":"termbridge-backup","version":1,"encrypted":false,
 "payload":{...document...}}
```

Encrypted backup:

```json
{"format":"termbridge-backup","version":1,"encrypted":true,
 "kdf":"PBKDF2WithHmacSHA256","iterations":600000,
 "salt":"<base64>","iv":"<base64>","ciphertext":"<base64>"}
```

The ciphertext is the whole document JSON, encrypted (see
below). The envelope carries no host data outside the payload
in encrypted mode — not even host names.

### Document (payload, or decrypted inner JSON)

```json
{"hosts":[{
  "exportId":0,"name":"Home server","hostname":"192.168.1.10",
  "port":22,"username":"admin","authType":"PASSWORD",
  "notes":"","tags":"","credential":null,"hasCredential":true,
  "buttons":[{"label":"Update","category":"","sortOrder":0,
    "mode":"EXECUTE_ONE","template":"sudo apt update","steps":[],
    "variables":[]}],
  "settings":{"remoteWorkingDirectory":"/home/admin",
    "remoteShell":"POSIX"},
  "envVars":[],
  "tunnels":[{"kind":"LOCAL","bindAddress":"127.0.0.1",
    "bindPort":8080,"targetHost":"localhost","targetPort":80,
    "allowNonLoopback":false,"enabled":true,"label":"web"}],
  "proxy":null,
  "jumpHopExportIds":[1]
}]}
```

`exportId` is the file-local identity for jump chains: a chain
references other hosts in the same file by exportId. It is never
a database id, and import assigns fresh ids and remaps chains
through it.

### Encryption parameters (encrypted backups only)

- KDF: PBKDF2WithHmacSHA256, 600,000 iterations, 256-bit key
  (the OWASP PBKDF2-HMAC-SHA256 recommendation).
- Cipher: AES-256-GCM, fresh 16-byte salt and 12-byte IV per
  export, 128-bit tag. Tampering or a wrong passphrase fails
  identically ("wrong passphrase or corrupt backup file") —
  the app does not distinguish the two.
- The passphrase is never stored, never logged, and is wiped
  after use. Losing it makes the backup unopenable: there is no
  recovery, and no back door is provided.
- Minimum passphrase length: 8 characters, enforced in the UI
  and again in the codec.
- This is deliberately NOT the Keystore key: Keystore keys do
  not migrate between devices (prompt 04), so a backup locked
  to one is useless as a migration path. The export key is
  derived from the passphrase, which is the whole point.

## What is exported, and what is not

| Item | Non-secret export | Encrypted backup |
|---|---|---|
| Host name/host/port/username/auth type/notes/tags | Yes | Yes |
| Command buttons (label, mode, template, steps, variables) | Yes | Yes |
| Working directory + remote shell | Yes | Yes |
| Tunnels, proxy, jump chains | Yes | Yes |
| Password / private-key credential | No (flag only: `hasCredential`) | Yes, inside the encrypted payload |
| Environment variable names + values | No (not even names) | Yes, inside the encrypted payload |
| Trusted host keys | Never | Never |
| Local SAF folder URI | Never | Never |
| Private-key passphrase | Never | Never (it was never stored) |

Why the exclusions are what they are:

- **Trusted host keys are never exported or imported.** Trust
  is a decision about a server, made by seeing its fingerprint
  on first connection. Importing trust would let a backup file
  silently vouch for a server the new device has never seen.
  After import, the first connection to each host shows its
  fingerprint again, exactly as for a new host.
- **The local SAF folder is device-specific.** A tree URI from
  one device is dead on another, so exporting it would promise
  a folder that does not exist. After import, pick the folder
  again on the new device.
- **Keystore blobs are never exported.** A Keystore-encrypted
  blob decrypts only on the device that made it. Exporting one
  would silently ship an unusable ciphertext and look like a
  successful backup. In encrypted mode the app instead decrypts
  the credential on the source device and re-encrypts it under
  the export passphrase — the only portable form.
- **Env var names are not in a non-secret export.** A name with
  no value imports as nothing, and names can themselves hint at
  what a server holds. In encrypted mode they ride inside the
  encrypted payload with their values.

## Import rules

1. **Preview first, always.** Import shows host names with
   `username@hostname:port`, counts (hosts, buttons, tunnels,
   env vars), whether the file is encrypted, whether it carries
   credentials, conflicts, and warnings — before anything is
   written. Previews contain names and counts only: no
   credential, no env value, no key material, ever.
2. **Fail-closed parsing.** Wrong format string, wrong version,
   malformed JSON, wrong field types, missing required fields,
   or ANY unknown field at any level rejects the whole file.
   A non-encrypted file containing a credential or an env
   value is rejected outright (a plaintext secret in a file
   that claims to be secret-free is a leak, not a warning).
   Host, button, env, tunnel, and proxy shapes re-run their
   existing validators, so import cannot smuggle in a record
   the app would refuse to save by hand.
3. **Conflict rule (default: skip).** A host collides with an
   existing host when hostname (case-insensitive), port, and
   username all match — the server + account, not the display
   name. By default the imported host is skipped and the
   existing host is untouched. Replacing requires the checkbox
   AND a confirmation dialog, and even then the existing
   credential is replaced ONLY when the backup itself carries
   one (encrypted mode); a non-secret replace keeps the
   existing credential. A credential is therefore never
   silently overwritten.
4. **Jump chains apply last, and only whole.** A chain whose
   hop host was skipped (conflict, no replace) is skipped
   whole with a warning — a partial chain is a different
   route, not a safe subset.
5. **Nothing imported is trusted or executed.** Imported host
   keys are not trusted, imported buttons are stored as data
   and run only when the user presses one, and imported env
   vars are applied only through the normal session mechanism
   (prompt 09), never by import itself.
6. **Limits.** File size at most 5 MB; at most 500 hosts; at
   most 100 buttons, 100 env vars, and 100 tunnels per host.
   Above a limit, the whole file is rejected, not truncated.
   A child record that fails to save during apply becomes a
   warning in the import report, not a silent drop and not a
   whole-import abort.

## Backup, auto-backup, and migration

- Android auto-backup stays OFF (`allowBackup = false`, prompt
  02/04). Export is the backup: a file the user explicitly
  creates, through a file picker they control, in a place they
  choose. There is no scheduled or automatic backup in this
  phase — the pack's "backup" requirement is met by the
  explicit export, and an automatic backup is NOT faked: one
  that silently copied secrets on a schedule would be a leak
  path, not a feature.
- Migration path (the promise from prompt 04's lifecycle doc,
  now delivered): on the old device, create an ENCRYPTED backup
  (it is the only kind that carries credentials and env
  values); move the file to the new device by any channel the
  user trusts; on the new device, import it with the same
  passphrase, then connect once per host to re-establish trust
  and re-pick the local folder. A non-secret export migrates
  structure only — credentials and env values must be entered
  again, and the import report says so.
- Restoring onto a device that already has hosts: conflicts
  are skipped by default (rule 3 above). To replace, use the
  replace option deliberately, with the confirmation dialog.

## Export failures

If a credential or an environment value on the source device
cannot be decrypted (Keystore loss, tampering), the export
fails instead of producing a partial backup: a backup that
quietly drops a secret is worse than no backup, because the
user believes it is complete. The error names the host (and,
for env vars, the variable names) — never a value.

## File access

Storage Access Framework only: `CreateDocument` for export,
`OpenDocument` for import. No storage permission is requested,
no fixed path is used, and the file's MIME type
(`application/json`) is advisory only — the content is what is
validated, so a renamed file with valid content still imports
and a `.json` file with invalid content still fails.

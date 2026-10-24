# TermBridge — Environment Variables and Working Paths (Prompt 09)

## The distinction that matters

These are REMOTE shell settings. They never touch the Android
app's own environment: no System property is set, no
ProcessBuilder environment is changed, no app-level env
manipulation exists anywhere in this codebase. Confusing the two
would be a category error, and the UI never suggests otherwise.

## Mechanism table — what applies where, and what does not

| Surface | Env vars | Working directory |
|---|---|---|
| Interactive terminal | SSH `env` requests via sshj `Session.setEnvVar`, after PTY allocation, before `startShell` (the only order the protocol allows). Per-var result reported by name. | One visible `cd` once per shell, immediately after shell start, before CONNECTED is set. |
| SFTP | NOT applied. SFTP is a subsystem, not a shell; there is no env request to send. | Start directory, but ONLY for POSIX-style paths. Windows-style directories fall back to root (see below). |
| Command buttons | NOT injected. Buttons run in the interactive shell, so they inherit whatever that shell actually has — nothing is added to or removed from their text. | NOT prefixed. The preview is exactly what is sent (prompt 08 guarantee). |
| The app itself | Never. | Never (local folder is a SAF URI, not an app working directory). |

## Why `setEnvVar`, and what "accepted" means

Verified against the sshj 0.40.0 jar (not assumed from memory):
`Session.setEnvVar(String, String)` exists and sends the `env`
channel request with want-reply, awaiting the server's reply.
A rejection therefore raises, and the app records that var's
NAME as rejected; a non-rejection is recorded as applied. This
is the strongest statement the protocol allows — it does not
prove the shell's login profile kept the value. OpenSSH's
default `AcceptEnv` accepts only `LANG` and `LC_*`, so on a
default server most custom vars will be REJECTED, and that is
expected behaviour, not a bug: the session still starts, the
session screen names the rejected vars, and the user can verify
in the shell itself (e.g. `printenv NAME`).

There is deliberately NO fallback that injects `export NAME=...`
text into the shell. That would pollute shell history, break on
non-POSIX shells, hide the server's refusal behind a lie, and
violate the pack's rule against blindly prepending shell text.

Server-side overrides the app cannot control (documented, not
hidden): `AcceptEnv` / `PermitUserEnvironment` policy, login
profiles (`.profile`, `.bashrc`, `.zshrc` sourcing order),
forced commands (`authorized_keys` `command=`), and shell
startup files can all ignore or override a requested value.
The UI text says this plainly; no claim of universal application
is made anywhere.

## The terminal `cd` — the one documented shell-text exception

SSH has no working-directory request, so for an interactive
shell the honest options are a visible `cd` or no initial
directory. The `cd` here is narrow: exactly once per shell,
sent before CONNECTED (terminal input is disabled until then,
so user typing cannot interleave with it), visible in terminal
output like any typed command, and never chained with `&&` —
if the directory does not exist, the shell prints its own error
and the session continues in the login directory. It is never
applied to buttons, SFTP, or the app.

Rendering depends on the per-host remote-shell setting (POSIX /
PowerShell / cmd), which the user chooses — it is NOT
auto-detected, because guessing a remote OS from a hostname
would send broken commands into live shells. POSIX and
PowerShell use a single-quoted literal; cmd uses `cd /d` with a
double-quoted path (double quotes are illegal in Windows names,
so validation already excludes them).

## Paths: POSIX, Windows, and local

- Remote POSIX directories are normalised with the existing
  `RemotePath` (lexical, clamped at root). Stored form is the
  canonical form, never raw user text.
- Remote Windows directories are normalised with `WindowsPath`
  (drive `C:\...` or UNC `\\server\share\...`, separators
  canonicalised, `.`/`..` resolved lexically, clamped at the
  drive/share root, illegal Windows name characters rejected).
  Normalisation is lexical: server-side symlinks or junctions
  may still resolve differently, and that limit is stated, not
  hidden.
- SFTP start directory applies only to POSIX paths. The SFTP
  path dialect on Windows servers is not standardised in this
  app (OpenSSH for Windows, third-party servers, and drive-letter
  mappings differ), so a Windows-style directory is not guessed
  into an SFTP path — browsing starts at root instead. If a
  POSIX start directory cannot be listed (deleted, no
  permission), browsing falls back to root once with a note,
  instead of stranding the user on a dead path.
- Local directories are SAF tree URIs ONLY. A typed filesystem
  path is rejected by validation, because using it would need
  broad storage access this app deliberately does not have.
  The folder is picked with `OpenDocumentTree`, persistently
  permitted BEFORE it is stored (a stored folder without its
  permission would be a dead setting), and used for one thing:
  downloads for that host write straight into it via
  `DocumentsContract.createDocument` (no picker). If the folder
  cannot be written (permission lost, provider gone), the error
  says so and the picker path remains available. Uploads still
  use the system picker — the local folder does not (yet)
  pre-select an upload source, because the OpenDocument contract
  takes no initial directory; that gap is stated, not papered
  over with a picker claim.

## Env values: encrypted, write-only, never logged

Decision (explicit, safer choice): env VALUES are encrypted
with the same Android Keystore AES-256-GCM crypto as host
credentials, one blob per variable, and there is NO plaintext
value column anywhere. Rationale: env values routinely hold
secrets (tokens, keys, DSNs), and a plaintext column would leak
them in any database dump or in a future export. Consequences,
all deliberate:
- The UI shows names and a masked marker only; a value is never
  displayed, logged, put in diagnostics, or included in an error
  message (validation reports the field, never the value).
- Editing a var requires re-typing its value — there is no
  "keep existing value" path, because no stored value is ever
  read back into a form. Only shell startup decrypts values,
  and it wipes the buffers afterwards.
- Names are NOT treated as secrets (they appear in the
  applied/rejected lists and in the variable list).
- A loader-injection blocklist is enforced at save time:
  `LD_PRELOAD`, `LD_LIBRARY_PATH`, `LD_AUDIT`, `LD_DEBUG`,
  `DYLD_INSERT_LIBRARIES`, `DYLD_LIBRARY_PATH`,
  `DYLD_FALLBACK_LIBRARY_PATH` cannot be saved even if the user
  types them, because a permissive server would hand them to the
  loader. `PATH` and similar are allowed: overriding them on
  one's own server is a legitimate user choice.
- A var that fails to decrypt at connect time (Keystore loss,
  tampering) is reported by NAME as rejected; env is advisory,
  so one bad var never blocks the session.
- Prompt 11 (import/export) must treat env values as secrets:
  they may only leave the device inside an encrypted export, and
  whether they export at all is prompt 11's decision — this
  phase adds no export path for them.

## Host lifecycle

- Deleting a host deletes its settings, env vars, buttons, and
  credential (repository delete + Room cascades).
- Duplicating a host copies its SETTINGS (non-secret: working
  directory, shell, local folder) but NOT its env vars
  (secret-bearing) and NOT its credential — the duplicate starts
  with no vars, exactly like prompt 04's credential rule.
  (Prompt 18: the duplicate's copied shell is also UNCONFIRMED
  until its own environment screen is saved — confirmation is
  per-host, not inherited with the copied value. The duplicate
  path constructs fresh settings, whose shellConfirmed
  defaults to false.)
- Shell confirmation (prompt 18, audit-17 R1): `shellConfirmed`
  is true only once the environment screen has been saved for
  that host, where the shell picker is displayed and editable.
  It is NOT exported in backups (the backup format carries
  only the working directory and the shell name), so an
  imported host is unconfirmed until its environment screen is
  saved on the importing device. Rows from before this flag
  migrate to false (DB v4→v5, DEFAULT 0).
- Changes apply on the NEXT connection, never to a running
  session: env requests belong to channel setup and the `cd`
  belongs to shell startup, so there is nothing to "apply live"
  without restarting the shell. The screen says so.

## What is NOT in this phase

- No exec-channel env (buttons and terminal share the
  interactive shell; a separate exec channel is a later
  decision, not a silent substitution).
- No per-button env or working directory (see the mechanism
  table: buttons are exact text by design).
- No auto-detection of remote OS or shell.
- No export of env vars (prompt 11).
- No real-server verification yet: the fixture tests are
  written and ignored (see PHASE_REPORT_09 §3), and the
  sshj-rejection behaviour must be confirmed on a default
  OpenSSH server before any stronger claim is made about
  applied/rejected reporting.

# TermBridge — SFTP Notes (Prompt 07)

## Subsystem requirement

SFTP works only where the server offers an SFTP subsystem
(OpenSSH `sftp-server` / `internal-sftp` by default, or another
SSH server's SFTP implementation). A server with SSH but without
SFTP fails at Files-connect time with "This server does not
offer SFTP" — not with a generic listing error, and not as a
claim that the server is broken. Windows targets without an
SFTP-capable SSH server are in this category.

## Local access

- SAF only: upload sources via OpenDocument, download
  destinations via CreateDocument. No broad storage permission
  (no MANAGE_EXTERNAL_STORAGE, no READ/WRITE_EXTERNAL_STORAGE
  request) is made in prompt 07.
- Local file names come from OpenableColumns.DISPLAY_NAME; if
  unavailable, the fallback name is "upload".
- Download destinations are chosen by the user in the SAF
  picker, so local name conflicts are resolved by the picker
  UI, not by the app overwriting silently.

## Transfers

- Streaming: InputStream/OutputStream with counting wrappers.
  No whole file is held in memory. Large files are therefore
  bounded by sshj's internal buffering, not by the app's heap.
- Progress: bytes transferred, with total when known (listing
  size for downloads, SAF size column for uploads when available).
- Cancellation: a flag checked on every read/write; a cancelled
  transfer throws and the partial download is deleted
  best-effort (DocumentsContract.deleteDocument). A cancelled
  upload may leave a partial remote file — the next upload with
  the same name triggers the conflict dialog, so a partial file
  cannot silently be mistaken for a complete one.
- Integrity: size comparison only (remote stat size vs bytes
  transferred for uploads; bytes written vs listing size for
  downloads). SFTP has no standard checksum extension exposed by
  sshj, so a digest check is NOT claimed. If a future server
  offers the `check-file` extension and sshj exposes it, digest
  verification can be added then, with its own test.
- Conflicts (upload): existing name in the current directory
  triggers a dialog — Overwrite, Upload with new name (auto
  suffix " (1)", " (2)", …), or Cancel (dialog dismiss).
  Download conflicts are picker-resolved (see above).

## Operations and limits

- List, navigate (including Up, clamped at root), create
  directory, rename, delete (single entry), upload, download.
- Delete is single-entry only. A non-empty directory fails with
  a message telling the user to empty it first — recursive
  delete is deliberately not implemented (dangerous, needs its
  own confirmation + traversal design).
- Files browsing starts at "/" (root), not at the user's home,
  because the sshj SFTP API used here exposes no pwd/home
  query. Home-start is a future improvement, not a claimed
  feature.
- Prompt 09 change: browsing starts in the host's remote
  working directory when that directory is a POSIX-style path
  (see docs/ENVIRONMENT_AND_PATHS.md). A Windows-style
  directory is not guessed into an SFTP path — browsing starts
  at root instead. A POSIX start directory that cannot be listed
  falls back to root once, with a note, instead of stranding the
  user on a dead path.
- Prompt 09 change: when the host has a local SAF folder set,
  downloads write straight into it (DocumentsContract.
  createDocument into the picked tree) instead of opening a
  picker. If the folder cannot be written, the error says so
  and the picker path remains available. Uploads still use the
  system picker.
- No root on the phone or the server is required; operations
  respect the account's remote permissions, and permission
  failures surface as "Permission denied", never as a privilege
  escalation attempt.
- Files opens its OWN SSH connection, separate from the
  terminal's. Unifying connections is a prompt-15 integration
  decision, documented in FilesViewModel.

## Note, 2026-11-04 — item T-21 (queue, folders, resume)

Transfers now run through a one-at-a-time queue in
FilesViewModel. Resume design: a download interrupted by
cancel or an IO failure keeps its partial destination file
and resumes with an append-mode stream plus sshj's
offset input stream — progress and the size check stay
total-based, so a resumed download is verified exactly
like a fresh one. An upload cancelled in the single-file
flow keeps its temporary file on the server; resume first
re-stats the temp and trusts it only when its size equals
the recorded offset, then writes the remainder at that
offset — the pre-existing whole-file size verification
before the rename still runs, so a bad resume fails
instead of corrupting. Resume state lives only for the
screen's ViewModel session; leaving Files drops it (a
stranded upload temp is a hidden dot-file next to its
target, one per cancelled upload at most).

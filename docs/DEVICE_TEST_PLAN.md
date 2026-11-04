# Device Test Plan (item F-04)

Manual verification on physical devices. CI proves the
build, the JVM tests, and the emulator fixture suite; the
items here are the ones only a real device in a real hand
can prove. Run on at least two devices / Android versions
for a release (item R-08) and record: device, Android
version, build (commit or release tag), tester, date, and
PASS/FAIL per section in the release notes.

## 0. Setup

- [ ] Install the signed release build (not the debug APK).
- [ ] A reachable SSH server (any Linux box) with password
      AND key accounts; a second server for jump-host tests.
- [ ] A VNC server (x11vnc or a desktop's built-in sharing).
- [ ] A large file (≥ 200 MB) on the server for transfers.

## 1. First run and onboarding (Q-06)

- [ ] Fresh install opens the welcome flow; it is skippable
      at every step and never shown again after finishing
      or skipping.
- [ ] "Add your first host" from onboarding lands on the
      host form.

## 2. Hosts and credentials

- [ ] Add a host with a password; connect; disconnect;
      reconnect (no re-prompt for the password).
- [ ] Add a host with a pasted OpenSSH key + passphrase.
- [ ] Import the same key from a FILE (F-01): the picker
      opens, the fingerprint is shown before saving, a
      wrong passphrase is rejected with a generic message.
- [ ] Import a password text file (F-01) as a password
      credential.
- [ ] Keychain (T-01): create an entry, generate an
      Ed25519 key inside the app, link two hosts to one
      entry, connect both. Deleting the entry is refused
      while linked; "detach and delete" works.
- [ ] Host list (T-22): search, tag filter, group headers,
      favourite pinning, sort modes, multi-select delete,
      duplicate (the copy must NOT inherit last-connected).
- [ ] Wrong password fails with the generic auth error;
      no password is ever visible in any error or log.

## 3. Host keys

- [ ] First connect to a new server shows the OpenSSH
      SHA256 fingerprint dialog; accepting stores it.
- [ ] Reconnect shows no dialog.
- [ ] (Lab) Point the app at a server presenting a
      DIFFERENT key for a known host: the changed-key
      warning appears and the connection does not proceed
      silently.

## 4. Terminal

- [ ] Interactive shell: run vim or top (full-screen app),
      resize by rotating the phone — the app redraws at the
      new size and the remote app follows (Q-02).
- [ ] Type with the soft keyboard incl. Backspace; use the
      ⌫ button; hardware keyboard if available (Tab,
      arrows, Ctrl+letters).
- [ ] Extra-keys bar (T-09): Ctrl latch once (next key
      only), lock (persists), F1–F12 row.
- [ ] Selection: long-press drag selects; Copy puts the
      text on the clipboard; tap a URL in output — it
      opens in the browser (Q-03).
- [ ] Scroll back while output streams: the view does not
      jump; the "jump to latest" chip appears and works.
- [ ] Stress: `cat` a 50 MB file — the app stays
      responsive, no OOM, scrolling still works (Q-03).
- [ ] Bell: `printf '\a'` — haptic fires; with sound on, a
      beep; with flash on, a brief flash (T-10).
- [ ] Cursor: in Settings switch block/underline/bar and
      blink; run an app that sets DECSCUSR (vim) — the
      app-chosen cursor wins while it runs (T-12).
- [ ] Themes (T-08): all 8 built-ins render readably; a
      custom theme can be created, exported, imported.
- [ ] Persian UI: switch the PHONE to فارسی — the app is
      fully Persian and RTL-correct (Q-05); type Persian
      into the shell via the IME (T-13); dictate a command
      by voice (T-13).
- [ ] Volume keys / shake (T-19): with an action mapped,
      it fires in a live session only; with the default
      (disabled), the keys keep their system behaviour.
- [ ] Full-screen mode (Q-01): enter/exit; system bars
      hide and return; the tab bar is gone on the session
      screen.
- [ ] Autocomplete (T-11): enable it, run commands, see
      suggestions on the next prefix; import shell history
      from the session screen (consent dialog first) and
      confirm suggestions grow; a line containing a token
      assignment is never suggested.

## 5. Command buttons and snippets

- [ ] Insert-only button types without executing.
- [ ] Execute-one runs after the parameter dialog; a
      risky command asks for confirmation in every mode.
- [ ] A sequence runs its steps in order.
- [ ] Change the host's shell AFTER saving buttons: the
      stale-shell gate blocks execution until the shell is
      re-confirmed (remediation phase 1).
- [ ] Global snippets (T-02) appear for every host and in
      quick-connect sessions.

## 6. Environment and files

- [ ] Set env vars and a working directory; the session
      starts in the directory and `env` shows the values.
- [ ] On a server WITHOUT matching AcceptEnv: values are
      reported refused; with the export fallback enabled
      (F-02) they are typed as visible export lines.
- [ ] SFTP: browse, upload, download (incl. into the
      host's local folder), rename, delete file; deleting
      a non-empty directory is refused with a clear error.
- [ ] Transfers (T-21): queue two downloads; cancel one
      mid-way and resume it; multi-select delete; upload a
      whole folder; "Open with" on a downloaded file.
- [ ] Quick connect (T-04): `user@host` connects without
      saving; nothing about it persists after disconnect.

## 7. Advanced SSH

- [ ] Local forward to a server-side service; browse it
      from the phone; stop the single forward from the
      active list (T-20) — the listener closes.
- [ ] Remote forward; jump-host chain through the second
      server; SOCKS proxy profile connects.
- [ ] Detect OS (T-16) shows the right distro; with the
      auto setting on, it appears without tapping.

## 8. Remote desktop (F-03)

- [ ] VNC connect (password and no-password servers);
      wrong password is rejected.
- [ ] Pinch zoom + pan; tap = left click; long-press =
      right click; middle-click and wheel buttons work;
      sticky Ctrl+click selects multiple items.
- [ ] Send clipboard text to the remote.
- [ ] Connect through an SSH host (tunnel) to a VNC server
      that only listens on the server's localhost.
- [ ] Save a desktop profile; reopen it; the VNC password
      is NOT stored (the form asks again).

## 9. App lock, backup, transcripts

- [ ] PIN lock: set, lock on background after the chosen
      delay, wrong PIN throttling, biometric unlock.
- [ ] Pattern lock (T-18): set (two identical strokes),
      unlock, wrong pattern message; "Lock now" works;
      with cover-recents on, the recents thumbnail is
      blanked.
- [ ] Backup: export encrypted; wipe the app data;
      import; hosts, keychain links, snippets and settings
      return. A plain export contains no credentials —
      verify by importing it on a second device and seeing
      credential-less hosts reported, not created.
- [ ] Transcript (T-03): enable for one host, run a
      session, confirm the transcript exists on-device
      (export an encrypted backup WITH the opt-in and see
      it counted in the preview), and that a session on
      another host recorded nothing.

## 10. Lifecycle and edges

- [ ] Rotate during a live session: the session survives.
- [ ] Background the app mid-transfer for a minute and
      return: state is consistent (no zombie progress).
- [ ] Kill the app from recents mid-session and relaunch:
      clean start, no corrupt state.
- [ ] Airplane mode during a session: a clean error, and
      reconnect works when the network returns.
- [ ] API 26 device (or the oldest available): install
      and run sections 2–4 (crypto paths are
      version-sensitive).

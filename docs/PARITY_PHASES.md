# Termius-parity work (phases 1-4)

Branch `feature/termius-parity`, merged after CI. What exists, what was
verified, and what was deliberately left out.

## Delivered
- **Settings** (persisted, all effective): font size, scrollback, TERM
  type, SSH keep-alive, keep screen on, block screenshots, terminal colour
  scheme (3), bell haptic.
- **Navigation:** Vaults / Connections / Settings. Vault: Hosts, Port
  forwarding, Snippets (command buttons), Known hosts (list + forget),
  Logs (local connection history, no terminal output).
- **Connections:** quick connect (pre-fills the add-host form; never
  dials), discover local devices (22/5900/3389, one /24 at most),
  SFTP, remote desktop.
- **Terminal:** proportional height, two-finger pinch zoom.
- **App lock:** PIN (PBKDF2, salted, throttled) + optional biometric.
  A convenience lock: stored credentials stay Keystore-encrypted either way.
- **Remote desktop (VNC):** touch-as-mouse, key row, Unicode (Persian)
  typing. RDP remains unsupported (docs/RDP_NOTES.md).

## Verified
- Unit tests (JVM) for every new pure component (quick-connect parser,
  subnet planner, history codec, PIN rules, settings rules, key mapping).
- Emulator suite: SSH password + key auth, SFTP, shell, command buttons,
  UI smoke test.

## NOT verified
- VNC against a real server: the integration tests exist
  (`RfbIntegrationTest`) but are skipped in CI. Adding the VNC server to the
  CI fixture (xvfb + x11vnc) made the emulator job hang twice, so it was
  reverted. VNC is therefore unproven end-to-end.
- Nothing has run on a physical phone.

## Not implemented
Keychain, customizable extra-keys bar / hotkeys, immersive terminal layout,
serial, account/sync, autocomplete and shell-history import, local terminal,
OS detection, connection notifications, volume-key and shake actions.

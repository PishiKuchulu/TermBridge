# TermBridge — Terminal Emulation Limits (Prompt 06, updated Prompt 14)

Honest scope for the prompt-06 VT engine. Anything not listed as
supported is ignored (consumed, never rendered as garbage), unless
stated otherwise.

## Supported

- Printable characters, CR, LF, backspace, tab (8-column stops),
  bell (ignored), NUL (ignored).
- CSI cursor moves: A/B/C/D, H/f (position), d (row), G (column),
  save/restore (s/u, ESC 7/8).
- Cursor style (2026-11-04, item T-12): DECSCUSR (CSI Ps SP q —
  blinking/steady block, underline, bar) and cursor visibility
  (CSI ?25h/l). Until an app sets a style, the user's Settings
  default (shape + blink) applies; a full reset (ESC c) returns
  to the user default.
- Erase: J (0/1/2/3, including scrollback clear on J 3), K (0/1/2).
- SGR: reset (0), bold (1, 22), standard colours (30–37, 40–47),
  bright colours (90–97, 100–107), default colours (39, 49).
  Extended colours (38/48, 256-colour and true-colour) are parsed
  and ignored — text renders in the default colour.
- Scrollback: user-configurable, 100–50,000 lines (default
  1,000; item T-23), oldest dropped first. Total memory is
  bounded by scrollback + screen size.
- Alternate screen (CSI ?1049h / ?1049l, prompt 14): entering
  saves the main screen grid, cursor, and saved-cursor slot and
  switches to a blank screen; exiting restores the main screen
  exactly. Scrolling on the alternate screen discards lines
  instead of adding them to scrollback (an app's transient
  output is not shell history). Double-enter and stray-exit are
  no-ops, and resize resizes the saved main screen too. This
  removes the largest prompt-06 limitation: vim, less, and htop
  now draw on their own screen instead of into the shell's.
  Display note: the list is still scrollback + current screen,
  so scrolling up while an app runs shows old shell history
  above the app's screen; the visible bottom is the app.
- Auto-scroll (prompt 14): new output scrolls to the bottom
  only while the user is already at the bottom. Scrolling up
  unpins the view; new output then shows a "Jump to latest (N
  new lines)" action instead of yanking the view, and scrolling
  back to the bottom re-pins it.
- PTY: `xterm-256color`, size set at shell start only. Resize
  updates the local parser grid; sshj 0.40.0 has no
  window-change API, so the server-side PTY size does not
  follow a later resize (CI run #6 fix).
- Input: typed characters sent as typed, Enter (CR), Ctrl-C
  (0x03), Ctrl-D (0x04), Tab, Escape, arrow keys (CSI A/B/C/D),
  backspace (DEL 0x7F).
- Selection/copy: the Canvas renderer (2026-11-04, item Q-03)
  supports long-press drag selection with a Copy action, and
  tap-to-open for http(s) URLs drawn underlined.
- Paste: never sent automatically. A paste (button or field)
  opens a confirmation dialog showing the exact text; only an
  explicit Send tap submits it, including any newlines.

## Not supported (documented, not hidden)

- Alternate screen via the older CSI ?47: still ignored (only
  ?1049 is implemented; ?47-only programs are rare, and adding
  a second save slot would double the edge cases for no real
  program — most full-screen apps use ?1049).
- Mouse reporting, bracketed paste, focus events: ignored.
- Reflow on resize: lines are truncated/padded, not reflowed.
  The PTY size is measured, not approximated (2026-11-04, item
  Q-02): the real monospace cell is measured with the same text
  style the grid is drawn with, and the exact cols/rows are
  sent to the shell.
- UTF-8: decoded correctly via InputStreamReader (multi-byte
  characters split across reads are handled). Combining characters
  and wide (East Asian) characters are treated as one cell each,
  so their alignment may be off by one column.
- Background/foreground lifecycle: no background service. The
  session survives tab switches (activity-scoped ViewModel) but
  not process death; on return after process death, the user
  reconnects.
- Shell startup: the shell is the server's default login shell
  for the authenticated user. No custom shell command is offered
  in prompt 06 (that is a prompt-09 environment concern, if at all).
- One shell session per connection in prompt 06. Multiple
  simultaneous sessions to the same or different hosts are not
  offered yet.

# TermBridge — Phase Report, Prompt 06

Date: 2026-10-24. Phase: 06 — Interactive SSH Terminal.
Process: autonomous continuation per the user's instruction
(no per-prompt widget).

## 1. Actual changes and touched files

No new dependency. The terminal is own code (ADR-04: own scoped
Kotlin VT engine; ConnectBot termlib NOT adopted, licence
unverified — unchanged).

Terminal (`terminal/`):
- `VtParser.kt` — screen grid + capped scrollback (1,000 lines,
  oldest dropped first; memory bounded by scrollback + screen),
  printable/CR/LF/backspace/tab, CSI cursor moves (A/B/C/D/H/f/d/G),
  erase J/K, SGR colours/bold/reset, save/restore cursor,
  ESC 7/8/c/M. Unsupported sequences (OSC, private modes,
  alternate screen, extended colours) are consumed and ignored,
  never rendered as garbage. Resize truncates/pads, no reflow.
- `TerminalSession.kt` — sshj shell channel: PTY
  `xterm-256color`, UTF-8 reader (InputStreamReader, so split
  multi-byte characters decode correctly), parser -> snapshot
  StateFlow, raw-byte input (sendText/sendByte/Enter/Ctrl-C/
  Ctrl-D/Tab/Escape/arrows), resize (parser + sshj
  `setWindowSize`), close (channel only, not transport). No log
  call exists in this file, by design.

Session wiring:
- `SessionViewModel` — after transport auth, opens a real shell
  channel (fails cleanly if the connection is not an sshj
  connection, instead of faking a terminal); terminal snapshot
  forwarded as StateFlow; input/resize/disconnect methods;
  onClosed sets a fixed "Shell closed by the server" /
  "Connection lost" message (never an exception message).
  Reconnect after a shell close reuses the connected host when
  no host is newly selected.
- `TermBridgeNavGraph` — session ViewModel is now
  activity-scoped (explicit prompt-06 decision): navigating to
  another tab does NOT disconnect; the session survives tab
  switches and reattaches on return. Disconnect is explicit or
  on activity destruction. Process death still loses the session
  (no background service) — documented, not hidden.
- `SessionScreen` — terminal view when a snapshot exists:
  SelectionContainer + LazyColumn display with ANSI colours,
  bold, and a reverse-video cursor; auto-scroll to newest output
  (documented limitation: it jumps back to the bottom even if
  the user scrolled up); PTY size from measured area (~8dp/char,
  ~20dp/line at 13sp, approximation documented); keyboard-capture
  input field (typed characters go to the shell as typed, field
  stays empty — it is not a command line); control buttons
  (Ctrl-C/D, Tab, Esc, arrows, Paste, Keyboard); paste
  confirmation dialog for both the Paste button and pasted text
  in the field (newlines trigger the dialog, never auto-send);
  backspace sends DEL per deleted character.
- Strings: terminal input hint, Paste/Keyboard/Send, paste
  dialog title/body added; the old terminal-placeholder string
  removed (no longer used).

Documentation:
- `docs/TERMINAL_LIMITS.md` — supported vs unsupported list,
  including the largest limitation (alternate screen ignored, so
  vim/less/htop render into the main screen), no reflow, PTY size
  approximation, auto-scroll behaviour, wide-character alignment,
  no background service, one session per connection.

## 2. Commands executed and exact results

- Static verification (passed): string-reference check — 0 missing
  after removing the now-unused placeholder string (only
  Kotlin-unused string is `app_name`, used by the manifest);
  no logging call in terminal/ViewModel sources.
- No Gradle build, no test run: NOT RUN, same sandbox reasons.
  CI is still the first real build/test gate.

## 3. Tests added/run

- JVM unit tests (written, NOT RUN locally): `VtParserTest`
  (9 cases — text/newline, backspace/tab, wrap+scrollback cap,
  CSI cursor/erase, SGR colours, unsupported sequences ignored,
  erase display, resize truncation, scrollback oldest-first).
  Three test expectations were corrected during review (cursor
  overwrite result, scrollback newest-line identity, snapshot
  size formula) — the parser code was not changed to fit the
  tests; the tests were fixed to match the parser's documented
  behaviour, and the reasoning is recorded here.
- Instrumented integration: one new ignored case in
  `SshIntegrationTest` (interactive shell streaming several
  commands), same fixture blocker as prompt 05.
- Run: none. Pass/fail: NOT RUN (unit) / IGNORED (integration).

## 4. Build status and environment limitations

- Build status: NOT BUILT anywhere yet.
- Compile risks to watch on the first CI run: sshj Session API
  (`startSession`, `allocatePTY`, `startShell`, `setWindowSize`,
  `inputStream`/`outputStream`/`close`), activity-scoped
  `viewModel(viewModelStoreOwner = activity)`, BoxWithConstraints
  size arithmetic, SelectionContainer + LazyColumn composition.
- Real-session manual test: NOT DONE (no device, no server).
  Device/API version and exact test steps cannot be recorded
  until that run exists — they are not invented here.

## 5. Security properties: proven vs assumed

- Proven by construction: no sensitive output is logged (no log
  call in terminal code); paste never auto-executes (dialog gate
  for both paste paths); error messages are fixed strings.
- Proven by tests (once CI runs them): parser behaviour for the
  supported sequence set, scrollback bounding.
- Assumed, not proven: sshj PTY/shell behaviour on Android and
  on real servers; keyboard-capture behaviour with real software
  keyboards (IME Enter handling varies by keyboard app); PTY
  size approximation accuracy on real devices.

## 6. Requirements implemented vs partial vs blocked

- Implemented (code scope): real shell channel, streaming output,
  typed input, Enter/Ctrl-C/Ctrl-D/Tab/Esc/arrows, resize (grid +
  PTY), scrolling (LazyColumn + scrollback), selection/copy,
  paste with explicit submit, keyboard focus, visible connection
  state, explicit disconnect, shell EOF / disconnect / network
  loss handling (onClosed -> fixed message + reconnect path),
  bounded scrollback, documented emulation limits.
- Partial: shell vs one-shot distinction is real (shell channel,
  not a command field) — but one-shot command execution itself
  is prompt 08's command buttons, not this phase; auto-scroll
  behaviour is crude (see limits doc); alternate-screen apps
  are unsupported.
- Blocked: build, test execution, real-session manual test
  (see §3/§4).
- Not implemented, correctly: SFTP (prompt 07), command buttons
  (prompt 08), environment/paths (prompt 09), advanced SSH
  (prompt 10).

## 7. Evidence for claimed capabilities

- No interactive session is claimed to have run. Claims with
  evidence are static checks (§2) and the test list (§3).
  Everything needing a run is labelled NOT RUN / IGNORED.

## 8. Remaining issues and next prompt

- Prompt 07 — SFTP File Manager: SFTP client over the same
  transport, directory listing, upload/download, and the Files
  screen becoming real (only where the server offers SFTP).
- Open decisions carried forward: repository + CI push,
  application ID confirmation, UI-language confirmation (before
  prompt 14), SSH integration fixture, session-survival across
  process death (future, needs a service decision).
- Continuing autonomously to prompt 07 per the user's instruction.

# TermBridge — Mobile UX, Accessibility, and Performance Notes (Prompt 14)

Phone-first polish pass over the features that are real. Nothing
here changes protocol or security semantics, and nothing here
presents RDP or VNC as working features — their rows stay
exactly as prompts 12 and 13 left them.

## 1. What changed in this phase

### Terminal: alternate screen (the largest prompt-06 limitation, closed)

`VtParser` now implements CSI ?1049h / ?1049l. Entering saves
the main screen grid, cursor, and saved-cursor slot and switches
to a blank screen; exiting restores the main screen exactly.
Scrolling on the alternate screen discards lines instead of
adding them to scrollback (an app's transient output is not
shell history). Double-enter and stray-exit are no-ops, and
resize resizes the saved main screen too, so exiting after a
rotation does not restore a stale-size grid. vim, less, and
htop now draw on their own screen instead of into the shell's.

Still not supported, honestly: CSI ?47 (the older alternate
screen — only ?1049 is implemented), mouse reporting,
bracketed paste, focus events, reflow on resize, wide/combining
character alignment. Display note: the list is still scrollback
+ current screen, so scrolling up while an app runs shows old
shell history above the app's screen; the visible bottom is the
app. Full list: docs/TERMINAL_LIMITS.md.

### Terminal: pinned auto-scroll

Prompt 06 scrolled to the bottom on every new output, yanking
the view even while the user read history. Prompt 14 sticks to
the bottom only when already at the bottom (pure policy in
`terminal/TerminalScroll.kt`, JVM-tested): scrolling up unpins
the view, new output shows a "Jump to latest (N new lines)"
action with an honest count, and scrolling back to the bottom
re-pins. The policy functions decide from numbers only; the
view owns the pinned flag from LazyListState layout info.

### Touch targets: whole-row selection everywhere it is unambiguous

Every single-choice radio row is now tappable across the whole
row at 48dp minimum: host pickers (Session, Files, Commands),
auth-type rows (Host form), mode rows (command form), shell
rows (Environment), export-mode rows (Backup). The radio keeps
its own onClick for accessibility semantics; selecting twice
is idempotent. Two deliberate exceptions, documented not
missed: the tunnel-kind and proxy-kind rows in AdvancedScreen
put TWO radios in one row, so a whole-row tap would be
ambiguous — each radio there is itself 48dp, which meets the
target. AdvancedScreen's host and jump-chain rows were already
whole-row clickable (prompt 10) and were left alone.

Primary/Secondary actions already guaranteed 48dp (prompt 03
shared components); IconButtons are 48dp by default. No
touch-target regression was found in the audit below.

### Live session is no longer invisible

Leaving the Session screen never disconnected (activity-scoped
ViewModel, prompt 06), but a live session was invisible from
every other tab. The nav graph now hoists that same
activity-scoped instance (no second instance, no new
connection — connect() is still only an explicit tap) and
shows a terminal action in the top bar while connected and
not already on the Session screen; it navigates back to the
live terminal. This also resolves the prompt-06 "session-aware
back" item: back never killed the session, so a confirmation
dialog would have been wrong — the missing piece was the way
back, and that is what was added.

### Errors where the user is

- Command form dialog: a failed repository save is now shown
  inside the dialog (it already stayed open on failure — the
  ViewModel clears the form only on success — but the error
  itself only showed on the screen behind it). Opening a fresh
  form clears a stale error.
- Diagnostics: the screen previously hardcoded "no session
  feature exists yet", which stopped being true in prompt 06.
  It now receives the real live-session flag from the nav
  graph, so the line can never go stale again.

### Strings hygiene (localisation readiness, not a translation)

- Terminal control labels (Ctrl-C, Ctrl-D, Tab, Esc) moved
  from hardcoded literals into strings.xml; arrow symbols stay
  symbols (language-independent, not words).
- SFTP transfer direction ("Upload"/"Download") was a
  hardcoded ViewModel string shown verbatim; the screen now
  maps the ViewModel's exactly-two values to the existing
  upload/download strings.
- Diagnostics labels ("Device:", "Android:") moved into
  strings with format arguments.
- After this pass, the only remaining hardcoded user-visible
  literals are data (hostnames, usernames, ports, file names,
  sizes) and the bullet separator in the risky-dialog reason
  list — no prose. That is the whole localisation surface:
  translating strings.xml is a translation pass, not a rewrite.

### Keyboard and rotation

- The NavHost now applies IME padding in addition to Scaffold
  padding, so an open software keyboard does not cover the
  input field at the bottom of a screen. No
  windowSoftInputMode is set (default behaviour); the
  combination is standard and harmless under adjustResize.
- Rotation: every screen scrolls vertically inside Scaffold
  padding, so portrait and landscape show full content; the
  terminal re-derives PTY size from the measured area on
  resize (prompt 06) and now also resizes the saved alternate
  screen's main screen (this phase).

## 2. Screen-by-screen audit (read, not run — no device here)

| Screen | Findings this phase |
|---|---|
| Hosts | List already lazy with stable keys; icon actions all labelled; empty state + error shown. No change needed. |
| Host form | Auth rows now whole-row tappable; per-field errors already under fields; credential separation unchanged. |
| Session | Pinned auto-scroll + jump action; control labels centralised; host picker whole-row; live-session return action in top bar. Paste confirmation, risky dialog, trust dialog untouched (see §4). |
| Files | Host picker whole-row; transfer direction via strings; streaming + progress + cancel unchanged; single-entry delete unchanged. |
| Commands | Host picker + mode rows whole-row; save error inside dialog; management-only separation untouched (never sends to a shell). |
| Environment | Shell rows whole-row; encrypted-value notes unchanged; no value ever displayed. |
| Advanced | Host/chain rows already whole-row clickable; kind rows documented above; blocked-features section untouched. |
| Desktop | RDP/VNC rows untouched — prompts 12/13 verdicts stand, no polish applied to blocked features. |
| Backup | Export-mode rows whole-row; SAF-only flow, preview, replace confirmation untouched. |
| Settings | Navigation only, no decorative toggles — unchanged, correctly. |
| Diagnostics | Real device facts only; live-session line now real; no hostname/credential/command text. |
| About | Independence statement + foundation libraries only — unchanged. |

Accessibility notes: all interactive icons carry a
contentDescription except one decorative file-type icon in
FilesScreen (null, correct for a redundant glyph next to the
file name). Text wraps by default (no fixed-width prose);
colours are Material 3 defaults in both light and dark
(system theme followed); focus order is composition order
(no custom traversal that could break TalkBack). Contrast and
TalkBack order on a real device are device-check items (§5),
not claimed here.

## 3. Performance hygiene (structural, no invented numbers)

- Scrollback stays capped at 1,000 lines (prompt 06); the
  alternate screen adds one more grid of the same size, so
  total memory is still bounded by scrollback + two screens,
  never by output volume.
- SFTP still streams (prompt 07); no file is held in memory.
- Lists are LazyColumn with stable keys (hosts, files) or
  index-keyed terminal lines; only visible lines compose.
- The terminal publishes a full snapshot per read chunk, but
  StateFlow conflates bursts and LazyColumn composes only
  visible items, so per-chunk cost is a list-reference copy
  plus visible-line rendering — no per-chunk full re-render
  of 1,000 lines. No memoisation was added: parser lines are
  mutated in place, so caching a rendered line by reference
  would show stale text; the honest cost is documented here
  instead of "optimised" away.
- No analytics or telemetry exists anywhere in the app
  (prompt-14 pack requirement); diagnostics shows device/app
  facts only, never hosts, usernames, commands, filenames,
  or secrets.

## 4. Security paths: verified untouched in behaviour

- Paste confirmation: both paths (Paste button, pasted text
  in the field) still gate on the dialog; only an explicit
  Send submits, including newlines. SessionScreen's paste
  code was not modified.
- Risky-command confirmation: ParameterDialog → risky
  dialog → execute flow unchanged; the dialog error change
  touches only where a repository error is displayed.
- Trust dialog: unchanged; host-key verification semantics
  untouched.
- Credential handling: no new logging, no new display of
  secrets, no new permission. Permissions remain INTERNET +
  ACCESS_NETWORK_STATE; allowBackup stays false.

## 5. Manual checklist for the device run (pack acceptance)

Run on a real device or emulator; record device, Android
version, and screen size with the outcomes (none of this has
been run — see the phase report):

1. Add host → connect → trust dialog → terminal input,
   Ctrl-C, paste with newlines (dialog appears, nothing
   auto-sends) → scroll up while output streams (view stays,
   jump button shows a count) → tap it → scroll to bottom
   re-pins → open vim, edit, quit (shell screen restored
   exactly) → disconnect → reconnect.
2. Rotate during a live session (terminal resizes, no
   crash, PTY size follows); rotate inside vim (exit
   restores the shell at the new size).
3. Keyboard open over the terminal input (field visible,
   not covered); keyboard open over the host form.
4. SFTP: navigate, upload, download with progress, cancel
   mid-transfer (partial file removed), rename, delete
   confirmation, non-empty directory delete refusal.
5. Command button: create with parameter, preview shows
   exact quoted text, insert-only never submits, risky
   command asks twice, failed save shows inside the dialog.
6. Leave a live session for another tab: top-bar terminal
   action appears; tap returns to the live terminal;
   Diagnostics shows the live session; back from Session
   does not disconnect.
7. Background/foreground the app mid-session and
   mid-transfer; kill and relaunch (session is lost by
   design — reconnect path, no fake restore).
8. Low-memory path: stream 1,000+ lines (scrollback caps,
   oldest dropped); large SFTP transfer (streaming, no
   memory growth claim without a profiler run).
9. TalkBack pass over Hosts, Session, Files, Commands:
   every control labelled, focus order sane, no clipped
   text at the largest font scale.
10. Dark theme + largest font scale over every screen: no
    clipping, no overflow, no unreadable contrast.

## 6. UI-language decision: STILL OPEN, awaiting Farzad

English v1 is unchanged. This phase centralised the last
hardcoded prose so a Persian translation is a translation
pass, and documented RTL readiness: Compose mirrors layout
automatically from locale, strings are the only prose
surface, and terminal content itself is LTR by protocol
nature (shell output is not flipped). No values-fa was
created, no default was switched, and no decision is
claimed — English-first vs Persian-first still belongs to
Farzad, and prompt 15 must not assume an answer either.

## 7. Carried forward (not fixed here, honestly)

- ?47 alternate screen (rare; ?1049 covers modern apps).
- Reflow on resize (documented prompt-06 limit).
- Wide/combining character alignment (documented).
- Room transaction for button-child replacement (prompt 08
  item — prompt 15's scope, not a UX fix).
- Room-testing for the v2→v3 and v3→v4 migrations.
- Upload picker starting in the local folder (blocked on
  the OpenDocument contract — prompt 09 item).
- On-device SAF picker + encrypted round-trip run
  (prompt 11 item).
- All device-dependent checks in §5 (no device here).

# TermBridge — Custom Command Buttons (Prompt 08)

## Modes, strictly separate

| Mode | What happens on the action tap |
|---|---|
| Insert only | Rendered text is sent to the shell input with sendText only. No Enter is sent, by any code path. The user must press Enter themselves. |
| Execute one | Rendered text + one Enter, exactly once, after the parameter/preview dialog (and a risky dialog when flagged). |
| Sequence | Rendered steps, in order, each text + Enter, with a 1-second pause between steps. Progress shows "step N of M sent". |

Nothing runs on button creation, selection, import, or screen
opening. There is no import/export in this phase (prompt 11 owns
it, validated, as the pack requires).

## Parameters and shell quoting

Placeholders are `{{name}}` (letters, digits, underscore, must
not start with a digit). Every placeholder must be a declared
variable; undeclared placeholders block the save, and values
supplied for undeclared variables are ignored, never interpolated.

Documented shell context (corrected by security fix 16,
finding H1 — the old text claimed a POSIX shell
unconditionally, which was false once prompt 09 let a host
declare cmd.exe or PowerShell): quoting and risky
classification are PER SHELL, using the connected host's
declared shell:
- POSIX: values are single-quote escaped (`'` becomes `'\''`).
- PowerShell: values are single-quote escaped by doubling
  (`'` becomes `''`), PowerShell's single-quoted literal rule.
- cmd.exe: there is NO safe general quoting, so a button WITH
  parameters cannot be prepared for a cmd.exe host at all —
  the parameter dialog shows the reason and the action stays
  disabled. A button WITHOUT parameters needs no quoting
  (rendered text is exactly the author text) and still works
  on cmd.exe.
Templates must NOT quote a placeholder themselves — the
preview shows the exact quoted text that will be inserted or
sent, so quoting is visible. On POSIX and PowerShell, a value
containing spaces, semicolons, `$()`, or backticks therefore
becomes one single-quoted shell word, not new shell syntax.
The risky-command heuristic is likewise shell-aware: POSIX
patterns on POSIX, Windows patterns (del, format,
Remove-Item -Recurse, Stop-Computer, …) on cmd.exe, and both
on PowerShell (which aliases `rm` to Remove-Item). The
heuristic remains a heuristic, not a guarantee, on every
shell.

Unconfirmed shell (prompt 18, audit-17 R1): the per-shell
guarantee above holds for the DECLARED shell, and the shell
is never auto-detected. A host whose environment screen
was never saved has `shellConfirmed = false`, and its
stored POSIX value is an untouched default, not a choice.
For such a host, a parameterised button shows a warning
in the parameter dialog — parameter values are quoted for
a POSIX shell, and if the host actually runs cmd.exe that
quoting does not protect values — and names the fix (set
the host's shell in Environment and save). The warning
does not block: the user may still run. Saving the
environment screen confirms the shell (whatever value the
picker holds). Pre-existing rows migrate to unconfirmed
(DB v4→v5, DEFAULT 0), and backup import yields
unconfirmed, since confirmation is device-local and is
not part of the backup format.

Parameter values typed at execution time are never persisted.

## Risky-command heuristic (not a classifier)

Flagged, requiring a second confirmation dialog:

- Recursive or forced delete (`rm` with `-r`/`-f`)
- Delete of `/`, `~`, or a system path
- Shutdown / reboot / poweroff / halt
- Filesystem creation or disk overwrite (`mkfs`, `dd`, `shred`, `wipefs`, `fdisk`, `parted`)
- Privilege escalation (`sudo`, `su`, `doas`, `pkexec`)
- Recursive permission/ownership change on a system path
- Kill-all / kill process group
- Fork bomb pattern
- Download piped directly to a shell (`curl|sh`, `wget|sh`)
- Firewall flush, or stopping sshd/ssh/networking services
- Write to a device (`> /dev/sd...`)
- History wipe (`> ~/.bash_history`)

The dialog states plainly that detection is a heuristic and can
miss dangerous commands (aliases, encoding, indirection). A
command that is not flagged is NOT claimed safe.

## Sequence stop and error semantics (stated, not hidden)

An interactive PTY shell does not report per-step exit status to
the app, so a sequence does NOT auto-stop on first error. The
shell's own output in the terminal is the error surface. Stop
prevents further steps from being submitted; steps already sent
keep running on the server until the user interrupts them
(Ctrl-C in the terminal). The stopped state shows where the
sequence stopped.

## Secrets in snippets

Saving is BLOCKED (not just warned) when a template, step, or
variable default matches an obvious-secret pattern: private-key
PEM, `password`/`passwd`/`secret`/`token`/`api-key` assignment,
bearer token, GitHub token prefix, AWS access-key prefix, or a
credential flag (`--password <value>`). Labels are shown, never
the matched secret. This is a heuristic: it cannot see secrets
hidden behind variables or encoding, and it may flag a benign
string. No perfect detection is claimed.

## Persistence and scoping

Buttons are per-host (hostId). Deleting a host deletes its
buttons (repository + Room cascade). List items are shallow
(label/category/mode/order); the full button (steps + variables)
is loaded before any edit or execution dialog. Save replacement
of steps/variables is sequential, not a single Room transaction
— a crash mid-save could leave partial children. That is a
documented pre-release limitation for prompt 15 to close.

## What is not in this phase

One-shot (non-shell) command execution: execution here uses the
live interactive shell channel, exactly as the terminal does.
A separate exec channel, if ever needed, is a later decision —
it is not silently substituted.

Prompt 09 interaction (explicit, not a silent rewrite):
environment variables and working directories NEVER modify a
button's text. The preview shown before Insert/Run is exactly
what is inserted or sent, with no `cd` prefix and no env
prefix. Buttons run in the interactive shell, so they inherit
whatever environment that shell actually has (including vars
the server accepted at session start) — but nothing is added to
or removed from the button itself. A user who wants a directory
for one button writes it in the button, visibly. See
docs/ENVIRONMENT_AND_PATHS.md for the full mechanism table.

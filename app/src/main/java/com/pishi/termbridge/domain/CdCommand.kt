package com.pishi.termbridge.domain

/**
 * Prompt 09 terminal startup: the single `cd` that applies a host's
 * initial working directory to a fresh interactive shell.
 *
 * Why a `cd` at all, when the pack forbids blindly prepending shell
 * text: SSH has no working-directory request, so for an interactive
 * shell there is no protocol mechanism — the only honest options
 * are a visible `cd` or no initial directory. This is the documented
 * exception, and it is narrow on purpose:
 * - It runs exactly once per shell, immediately after the shell
 *   starts and before the session is shown as connected, so it is
 *   the first input the shell sees (terminal input is disabled
 *   until CONNECTED, so user typing cannot interleave with it).
 * - It is visible in the terminal output, like any typed command.
 * - If the directory does not exist, the shell prints its own
 *   error and the session continues — a failed `cd` never kills
 *   a session, and the command after `&&` is never used, so a
 *   failed `cd` cannot silently change where later commands run.
 * - It is NEVER applied to command buttons (see
 *   docs/ENVIRONMENT_AND_PATHS.md): buttons are user-authored
 *   exact text, and prefixing them would break the prompt-08
 *   guarantee that the preview is exactly what is sent.
 * - It is NEVER applied to SFTP (a subsystem, not a shell) or to
 *   the app itself.
 *
 * Rendering per shell dialect: POSIX and PowerShell both accept a
 * single-quoted literal, but with different escapes (security
 * fix 16: the old code used POSIX quoting for PowerShell too,
 * which is wrong for paths containing a quote — PowerShell
 * doubles quotes, POSIX closes/escapes/reopens). cmd.exe does
 * not accept single-quoted literals, so cmd gets `cd /d` with
 * a double-quoted path (double quotes are illegal in Windows
 * names, so validation already excludes them from the path).
 */
object CdCommand {
    fun render(directory: String, shell: RemoteShell): String = when (shell) {
        RemoteShell.POSIX, RemoteShell.POWERSHELL ->
            "cd " + CommandTemplate.shellQuote(directory, shell)
        RemoteShell.CMD -> "cd /d \"$directory\""
    }
}

package com.pishi.termbridge.domain

/**
 * Prompt 08 risky-command heuristic. Pure Kotlin, JVM-testable.
 *
 * This is a CONSERVATIVE heuristic, not a classifier and not a
 * security guarantee. It is designed to over-trigger (a benign
 * command may ask for confirmation) rather than under-trigger, and
 * it can be bypassed by obfuscation (variables, encoded strings,
 * aliases) — the confirmation dialog says so in plain words.
 * Nothing here makes a command safe; it only decides whether an
 * extra confirmation dialog appears before submission.
 *
 * A command is split on shell separators (;, &&, ||, |, newline,
 * and on POSIX also single &, ( ), `$(` and backticks; on cmd.exe
 * also single &) and each segment is checked, so `ls; rm -rf /` is
 * risky even though its first segment is benign. Before matching,
 * a segment is NORMALISED (review #2 F-09): quote characters are
 * removed, group/subshell openers and a leading backslash are
 * dropped, a path prefix on the command word (`/bin/rm`) is
 * stripped, and transparent wrappers (command, nohup, time, xargs,
 * ...) are skipped. This matters because the app itself wraps every
 * parameter value in single quotes, so `rm -r '/etc'` and
 * `chmod -R 777 '/'` must classify like their unquoted forms. Matching is case-insensitive
 * for command names. Rendered text (after variable substitution)
 * is what is classified, never the raw template. Two patterns
 * span a separator (a pipe) and are matched against the WHOLE
 * command instead: splitting on '|' first would make a pattern
 * needing a literal pipe unmatchable (CI run #12: curl|sh was
 * missed, and the fork-bomb pattern had the same defect).
 *
 * Shell-aware (security fix 16, finding H1): the old pattern set
 * was POSIX-only, so Windows-dangerous commands were never
 * flagged on cmd.exe/PowerShell hosts. Patterns are now split
 * into common, POSIX-only, and Windows-only sets, and
 * [classify] applies the sets for the host's shell:
 * POSIX -> common + POSIX; PowerShell -> all three (PowerShell
 * has `rm` as a Remove-Item alias, so POSIX patterns still
 * bite there); cmd.exe -> common + Windows. A pattern that is
 * inert on a shell is not applied there, so a POSIX-only
 * command on cmd.exe is not flagged for no reason — and, more
 * importantly, a Windows-dangerous command on a Windows shell
 * is no longer missed.
 */
data class RiskAssessment(val isRisky: Boolean, val reasons: List<String>)

object RiskyCommand {
    // Each entry: reason label -> regex matched against one segment.
    private val commonPatterns: List<Pair<String, Regex>> = listOf(
        "shutdown, reboot, or poweroff" to Regex(
            """^\s*(shutdown|reboot|poweroff|halt)\b""",
            RegexOption.IGNORE_CASE
        )
    )

    // Patterns spanning a separator: matched against the whole
    // command, never against a segment (see the KDoc above).
    private val wholeCommandPatterns: List<Pair<String, Regex>> = listOf(
        "download piped directly to a shell" to Regex(
            """\b(curl|wget)\b.*\|\s*(sh|bash|zsh)\b""",
            RegexOption.IGNORE_CASE
        ),
        "fork bomb" to Regex(
            """:\(\)\s*\{\s*:\|:\s*&\s*\}\s*;""",
            RegexOption.IGNORE_CASE
        )
    )

    private val posixPatterns: List<Pair<String, Regex>> = listOf(
        "recursive or forced delete" to Regex(
            """^\s*rm\s+.*(-[a-zA-Z]*[rf][a-zA-Z]*)\b""",
            RegexOption.IGNORE_CASE
        ),
        "delete of a system or home path" to Regex(
            """^\s*rm\s+(?:.*\s)?(/|/\*|~|~/\S*|/etc|/usr|/var|/boot|/bin|/sbin|\${'$'}HOME(/\S*)?)\s*$""",
            RegexOption.IGNORE_CASE
        ),
        "shutdown, reboot, or poweroff (systemd)" to Regex(
            """^\s*systemctl\s+(poweroff|reboot)\b""",
            RegexOption.IGNORE_CASE
        ),
        "filesystem creation or disk overwrite" to Regex(
            """^\s*(mkfs(\.\w+)?|dd|shred|wipefs|fdisk|parted)\b""",
            RegexOption.IGNORE_CASE
        ),
        "privilege escalation" to Regex(
            """^\s*(sudo|su|doas|pkexec)\b""",
            RegexOption.IGNORE_CASE
        ),
        "recursive permission or ownership change on a system path" to Regex(
            """^\s*(chmod|chown)\s+.*-R.*\s(?:/|/etc|/usr|/var|/boot|/bin|/sbin)(?=\s|$|/)""",
            RegexOption.IGNORE_CASE
        ),
        "kill all processes or kill process group" to Regex(
            """^\s*(killall|pkill\s+.*-9|kill\s+-9\s+-1)\b""",
            RegexOption.IGNORE_CASE
        ),
        "firewall flush or remote-access service stop" to Regex(
            """(iptables\s+.*(-F|--flush)|systemctl\s+stop\s+(sshd?|ssh|networking|NetworkManager))\b""",
            RegexOption.IGNORE_CASE
        ),
        "write to a device" to Regex(
            """[>|]\s*/dev/(sd|hd|nvme|vd)[a-z0-9]*""",
            RegexOption.IGNORE_CASE
        ),
        "history or shell configuration wipe" to Regex(
            """^\s*>\s*(~/.bash_history|~/.zsh_history|/dev/null)\s*$""",
            RegexOption.IGNORE_CASE
        )
    )

    private val windowsPatterns: List<Pair<String, Regex>> = listOf(
        "forced, recursive, or wildcard delete (Windows)" to Regex(
            """^\s*(del|erase)\b.*(/f|/s|/q|\*)""",
            RegexOption.IGNORE_CASE
        ),
        "recursive directory remove (Windows)" to Regex(
            """^\s*(rd|rmdir)\s+.*(/s|/q)\b""",
            RegexOption.IGNORE_CASE
        ),
        "recursive remove (Windows)" to Regex(
            """\bRemove-Item\b.*-Recurse\b""",
            RegexOption.IGNORE_CASE
        ),
        "format or disk partitioning (Windows)" to Regex(
            """^\s*(format|diskpart)\b""",
            RegexOption.IGNORE_CASE
        ),
        "shutdown or restart (Windows)" to Regex(
            """^\s*(Stop-Computer|Restart-Computer)\b""",
            RegexOption.IGNORE_CASE
        ),
        "registry delete (Windows)" to Regex(
            """^\s*reg\s+delete\b""",
            RegexOption.IGNORE_CASE
        ),
        "shadow copy deletion (Windows)" to Regex(
            """\bvssadmin\s+delete\s+shadows\b""",
            RegexOption.IGNORE_CASE
        ),
        "firewall disable (Windows)" to Regex(
            """\bnetsh\s+advfirewall\s+set\s+.*\bstate\s+off\b""",
            RegexOption.IGNORE_CASE
        ),
        "free-space wipe (Windows)" to Regex(
            """\bcipher\s+/w\b""",
            RegexOption.IGNORE_CASE
        )
    )

    private fun patternsFor(shell: RemoteShell): List<Pair<String, Regex>> =
        commonPatterns + when (shell) {
            RemoteShell.POSIX -> posixPatterns
            RemoteShell.POWERSHELL -> posixPatterns + windowsPatterns
            RemoteShell.CMD -> windowsPatterns
        }

    private val posixSplit = Regex("""&&|\|\||\$\(|[;&|\n()`]""")
    private val cmdSplit = Regex("""&&|\|\||[;&|\n]""")
    private val powershellSplit = Regex("""&&|\|\||[;|\n]""")

    private fun splitSegments(command: String, shell: RemoteShell): List<String> {
        val splitter = when (shell) {
            RemoteShell.POSIX -> posixSplit
            RemoteShell.CMD -> cmdSplit
            // PowerShell: `&` is the call operator and backtick the
            // escape character, so neither is a separator there.
            RemoteShell.POWERSHELL -> powershellSplit
        }
        return command.split(splitter)
    }

    private val quoteChars = Regex("""['"`]""")
    private val leadingNoise = Regex("""^[\s({\\]+""")
    private val pathPrefix = Regex("""^\S*/(?=[A-Za-z])""")
    private val wrapperWords =
        Regex("""^(?:(?:command|builtin|exec|nohup|time|nice|xargs)\s+)+""", RegexOption.IGNORE_CASE)

    private fun normalise(segment: String, shell: RemoteShell): String {
        var text = segment
        // Backtick is PowerShell's escape character, not a quote.
        text = if (shell == RemoteShell.POWERSHELL) text.replace(Regex("""['"]"""), "") else text.replace(quoteChars, "")
        text = text.replace(leadingNoise, "")
        text = text.replace(pathPrefix, "")
        text = text.replace(wrapperWords, "")
        return text
    }

    fun classify(command: String, shell: RemoteShell = RemoteShell.POSIX): RiskAssessment {
        val patterns = patternsFor(shell)
        val reasons = linkedSetOf<String>()
        for ((reason, regex) in wholeCommandPatterns) {
            if (regex.containsMatchIn(command)) reasons += reason
        }
        for (rawSegment in splitSegments(command, shell)) {
            val segment = normalise(rawSegment, shell)
            for ((reason, regex) in patterns) {
                if (regex.containsMatchIn(segment)) reasons += reason
            }
        }
        return RiskAssessment(reasons.isNotEmpty(), reasons.toList())
    }

    fun classifySteps(steps: List<String>, shell: RemoteShell = RemoteShell.POSIX): RiskAssessment {
        val reasons = linkedSetOf<String>()
        for (step in steps) {
            reasons += classify(step, shell).reasons
        }
        return RiskAssessment(reasons.isNotEmpty(), reasons.toList())
    }
}

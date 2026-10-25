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
 * A command is split on shell separators (;, &&, ||, |, newline)
 * and each segment is checked, so `ls; rm -rf /` is risky even
 * though its first segment is benign. Matching is case-insensitive
 * for command names. Rendered text (after variable substitution)
 * is what is classified, never the raw template.
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
        ),
        "download piped directly to a shell" to Regex(
            """\b(curl|wget)\b.*\|\s*(sh|bash|zsh)\b""",
            RegexOption.IGNORE_CASE
        )
    )

    private val posixPatterns: List<Pair<String, Regex>> = listOf(
        "recursive or forced delete" to Regex(
            """^\s*rm\s+.*(-[a-zA-Z]*[rf][a-zA-Z]*)\b""",
            RegexOption.IGNORE_CASE
        ),
        "delete of a system or home path" to Regex(
            """^\s*rm\s+.*\s(/|/\*|~|~/\S*|/etc|/usr|/var|/boot|/bin|/sbin|\${'$'}HOME(/\S*)?)\s*$""",
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
            """^\s*(chmod|chown)\s+.*-R.*\s(/|/etc|/usr|/var|/boot|/bin|/sbin)\b""",
            RegexOption.IGNORE_CASE
        ),
        "kill all processes or kill process group" to Regex(
            """^\s*(killall|pkill\s+.*-9|kill\s+-9\s+-1)\b""",
            RegexOption.IGNORE_CASE
        ),
        "fork bomb" to Regex(
            """:\(\)\s*\{\s*:\|:\s*&\s*\}\s*;""",
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

    private fun splitSegments(command: String): List<String> =
        command.split(';', '\n').flatMap { it.split("&&", "||", "|") }

    fun classify(command: String, shell: RemoteShell = RemoteShell.POSIX): RiskAssessment {
        val patterns = patternsFor(shell)
        val reasons = linkedSetOf<String>()
        for (segment in splitSegments(command)) {
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

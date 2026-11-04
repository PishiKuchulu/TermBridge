package com.pishi.termbridge.domain

/**
 * Item F-02: the shell-export fallback for environment
 * variables the server refused through the SSH env channel.
 *
 * Background: `Session.setEnvVar` only works when the server's
 * sshd is configured with a matching `AcceptEnv` — a
 * server-admin setting the user often cannot change (the
 * friend's report behind F-02: "some things needlessly need
 * root"). The fallback types one visible export line per
 * refused variable into the fresh shell instead. It is OFF by
 * default, per host, and the session layer applies it only
 * when the host's shell is user-confirmed — see
 * HostEnvironmentSettings.envExportFallback.
 *
 * Safety shape, deliberately the same as the initial `cd`
 * (see CdCommand):
 * - The lines are VISIBLE: they are the shell's first input,
 *   they stay on screen and in the shell's history. A value
 *   the user would not want visible must not use this path —
 *   the Settings hint says exactly that.
 * - Values are quoted with the shell's own rules
 *   ([CommandTemplate.shellQuote]) and any value containing a
 *   control character is REFUSED (null), never typed: a
 *   newline in a value would otherwise smuggle extra commands
 *   into the shell. (Stored values are only NUL-checked at
 *   save time, so this render-time gate is the real one.)
 * - cmd.exe additionally refuses values with `%` or `"` —
 *   cmd expands `%VAR%` even inside quotes (review #2 F-22).
 * - Names must match the same rule the save path enforces.
 * - Nothing here logs or returns a value: the plan carries
 *   names plus the rendered lines (which the caller sends and
 *   then drops), and skipped names are reported by name only.
 */
object ExportCommand {
    private val NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    /** One export line for [name]/[value] in [shell]'s dialect,
     *  or null when the pair cannot be typed safely. */
    fun render(name: String, value: String, shell: RemoteShell): String? {
        if (!NAME_REGEX.matches(name)) return null
        if (ControlChars.containsControl(value)) return null
        return when (shell) {
            RemoteShell.POSIX ->
                "export $name=" + CommandTemplate.shellQuote(value, shell)
            RemoteShell.POWERSHELL ->
                "\$env:$name = " + CommandTemplate.shellQuote(value, shell)
            RemoteShell.CMD ->
                if ('%' in value || '"' in value) {
                    null
                } else {
                    "set \"$name=$value\""
                }
        }
    }

    /** What the fallback will type, and what it will not. */
    data class FallbackPlan(
        /** (name, line) pairs, in the refused order. */
        val lines: List<Pair<String, String>>,
        /** Refused names with no safe line (no value available,
         *  or the pair failed [render]). Names only. */
        val skippedNames: List<String>
    )

    /**
     * Plan the fallback for the variables the server refused.
     * [values] holds the decrypted values by name; a refused
     * name without a value (undecryptable) is skipped, as is
     * any pair [render] refuses.
     */
    fun planFallback(
        values: Map<String, String>,
        rejectedNames: List<String>,
        shell: RemoteShell
    ): FallbackPlan {
        val lines = mutableListOf<Pair<String, String>>()
        val skipped = mutableListOf<String>()
        for (name in rejectedNames) {
            val line = values[name]?.let { render(name, it, shell) }
            if (line != null) {
                lines += name to line
            } else {
                skipped += name
            }
        }
        return FallbackPlan(lines = lines, skippedNames = skipped)
    }
}

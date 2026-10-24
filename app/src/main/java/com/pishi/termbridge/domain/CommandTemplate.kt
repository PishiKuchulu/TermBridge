package com.pishi.termbridge.domain

/**
 * Prompt 08 template handling. Pure Kotlin, JVM-testable.
 *
 * Syntax: placeholders are {{name}} where name matches
 * [A-Za-z_][A-Za-z0-9_]*. Any other {{...}} is rejected as a
 * malformed placeholder rather than silently passed through.
 *
 * Shell context (documented, not assumed): the rendered text is
 * submitted to the connected host's interactive shell, and
 * quoting is PER SHELL (security fix 16, finding H1 — the old
 * text claimed a POSIX shell unconditionally, but prompt 09 lets
 * a host declare cmd.exe or PowerShell, where POSIX quoting is
 * void or wrong):
 * - POSIX: values are single-quote escaped (a ' becomes '\'').
 * - PowerShell: values are single-quote escaped by doubling
 *   (a ' becomes ''), which is PowerShell's single-quoted
 *   literal rule — no interpolation, no escape processing.
 * - cmd.exe: there is NO safe general quoting (double quotes do
 *   not stop %VAR% expansion, and a value containing a quote
 *   breaks out), so a button WITH parameters cannot be prepared
 *   for a cmd.exe host at all — prepare() throws, the parameter
 *   dialog shows the reason, and the action stays disabled. A
 *   button WITHOUT parameters needs no quoting (rendered text
 *   is exactly the author text) and still prepares on cmd.exe.
 * Templates must NOT add their own quotes around a placeholder —
 * doing so would double-quote the already-quoted value. The
 * preview shown to the user is exactly the text that will be
 * inserted or submitted, so quoting is visible, not hidden.
 *
 * A value is never interpolated raw into shell syntax: on POSIX
 * and PowerShell, even a value containing spaces, semicolons,
 * $(), or backticks becomes one single-quoted shell word.
 */
object CommandTemplate {
    private val placeholderRegex = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]*)\\}\\}")
    private val malformedRegex = Regex("\\{\\{[^}]*\\}\\}")
    private val variableNameRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun isValidVariableName(name: String): Boolean = variableNameRegex.matches(name)

    /** Placeholder names in order of first appearance. */
    fun extractPlaceholders(template: String): List<String> {
        val seen = linkedSetOf<String>()
        placeholderRegex.findAll(template).forEach { seen += it.groupValues[1] }
        return seen.toList()
    }

    /** True when the template contains a {{...}} that is not a valid placeholder. */
    fun hasMalformedPlaceholder(template: String): Boolean {
        val malformed = malformedRegex.findAll(template).map { it.value }.toSet()
        val valid = placeholderRegex.findAll(template).map { it.value }.toSet()
        return (malformed - valid).isNotEmpty()
    }

    /**
     * Prompt 18 (audit-17 R1): warn when a parameterised button
     * is prepared for a host whose shell was never confirmed.
     * Parameter-free buttons never quote anything, so they never
     * warn; a confirmed shell never warns, whatever it is (cmd.exe
     * is already gated by shellQuote). This is a pure predicate
     * so the rule is unit-testable without a dialog.
     */
    fun warnUnconfirmedShell(hasParameters: Boolean, shellConfirmed: Boolean): Boolean =
        hasParameters && !shellConfirmed

    /** Single-quote escape for a POSIX shell word. */
    fun shellQuote(value: String): String = shellQuote(value, RemoteShell.POSIX)

    /**
     * Shell-aware quoting. POSIX and PowerShell both use
     * single-quoted literals, but with different escapes (POSIX:
     * '\'' closes, escapes, reopens; PowerShell: '' doubling).
     * cmd.exe has no safe quoting for arbitrary values, so it
     * throws — callers surface the message and disable the
     * action rather than sending POSIX-quoted text to cmd.exe.
     * A template with no placeholders never reaches this
     * function, so parameter-free buttons are unaffected.
     */
    fun shellQuote(value: String, shell: RemoteShell): String = when (shell) {
        RemoteShell.POSIX -> "'" + value.replace("'", "'\\''") + "'"
        RemoteShell.POWERSHELL -> "'" + value.replace("'", "''") + "'"
        RemoteShell.CMD -> throw IllegalArgumentException(
            "Command buttons with parameters are not supported on " +
                "cmd.exe hosts: values cannot be quoted safely for " +
                "cmd.exe. Use a POSIX or PowerShell shell for this " +
                "host, or remove the parameters from the button."
        )
    }

    /**
     * Render a template with the given values. Throws
     * IllegalArgumentException when a placeholder has no value,
     * or when the shell cannot quote a parameter value (cmd.exe).
     * Callers resolve defaults/required checks before calling this
     * (see CommandValidator.resolveValues).
     */
    fun render(
        template: String,
        values: Map<String, String>,
        shell: RemoteShell = RemoteShell.POSIX
    ): String {
        if (hasMalformedPlaceholder(template)) {
            throw IllegalArgumentException("Template contains a malformed placeholder.")
        }
        val missing = extractPlaceholders(template).filter { it !in values }
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("Missing values for: ${missing.joinToString(", ")}")
        }
        return placeholderRegex.replace(template) { match ->
            shellQuote(values.getValue(match.groupValues[1]), shell)
        }
    }

    /**
     * Resolve effective values for a button: user-supplied values
     * win, then defaults. Throws IllegalArgumentException listing
     * required variables that still have no value.
     */
    fun resolveValues(
        button: CommandButton,
        supplied: Map<String, String>
    ): Map<String, String> {
        val resolved = mutableMapOf<String, String>()
        val missingRequired = mutableListOf<String>()
        for (variable in button.variables) {
            val suppliedValue = supplied[variable.name]
            when {
                suppliedValue != null -> resolved[variable.name] = suppliedValue
                variable.defaultValue.isNotEmpty() -> resolved[variable.name] = variable.defaultValue
                variable.required -> missingRequired += variable.name
                else -> resolved[variable.name] = ""
            }
        }
        // Values supplied for undeclared variables are ignored, not
        // interpolated — only declared variables reach the shell.
        if (missingRequired.isNotEmpty()) {
            throw IllegalArgumentException(
                "Required values missing: ${missingRequired.joinToString(", ")}"
            )
        }
        return resolved
    }

    /**
     * Prepare a button for action without executing anything.
     * [shell] is the connected host's shell: quoting and risky
     * classification are both shell-aware (security fix 16, H1).
     * Defaults to POSIX so existing callers and tests that do not
     * know a host's shell keep the old behaviour; the session
     * dialog always passes the connected host's shell.
     */
    fun prepare(
        button: CommandButton,
        supplied: Map<String, String>,
        shell: RemoteShell = RemoteShell.POSIX
    ): PreparedCommand {
        val values = resolveValues(button, supplied)
        return when (button.mode) {
            CommandMode.INSERT_ONLY, CommandMode.EXECUTE_ONE -> {
                val rendered = render(button.template, values, shell)
                val risk = RiskyCommand.classify(rendered, shell)
                PreparedCommand(
                    button = button,
                    rendered = rendered,
                    isRisky = risk.isRisky,
                    riskReasons = risk.reasons
                )
            }
            CommandMode.SEQUENCE -> {
                val renderedSteps = button.steps.map { render(it, values, shell) }
                val risk = RiskyCommand.classifySteps(renderedSteps, shell)
                PreparedCommand(
                    button = button,
                    renderedSteps = renderedSteps,
                    isRisky = risk.isRisky,
                    riskReasons = risk.reasons
                )
            }
        }
    }
}

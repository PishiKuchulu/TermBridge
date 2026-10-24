package com.pishi.termbridge.domain

/**
 * Prompt 08: custom command buttons, per host.
 *
 * The three modes are STRICTLY separate and are never conflated:
 * - INSERT_ONLY: rendered text is placed into the terminal input
 *   and is never submitted. The user must press Enter themselves.
 * - EXECUTE_ONE: one rendered command is submitted to the active
 *   shell after an explicit user action (and a confirmation dialog
 *   when the command is classified risky).
 * - SEQUENCE: rendered steps are submitted in order. See
 *   CommandRunner for the exact stop/cancel semantics — an
 *   interactive PTY shell does not report per-step exit status to
 *   the app, so a sequence does NOT auto-stop on first error;
 *   the user stops it with Stop, and remaining steps are never sent.
 *
 * No button, selection, import, or screen opening ever auto-runs.
 * Import/export is NOT part of this phase (prompt 11 owns it).
 */
enum class CommandMode {
    INSERT_ONLY,
    EXECUTE_ONE,
    SEQUENCE
}

/** One named variable a template may reference as {{name}}. */
data class CommandVariable(
    val name: String,
    val defaultValue: String = "",
    val required: Boolean = false
)

/**
 * A command button without any secret value. Templates must never
 * contain credentials — CommandValidator blocks obvious secret
 * patterns on save (heuristic, not perfect, see SecretScanner).
 *
 * For INSERT_ONLY and EXECUTE_ONE, [template] is the command.
 * For SEQUENCE, [template] is unused (empty) and [steps] holds the
 * ordered commands, one per entry.
 */
data class CommandButton(
    val id: Long = 0L,
    val hostId: Long,
    val label: String,
    val category: String = "",
    val sortOrder: Int = 0,
    val mode: CommandMode = CommandMode.INSERT_ONLY,
    val template: String = "",
    val steps: List<String> = emptyList(),
    val variables: List<CommandVariable> = emptyList()
)

/**
 * A button fully prepared for action: all placeholders resolved,
 * risk assessed. The UI shows this preview before any submission,
 * and for risky commands shows a second confirmation dialog.
 * A PreparedCommand is never executed implicitly — the ViewModel
 * only acts on it after an explicit confirm tap.
 */
data class PreparedCommand(
    val button: CommandButton,
    /** Rendered text for INSERT_ONLY / EXECUTE_ONE. */
    val rendered: String = "",
    /** Rendered steps for SEQUENCE, in order. */
    val renderedSteps: List<String> = emptyList(),
    val isRisky: Boolean = false,
    /** Human-readable reason labels (never the command text). */
    val riskReasons: List<String> = emptyList()
) {
    /** Steps a sequence would submit, or the single command. */
    val textsToSubmit: List<String>
        get() = if (button.mode == CommandMode.SEQUENCE) renderedSteps else listOf(rendered)
}

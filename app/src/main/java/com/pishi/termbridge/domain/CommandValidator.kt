package com.pishi.termbridge.domain

/**
 * Prompt 08 button validation. Pure Kotlin, JVM-testable.
 * Enforced in the repository (so no UI can bypass it) and mirrored
 * in the management ViewModel for per-field errors.
 */
object CommandValidator {
    fun validate(button: CommandButton): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (button.label.isBlank()) {
            issues += ValidationIssue("label", "Label must not be blank.")
        }
        if (button.label.length > 80) {
            issues += ValidationIssue("label", "Label must be at most 80 characters.")
        }
        when (button.mode) {
            CommandMode.INSERT_ONLY, CommandMode.EXECUTE_ONE -> {
                if (button.template.isBlank()) {
                    issues += ValidationIssue("template", "Command must not be blank.")
                }
                if (button.steps.isNotEmpty()) {
                    issues += ValidationIssue(
                        "template",
                        "A single command button must not carry sequence steps."
                    )
                }
            }
            CommandMode.SEQUENCE -> {
                if (button.steps.isEmpty() || button.steps.all { it.isBlank() }) {
                    issues += ValidationIssue("steps", "A sequence needs at least one step.")
                }
                if (button.template.isNotBlank()) {
                    issues += ValidationIssue(
                        "template",
                        "A sequence button must not carry a single command."
                    )
                }
            }
        }
        val templates = when (button.mode) {
            CommandMode.SEQUENCE -> button.steps
            else -> listOf(button.template)
        }
        for (template in templates) {
            if (CommandTemplate.hasMalformedPlaceholder(template)) {
                issues += ValidationIssue(
                    "template",
                    "A placeholder is malformed. Use {{name}} with letters, digits, or underscore."
                )
            }
        }
        for (template in templates) {
            if (ControlChars.containsControlExceptNewline(template)) {
                issues += ValidationIssue(
                    "template",
                    "A command must not contain control characters (other than line breaks)."
                )
            }
        }
        for (variable in button.variables) {
            if (ControlChars.containsControl(variable.defaultValue)) {
                issues += ValidationIssue(
                    "variables",
                    "A default value must not contain control characters."
                )
            }
        }
        val names = button.variables.map { it.name }
        if (names.size != names.toSet().size) {
            issues += ValidationIssue("variables", "Variable names must be unique.")
        }
        for (variable in button.variables) {
            if (!CommandTemplate.isValidVariableName(variable.name)) {
                issues += ValidationIssue(
                    "variables",
                    "Invalid variable name: ${variable.name}."
                )
            }
        }
        val used = templates.flatMap { CommandTemplate.extractPlaceholders(it) }.toSet()
        val declared = names.toSet()
        val undeclared = used - declared
        if (undeclared.isNotEmpty()) {
            issues += ValidationIssue(
                "variables",
                "Placeholders used but not defined: ${undeclared.joinToString(", ")}."
            )
        }
        val secrets = SecretScanner.scanButton(button)
        if (secrets.isNotEmpty()) {
            issues += ValidationIssue(
                "template",
                "Refused: this button looks like it contains a secret " +
                    "(${secrets.joinToString(", ")}). Snippets must not store credentials."
            )
        }
        return issues
    }
}

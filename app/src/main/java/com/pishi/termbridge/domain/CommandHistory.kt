package com.pishi.termbridge.domain

/** One remembered command (item T-11). On-device only. */
data class HistoryEntry(
    val text: String,
    val uses: Int,
    val lastUsedMs: Long
)

/**
 * Item T-11: the gate every command must pass before it is
 * remembered for autocomplete. A line is admitted only when
 * it looks like an ordinary shell command: reasonable
 * length, no control characters, and NOTHING the
 * SecretScanner flags (assignments of secrets, key material,
 * tokens, and its other patterns). The user's own typing and
 * imported shell history both pass through here — a line
 * that fails is dropped silently, never stored, never shown.
 */
object CommandAdmission {
    const val MIN_LENGTH = 2
    const val MAX_LENGTH = 500

    /** The admitted (trimmed) line, or null to drop it. */
    fun admit(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.length < MIN_LENGTH || trimmed.length > MAX_LENGTH) return null
        if (ControlChars.containsControl(trimmed)) return null
        if (SecretScanner.scan(trimmed).isNotEmpty()) return null
        return trimmed
    }
}

/**
 * Item T-11: prefix suggestions over the remembered
 * commands. Whole-line matching (the mobile-terminal form of
 * history search): the current line must be a strict prefix
 * of a remembered command. Ranked by use count, then by
 * recency.
 */
object Autocomplete {
    const val MAX_SUGGESTIONS = 6
    const val MIN_PREFIX = 2

    fun suggestions(
        currentLine: String,
        entries: List<HistoryEntry>,
        max: Int = MAX_SUGGESTIONS
    ): List<String> {
        if (currentLine.length < MIN_PREFIX) return emptyList()
        return entries.asSequence()
            .filter { it.text.length > currentLine.length && it.text.startsWith(currentLine) }
            .sortedWith(
                compareByDescending<HistoryEntry> { it.uses }
                    .thenByDescending { it.lastUsedMs }
            )
            .map { it.text }
            .distinct()
            .take(max)
            .toList()
    }
}

/**
 * Item T-11: models the line the user is composing in the
 * terminal input field, so autocomplete knows the prefix.
 * It tracks ONLY what passes through the app's own input
 * paths; anything that makes the shell-side line unknowable
 * (history recall, completion, control chords) is a [reset]
 * — no suggestions beats wrong suggestions.
 */
class InputLineTracker {
    private val builder = StringBuilder()

    val currentLine: String
        get() = builder.toString()

    fun type(text: String) {
        for (c in text) {
            if (!ControlChars.isControl(c)) builder.append(c)
        }
    }

    fun backspace() {
        if (builder.isNotEmpty()) builder.deleteCharAt(builder.length - 1)
    }

    /** Returns the composed line and clears the tracker. */
    fun submit(): String {
        val line = builder.toString()
        builder.setLength(0)
        return line
    }

    fun reset() {
        builder.setLength(0)
    }
}

/**
 * Item T-11: parses a shell history file dump into admitted
 * commands. Understands plain bash lines, zsh extended
 * history (`: <epoch>:<elapsed>;<cmd>`) and fish history
 * (`- cmd: <cmd>` records). Everything passes
 * [CommandAdmission]; duplicates collapse to their LAST
 * occurrence (the most recent position wins).
 */
object ShellHistoryImport {
    const val MAX_LINES = 2000

    private val zshExtended = Regex("^: \\d+:\\d+;(.*)$")

    fun parse(raw: String): List<String> {
        val ordered = LinkedHashMap<String, Unit>()
        for (line in raw.lineSequence().take(MAX_LINES * 2)) {
            // Indented lines are fish metadata ("  when: ...")
            // or continuations, never commands of their own.
            if (line.firstOrNull() == ' ' || line.firstOrNull() == '\t') continue
            val candidate = when {
                line.startsWith("- cmd: ") -> line.removePrefix("- cmd: ")
                else -> zshExtended.matchEntire(line)?.groupValues?.get(1) ?: line
            }
            val admitted = CommandAdmission.admit(candidate) ?: continue
            ordered.remove(admitted)
            ordered[admitted] = Unit
            if (ordered.size > MAX_LINES) {
                ordered.remove(ordered.keys.first())
            }
        }
        return ordered.keys.toList()
    }
}

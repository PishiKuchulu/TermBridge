package com.pishi.termbridge.domain

/**
 * Control-character policy for every string that is TYPED into a
 * remote terminal (review #2 F-03).
 *
 * Quoting protects against shell SYNTAX injection only. The pty line
 * discipline and readline interpret raw control bytes before the
 * shell parser ever sees them: ^C discards the line (so the text
 * after it runs as a new command), ^A / ^E / ^U / ESC sequences move
 * or edit the line buffer, ^D ends input, TAB triggers completion.
 * Anything that reaches `sendText` therefore must not contain them.
 */
object ControlChars {
    /** C0 controls, DEL, and C1 controls. */
    fun isControl(c: Char): Boolean = c.code < 0x20 || c.code == 0x7F || c.code in 0x80..0x9F

    fun containsControl(text: String): Boolean = text.any { isControl(it) }

    /** Like [containsControl] but tolerates line feeds (multi-line
     *  command templates are an authored feature). */
    fun containsControlExceptNewline(text: String): Boolean =
        text.any { it != '\n' && isControl(it) }

    /**
     * Human-visible rendering for previews and confirmation dialogs:
     * every control character except a line feed becomes `<0xNN>` so
     * the user can SEE what would be typed (review #2 F-03, F-24).
     */
    fun visualize(text: String): String {
        if (text.none { it != '\n' && isControl(it) }) return text
        val sb = StringBuilder(text.length + 8)
        for (c in text) {
            if (c != '\n' && isControl(c)) {
                sb.append("<0x").append(String.format("%02X", c.code)).append('>')
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }
}

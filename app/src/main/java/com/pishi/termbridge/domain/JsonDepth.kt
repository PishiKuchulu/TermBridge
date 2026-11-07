package com.pishi.termbridge.domain

/**
 * Structural depth of JSON text (audit #7 AUD-001): the deepest
 * nesting of objects/arrays, ignoring brackets inside string
 * literals. Used as a pre-parse guard wherever text from
 * outside the app is handed to the recursive-descent JSON
 * parser, whose failure mode for hostile nesting is a
 * StackOverflowError — an Error, not an Exception, so it
 * escapes ordinary catch blocks and crashes the process.
 * Pure and JVM-testable.
 */
object JsonDepth {

    /** Deepest bracket nesting in [text]; 0 for text without
     *  any object/array. Unbalanced closers are ignored (the
     *  parser will reject the text on its own terms). */
    fun maxDepth(text: String): Int = scan(text, Int.MAX_VALUE)

    /** True when [text] nests deeper than [max]. Exits the scan
     *  early once the cap is passed, so a hostile megabyte of
     *  brackets costs only a few hundred iterations. */
    fun exceeds(text: String, max: Int): Boolean = scan(text, max) > max

    private fun scan(text: String, limit: Int): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > deepest) {
                        deepest = depth
                        if (deepest > limit) return deepest
                    }
                }
                '}', ']' -> if (depth > 0) depth--
            }
        }
        return deepest
    }
}

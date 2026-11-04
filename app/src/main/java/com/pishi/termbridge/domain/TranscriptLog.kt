package com.pishi.termbridge.domain

/**
 * Item T-03: pure planning for the per-host session
 * transcript — a local-only, opt-in record of what the
 * terminal showed. This object owns the size policy so the
 * file layer stays dumb:
 *
 * - The live log is capped at [MAX_BYTES].
 * - When an append would pass the cap, the file layer
 *   ROTATES once (live → `.1`, one generation kept) and the
 *   append lands in a fresh file.
 * - A single chunk larger than the cap keeps only its
 *   [tail] — the newest content wins.
 *
 * Every line passes SecretRedactor.scrubFreeText BEFORE it
 * reaches this layer (the writer's job); this layer never
 * sees an unscrubbed line by contract.
 */
object TranscriptLog {
    const val MAX_BYTES: Long = 256L * 1024L

    enum class Plan { APPEND, ROTATE_THEN_APPEND }

    /** What to do with an incoming chunk of [incomingBytes]. */
    fun plan(existingBytes: Long, incomingBytes: Int, maxBytes: Long = MAX_BYTES): Plan {
        if (incomingBytes <= 0) return Plan.APPEND
        return if (existingBytes + incomingBytes <= maxBytes) {
            Plan.APPEND
        } else {
            Plan.ROTATE_THEN_APPEND
        }
    }

    /** The last [maxBytes] of [text] (UTF-8 safe enough for a
     *  log: cuts on a byte budget, then drops a partial first
     *  line so the file never starts mid-line). */
    fun tail(text: String, maxBytes: Long = MAX_BYTES): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text
        val kept = String(
            bytes.copyOfRange(bytes.size - maxBytes.toInt(), bytes.size),
            Charsets.UTF_8
        )
        val firstBreak = kept.indexOf('\n')
        return if (firstBreak >= 0) kept.substring(firstBreak + 1) else kept
    }
}

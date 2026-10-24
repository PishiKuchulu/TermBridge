package com.pishi.termbridge.domain

/**
 * Windows-style absolute path handling, pure Kotlin, JVM-tested.
 * Lexical only — it never touches a filesystem or a server, so
 * symlink/alias resolution on the remote machine may still differ
 * from this canonical form (documented, not claimed otherwise).
 *
 * Accepted forms:
 * - Drive: `C:\Users\name`, `C:/Users/name` (slash normalised).
 * - UNC: `\\server\share\dir` (server + share are the root).
 * Rules: no NUL, no `" < > | ? *` (illegal in Windows names),
 * no control characters; "." segments dropped; ".." pops the
 * previous segment, clamped at the root (drive root or share
 * root). A relative path (no drive, no UNC) is rejected.
 * Result uses backslashes, no trailing separator except the root.
 */
object WindowsPath {
    private val driveRegex = Regex("^([A-Za-z]):[/\\\\](.*)$")
    private val uncRegex = Regex("^\\\\\\\\([^\\\\]+)\\\\([^\\\\]+)(?:\\\\(.*))?$")
    private val illegal = setOf('"', '<', '>', '|', '?', '*')

    fun normalize(path: String): String? {
        if (path.isEmpty() || '\u0000' in path) return null
        if (path.any { it in illegal || it.code < 0x20 }) return null
        val driveMatch = driveRegex.matchEntire(path)
        if (driveMatch != null) {
            val root = driveMatch.groupValues[1].uppercase() + ":\\"
            val segments = resolveSegments(driveMatch.groupValues[2]) ?: return null
            return if (segments.isEmpty()) root else root + segments.joinToString("\\")
        }
        val uncMatch = uncRegex.matchEntire(path)
        if (uncMatch != null) {
            val root = "\\\\" + uncMatch.groupValues[1] + "\\" + uncMatch.groupValues[2]
            val segments = resolveSegments(uncMatch.groupValues[3]) ?: return null
            return if (segments.isEmpty()) root else root + "\\" + segments.joinToString("\\")
        }
        return null
    }

    private fun resolveSegments(rest: String): List<String>? {
        val segments = mutableListOf<String>()
        for (part in rest.split('/', '\\')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments.add(part)
            }
        }
        return segments
    }
}

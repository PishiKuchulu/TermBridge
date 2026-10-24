package com.pishi.termbridge.transport

/**
 * Remote-path safety for SFTP (prompt 07). All remote paths used
 * by the file manager are absolute and normalized through here.
 * Rules:
 * - A path must be absolute (start with "/") and contain no NUL.
 * - "." segments are dropped, ".." segments pop the previous
 *   segment, clamped at root — navigation can never escape "/".
 * - A single entry name (for create/rename) must not contain "/",
 *   must not be "." or "..", and must not be blank. This blocks
 *   traversal via a crafted name, which is the realistic attack
 *   surface (full paths are built by the app, not typed by the
 *   user, except for names in dialogs).
 * Pure Kotlin, fully unit-tested on the JVM.
 */
object RemotePath {
    fun normalize(absolutePath: String): String? {
        if (!absolutePath.startsWith("/") || '\u0000' in absolutePath) return null
        val segments = mutableListOf<String>()
        for (part in absolutePath.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments.add(part)
            }
        }
        return "/" + segments.joinToString("/")
    }

    fun parent(absolutePath: String): String {
        val normalized = normalize(absolutePath) ?: return "/"
        if (normalized == "/") return "/"
        val index = normalized.lastIndexOf('/')
        return if (index <= 0) "/" else normalized.substring(0, index)
    }

    fun join(parentPath: String, name: String): String? {
        if (!isValidName(name)) return null
        val parent = normalize(parentPath) ?: return null
        return if (parent == "/") "/$name" else "$parent/$name"
    }

    fun isValidName(name: String): Boolean {
        return name.isNotBlank() &&
            '/' !in name &&
            '\u0000' !in name &&
            name != "." &&
            name != ".."
    }

    fun displayName(path: String): String {
        val normalized = normalize(path) ?: return path
        return if (normalized == "/") "/" else normalized.substringAfterLast('/')
    }
}

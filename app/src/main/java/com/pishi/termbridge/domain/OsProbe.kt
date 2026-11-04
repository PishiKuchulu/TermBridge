package com.pishi.termbridge.domain

/** A detected remote operating system (item T-16). */
data class DetectedOs(
    /** Display name, e.g. "Ubuntu 24.04.1 LTS". */
    val name: String,
    /** Kernel / build detail, e.g. "Linux 6.8.0-31-generic x86_64". */
    val detail: String = ""
) {
    val display: String
        get() = if (detail.isBlank()) name else "$name ($detail)"
}

/**
 * Item T-16: remote OS detection over the exec channel —
 * the probe commands run on a separate channel and are
 * NEVER typed into the interactive shell. Parsing is pure:
 *
 * - POSIX: `uname -srm` plus `/etc/os-release`; the display
 *   name prefers PRETTY_NAME, then NAME + VERSION_ID, then
 *   the uname system itself (BSD/macOS land here).
 * - Windows: when the POSIX probe produced nothing usable,
 *   the caller runs the fallback and [parseWindows] reads
 *   the `ver` output line.
 */
object OsProbe {
    const val POSIX_COMMAND =
        "uname -srm 2>/dev/null; echo ---TB---; " +
            "cat /etc/os-release 2>/dev/null | head -n 30"

    const val WINDOWS_COMMAND = "ver"

    private val windowsVersion = Regex("""Microsoft Windows \[Version ([^\]]+)]""")

    fun parsePosix(output: String): DetectedOs? {
        val parts = output.split("---TB---")
        val uname = parts.getOrNull(0)?.trim()?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val releaseBlock = parts.getOrNull(1).orEmpty()
        val fields = mutableMapOf<String, String>()
        for (line in releaseBlock.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
                value = value.substring(1, value.length - 1)
            }
            fields[key] = value
        }
        val pretty = fields["PRETTY_NAME"]?.takeIf { it.isNotBlank() }
        val nameVersion = listOfNotNull(
            fields["NAME"]?.takeIf { it.isNotBlank() },
            fields["VERSION_ID"]?.takeIf { it.isNotBlank() }
        ).joinToString(" ")
        val unameParts = uname.split(' ').filter { it.isNotBlank() }
        val name = pretty ?: nameVersion.takeIf { it.isNotBlank() }
            ?: unameParts.firstOrNull()
            ?: return null
        // With a distro name, the full uname line is the kernel
        // detail; without one, the name IS the uname system and
        // the detail is the rest of that line (kernel + arch).
        val detail = if (pretty != null || nameVersion.isNotBlank()) {
            uname
        } else {
            unameParts.drop(1).joinToString(" ")
        }
        return DetectedOs(name = name, detail = detail)
    }

    fun parseWindows(output: String): DetectedOs? {
        val match = windowsVersion.find(output) ?: return null
        return DetectedOs(
            name = "Microsoft Windows",
            detail = "version ${match.groupValues[1]}"
        )
    }
}

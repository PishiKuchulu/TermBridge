package com.pishi.termbridge.domain

/**
 * Audit #3 (A-20): coarse OS family, used only to compare a
 * probed remote OS against the shell the user DECLARED for the
 * host. The declared shell drives command-button quoting
 * (security fix 16, H1), so a declaration from the wrong family
 * silently voids the quoting guarantee. This comparison can
 * only warn — it never changes the shell and never blocks.
 */
enum class OsFamily {
    POSIX,
    WINDOWS,
    UNKNOWN
}

/**
 * Pure family comparison between an [OsProbe] result and a
 * declared [RemoteShell].
 *
 * Family of a probe result: [OsProbe.parseWindows] is the only
 * parser that produces a Windows detection, and it names it
 * exactly "Microsoft Windows"; every other detection comes from
 * the POSIX parser (Linux, the BSDs, macOS — all POSIX shells).
 *
 * Family of a declared shell: POSIX is the POSIX family;
 * POWERSHELL and CMD are the Windows shells in this app's model
 * (see CommandTemplate's quoting rules), so both map to WINDOWS.
 */
object ShellMismatch {

    fun familyOf(detected: DetectedOs): OsFamily =
        if (detected.name.startsWith("Microsoft Windows")) {
            OsFamily.WINDOWS
        } else {
            OsFamily.POSIX
        }

    fun familyOf(shell: RemoteShell): OsFamily = when (shell) {
        RemoteShell.POSIX -> OsFamily.POSIX
        RemoteShell.POWERSHELL, RemoteShell.CMD -> OsFamily.WINDOWS
    }

    /**
     * True only when BOTH sides carry a clear family signal and
     * they contradict. A failed probe (null) never warns: no
     * signal is not a mismatch.
     */
    fun isMismatch(detected: DetectedOs?, declaredShell: RemoteShell): Boolean {
        if (detected == null) return false
        val probed = familyOf(detected)
        if (probed == OsFamily.UNKNOWN) return false
        return probed != familyOf(declaredShell)
    }
}

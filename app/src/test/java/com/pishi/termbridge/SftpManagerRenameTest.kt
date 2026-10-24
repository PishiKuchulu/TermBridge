package com.pishi.termbridge

import com.pishi.termbridge.transport.SftpManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Security fix 16 (L3): SftpManager.rename used to normalise
 * only the NEW path and pass oldPath raw to sshj, while
 * RemotePath.parent() falls back to "/" on an invalid path —
 * so a malformed oldPath renamed from a raw path to
 * "/<newName>". renamePaths is pure (companion), so it is
 * JVM-testable without an SFTP peer. NOT RUN (sandbox
 * Gradle limits); the live rename against a server is a
 * fixture item, not claimed here.
 */
class SftpManagerRenameTest {
    @Test
    fun normalPathsResolveToNormalisedPair() {
        assertEquals(
            "/srv/app/old.txt" to "/srv/app/new.txt",
            SftpManager.renamePaths("/srv/app/old.txt", "new.txt")
        )
    }

    @Test
    fun traversalInOldPathIsClampedBeforeRename() {
        // /srv/app/../secret.txt normalises to /srv/secret.txt
        // (clamped at root semantics), never passed raw.
        assertEquals(
            "/srv/secret.txt" to "/srv/renamed.txt",
            SftpManager.renamePaths("/srv/app/../secret.txt", "renamed.txt")
        )
    }

    @Test
    fun invalidOldPathFailsInsteadOfFallingBackToRoot() {
        assertNull(SftpManager.renamePaths("/srv/\u0000bad", "new.txt"))
    }

    @Test
    fun invalidNewNameFails() {
        assertNull(SftpManager.renamePaths("/srv/app/old.txt", "../evil"))
        assertNull(SftpManager.renamePaths("/srv/app/old.txt", "a/b"))
    }
}

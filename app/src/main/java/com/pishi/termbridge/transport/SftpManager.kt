package com.pishi.termbridge.transport

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient

/** One remote file/directory entry, UI-independent. */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    /** Remote size in bytes at listing time; -1 when unknown. */
    val isRegularFile: Boolean
)

sealed class SftpError(val userMessage: String) {
    object NotOffered : SftpError("This server does not offer SFTP. File browsing requires an SFTP subsystem.")
    object PermissionDenied : SftpError("Permission denied. The account does not allow this operation.")
    object NotFound : SftpError("Path not found. It may have been moved or deleted.")
    object AlreadyExists : SftpError("An entry with that name already exists.")
    object IntegrityMismatch : SftpError("Transfer finished but the size does not match. The file may be incomplete.")
    object Cancelled : SftpError("Transfer cancelled.")
    object Generic : SftpError("File operation failed. Please try again.")
}

class SftpException(val error: SftpError, cause: Throwable? = null) :
    Exception(error.userMessage, cause)

/**
 * SFTP over an established sshj connection (prompt 07).
 *
 * - Streaming only: upload/download use InputStream/OutputStream
 *   with counting wrappers, so a large file is never loaded into
 *   memory. The buffer lives in sshj, not in this code.
 * - Progress: the counting wrappers report bytes and check a
 *   cancellation flag on every write/read; cancellation throws,
 *   which the caller maps to Cancelled.
 * - Integrity: size comparison only (remote stat size vs bytes
 *   transferred). SFTP has no standard checksum extension that
 *   sshj exposes, so a digest check is NOT claimed — see
 *   docs/SFTP_NOTES.md.
 * - Delete is single-entry only: a non-empty directory cannot be
 *   deleted here (recursive delete is dangerous and is not
 *   implemented in prompt 07; the error tells the user to empty
 *   the directory first).
 * - All paths pass through RemotePath before they reach sshj.
 */
class SftpManager(private val connection: SshjConnection) {
    private var sftp: SFTPClient? = null

    fun ensureSftp(): SFTPClient {
        val existing = sftp
        if (existing != null) return existing
        try {
            val client = connection.sshClient.newSFTPClient()
            sftp = client
            return client
        } catch (e: Exception) {
            throw SftpException(SftpError.NotOffered, e)
        }
    }

    fun list(path: String): List<RemoteEntry> {
        val normalized = RemotePath.normalize(path) ?: throw SftpException(SftpError.NotFound)
        try {
            return ensureSftp().ls(normalized).map { it.toEntry() }
                .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        } catch (e: SftpException) {
            throw e
        } catch (e: Exception) {
            throw SftpException(mapError(e), e)
        }
    }

    fun mkdir(parentPath: String, name: String) {
        val full = RemotePath.join(parentPath, name) ?: throw SftpException(SftpError.Generic)
        try {
            ensureSftp().mkdir(full)
        } catch (e: Exception) {
            throw SftpException(mapError(e), e)
        }
    }

    fun rename(oldPath: String, newName: String) {
        // Security fix 16 (L3): the old code normalised only the
        // NEW path and passed oldPath raw to sshj, and
        // RemotePath.parent() falls back to "/" on an invalid
        // path — so a malformed oldPath renamed from a raw path
        // to "/<newName>". Now both sides are normalised and an
        // un-normalisable old path fails like every other method.
        val (normalizedOld, newPath) = renamePaths(oldPath, newName)
            ?: throw SftpException(SftpError.NotFound)
        try {
            ensureSftp().rename(normalizedOld, newPath)
        } catch (e: Exception) {
            throw SftpException(mapError(e), e)
        }
    }

    fun delete(path: String) {
        val normalized = RemotePath.normalize(path) ?: throw SftpException(SftpError.NotFound)
        try {
            // sshj SFTPClient.rm handles files; for directories it
            // succeeds only when empty (server-side). A non-empty
            // directory failure surfaces as a generic error with
            // a clear message from the caller, not a silent partial
            // delete — there is no recursive delete here.
            ensureSftp().rm(normalized)
        } catch (e: Exception) {
            throw SftpException(mapError(e), e)
        }
    }

    fun statSize(path: String): Long? {
        val normalized = RemotePath.normalize(path) ?: return null
        return try {
            ensureSftp().stat(normalized).size
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Download with progress + cancellation. [expectedSize] comes
     * from the listing; after the stream finishes, bytes written
     * must equal it when it is known, otherwise integrity fails.
     */
    fun download(
        remotePath: String,
        output: OutputStream,
        expectedSize: Long?,
        onProgress: (Long) -> Unit,
        isCancelled: () -> Boolean
    ) {
        val normalized = RemotePath.normalize(remotePath) ?: throw SftpException(SftpError.NotFound)
        val counting = CountingOutputStream(output, onProgress, isCancelled)
        try {
            // sshj 0.40.0 has no get(String, OutputStream):
            // stream through the remote file's own input stream.
            val remote = ensureSftp().open(normalized, setOf(OpenMode.READ))
            try {
                remote.RemoteFileInputStream().copyTo(counting)
            } finally {
                remote.close()
            }
            counting.flush()
        } catch (e: TransferCancelledException) {
            throw SftpException(SftpError.Cancelled, e)
        } catch (e: Exception) {
            throw SftpException(mapError(e), e)
        }
        if (expectedSize != null && expectedSize >= 0 && counting.count != expectedSize) {
            throw SftpException(SftpError.IntegrityMismatch)
        }
    }

    fun upload(
        input: InputStream,
        remotePath: String,
        onProgress: (Long) -> Unit,
        isCancelled: () -> Boolean
    ): Long {
        val normalized = RemotePath.normalize(remotePath) ?: throw SftpException(SftpError.NotFound)
        val counting = CountingInputStream(input, onProgress, isCancelled)
        try {
            // sshj 0.40.0 has no put(InputStream, String):
            // stream through the remote file's own output stream.
            val remote = ensureSftp().open(
                normalized,
                setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
            )
            try {
                counting.copyTo(remote.RemoteFileOutputStream())
            } finally {
                remote.close()
            }
        } catch (e: TransferCancelledException) {
            throw SftpException(SftpError.Cancelled, e)
        } catch (e: Exception) {
            throw SftpException(mapError(e), e)
        }
        val remoteSize = statSize(normalized)
        if (remoteSize != null && remoteSize != counting.count) {
            throw SftpException(SftpError.IntegrityMismatch)
        }
        return counting.count
    }

    fun close() {
        try {
            sftp?.close()
        } catch (_: Exception) {
            // Close is best-effort.
        }
        sftp = null
    }

    companion object {
        /**
         * Pure path resolution for [rename], kept separate so it
         * is JVM-testable without an SFTP peer (the manager
         * itself needs a live sshj connection). Returns null
         * when the old path cannot be normalised or the new
         * name is invalid.
         */
        internal fun renamePaths(oldPath: String, newName: String): Pair<String, String>? {
            val normalizedOld = RemotePath.normalize(oldPath) ?: return null
            val parent = RemotePath.parent(normalizedOld)
            val newPath = RemotePath.join(parent, newName) ?: return null
            return normalizedOld to newPath
        }
    }

    private fun mapError(e: Exception): SftpError {
        val message = e.message ?: ""
        return when {
            "permission" in message.lowercase() -> SftpError.PermissionDenied
            "no such file" in message.lowercase() || "not found" in message.lowercase() -> SftpError.NotFound
            "already exists" in message.lowercase() || "file exists" in message.lowercase() -> SftpError.AlreadyExists
            else -> SftpError.Generic
        }
    }

    private fun RemoteResourceInfo.toEntry(): RemoteEntry {
        val attributes = this.attributes
        return RemoteEntry(
            name = this.name,
            path = this.path,
            isDirectory = this.isDirectory,
            size = attributes.size,
            isRegularFile = this.isRegularFile
        )
    }
}

class TransferCancelledException : IOException("Transfer cancelled.")

private class CountingOutputStream(
    out: OutputStream,
    private val onProgress: (Long) -> Unit,
    private val isCancelled: () -> Boolean
) : FilterOutputStream(out) {
    var count: Long = 0
        private set

    override fun write(b: Int) {
        checkCancelled()
        super.write(b)
        count++
        onProgress(count)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        checkCancelled()
        super.write(b, off, len)
        count += len
        onProgress(count)
    }

    private fun checkCancelled() {
        if (isCancelled()) throw TransferCancelledException()
    }
}

private class CountingInputStream(
    input: InputStream,
    private val onProgress: (Long) -> Unit,
    private val isCancelled: () -> Boolean
) : FilterInputStream(input) {
    var count: Long = 0
        private set

    override fun read(): Int {
        checkCancelled()
        val value = super.read()
        if (value >= 0) {
            count++
            onProgress(count)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkCancelled()
        val read = super.read(b, off, len)
        if (read > 0) {
            count += read
            onProgress(count)
        }
        return read
    }

    private fun checkCancelled() {
        if (isCancelled()) throw TransferCancelledException()
    }
}

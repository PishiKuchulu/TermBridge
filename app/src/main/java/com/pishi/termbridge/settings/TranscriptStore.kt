package com.pishi.termbridge.settings

import android.content.Context
import com.pishi.termbridge.domain.TranscriptLog
import java.io.File

/**
 * Item T-03: file storage for per-host session transcripts.
 *
 * Files live in the app's private directory
 * (`files/transcripts/host-<id>.log`, one rotated generation
 * at `host-<id>.log.1`). Nothing here ever leaves the device
 * except through an encrypted backup the user explicitly
 * opted into per host. Callers run all methods off the main
 * thread and pass ALREADY-SCRUBBED text — the store does not
 * scrub (the writer owns that duty, so there is exactly one
 * scrubbing site).
 */
/** The transcript storage contract — the file implementation
 *  below in production, an in-memory fake in tests. */
interface TranscriptStorage {
    fun append(hostId: Long, text: String)
    fun read(hostId: Long): String
    fun write(hostId: Long, text: String)
    fun clear(hostId: Long)
}

class TranscriptStore(context: Context) : TranscriptStorage {
    private val dir = File(context.filesDir, "transcripts")

    private fun liveFile(hostId: Long) = File(dir, "host-$hostId.log")
    private fun rotatedFile(hostId: Long) = File(dir, "host-$hostId.log.1")

    /** Appends [text], rotating once when the size policy says so. */
    override fun append(hostId: Long, text: String) {
        if (text.isEmpty()) return
        dir.mkdirs()
        val live = liveFile(hostId)
        val incoming = text.toByteArray(Charsets.UTF_8)
        when (TranscriptLog.plan(live.length(), incoming.size)) {
            TranscriptLog.Plan.APPEND -> live.appendBytes(incoming)
            TranscriptLog.Plan.ROTATE_THEN_APPEND -> {
                val rotated = rotatedFile(hostId)
                if (rotated.exists()) rotated.delete()
                if (live.exists()) live.renameTo(rotated)
                live.writeBytes(
                    TranscriptLog.tail(text)
                        .toByteArray(Charsets.UTF_8)
                )
            }
        }
    }

    /** The full current transcript text for a host ("" when none). */
    override fun read(hostId: Long): String {
        val live = liveFile(hostId)
        return if (live.exists()) live.readText(Charsets.UTF_8) else ""
    }

    /** Replaces a host's transcript (backup restore). */
    override fun write(hostId: Long, text: String) {
        dir.mkdirs()
        liveFile(hostId).writeText(TranscriptLog.tail(text), Charsets.UTF_8)
        rotatedFile(hostId).delete()
    }

    override fun clear(hostId: Long) {
        liveFile(hostId).delete()
        rotatedFile(hostId).delete()
    }
}

package com.pishi.termbridge.terminal

import com.pishi.termbridge.domain.EnvApplyResult
import com.pishi.termbridge.transport.SshjConnection
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session

/**
 * A live shell channel over an established SSH connection.
 *
 * - PTY is allocated as "xterm-256color" at the size given at
 *   start; [resize] updates only the local parser grid, because
 *   sshj 0.40.0 exposes no window-change API (its Session has
 *   no setWindowSize method), so the server-side PTY size stays
 *   at the size negotiated at shell start.
 * - Output is read as UTF-8 characters (InputStreamReader, so a
 *   multi-byte character split across reads is decoded correctly),
 *   fed to the VT parser, and published as snapshots.
 * - Input is written to the shell's output stream (the shell's
 *   stdin) as raw bytes — character by character, not as submitted
 *   commands. Interactive semantics are real: the shell echoes,
 *   line-edits, and runs, exactly as a terminal does.
 * - Sensitive output is never logged here. No log call exists in
 *   this file, by design.
 * - Closing the shell channel does NOT close the transport
 *   connection; the ViewModel closes both on disconnect.
 */
class TerminalSession(
    private val connection: SshjConnection,
    initialCols: Int,
    initialRows: Int
) {
    private val parser = VtParser(initialCols, initialRows)

    private val _snapshot = MutableStateFlow(parser.snapshot())
    val snapshot: StateFlow<TerminalSnapshot> = _snapshot.asStateFlow()

    private var session: Session? = null
    private var readerJob: Job? = null
    private var closed = false

    /**
     * Review #1 (C-02): all shell writes go through this channel,
     * drained by a single writer coroutine on Dispatchers.IO.
     * [sendBytes] only enqueues — it never touches the socket, so
     * calling it from a Compose handler (main thread) cannot throw
     * NetworkOnMainThreadException and cannot silently drop keys
     * into a swallowed catch. The channel is UNLIMITED and
     * single-consumer, so byte order is preserved exactly. The
     * channel is recreated on every [start] so a previous [close]
     * cannot leave stale bytes for a later shell.
     */
    private var writeChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var writerJob: Job? = null

    /** Called once when the shell ends (EOF or error). Null
     *  argument = clean EOF (server closed the shell). */
    var onClosed: ((Throwable?) -> Unit)? = null

    /**
     * Prompt 09: start the shell, requesting [envVars] first.
     *
     * Mechanism (verified against sshj 0.40.0, see
     * docs/ENVIRONMENT_AND_PATHS.md): each var is sent with
     * Session.setEnvVar AFTER allocatePTY and BEFORE startShell,
     * which is the only order the protocol allows — env requests
     * belong to the channel before the shell starts. sshj awaits
     * the server's reply for each request, so a rejection raises
     * and is recorded by NAME in [EnvApplyResult.rejectedNames];
     * a rejection is never fatal, because env is advisory — the
     * shell still starts without that var. Values are never
     * logged, never stored here, and the result carries names
     * only. If startShell itself fails, no env result is reported
     * (the connection has failed anyway).
     *
     * There is deliberately NO fallback that injects `export`
     * text into the shell: that would pollute history, break on
     * non-POSIX shells, and hide the fact that the server refused.
     */
    suspend fun start(
        envVars: List<Pair<String, String>> = emptyList(),
        onEnvResult: ((EnvApplyResult) -> Unit)? = null
    ) {
        withContext(Dispatchers.IO) {
            val sess = connection.sshClient.startSession()
            session = sess
            sess.allocatePTY(
                "xterm-256color",
                parser.cols,
                parser.rows,
                0,
                0,
                emptyMap<PTYMode, Int>()
            )
            val applied = mutableListOf<String>()
            val rejected = mutableListOf<String>()
            for ((name, value) in envVars) {
                try {
                    sess.setEnvVar(name, value)
                    applied += name
                } catch (_: Exception) {
                    rejected += name
                }
            }
            sess.startShell()
            onEnvResult?.invoke(
                EnvApplyResult(appliedNames = applied, rejectedNames = rejected)
            )
            writeChannel = Channel(Channel.UNLIMITED)
            writerJob = CoroutineScope(Dispatchers.IO).launch {
                for (bytes in writeChannel) {
                    try {
                        sess.outputStream.write(bytes)
                        sess.outputStream.flush()
                    } catch (e: Exception) {
                        // A failed write means the shell is dead:
                        // surface it the same way the reader loop
                        // does, unless this was an intentional
                        // close (closed == true suppresses it).
                        if (!closed) notifyClosed(e)
                    }
                }
            }
            readerJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val reader = InputStreamReader(sess.inputStream, StandardCharsets.UTF_8)
                    val buffer = CharArray(4096)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        parser.feed(String(buffer, 0, read))
                        _snapshot.value = parser.snapshot()
                    }
                    notifyClosed(null)
                } catch (e: Exception) {
                    if (!closed) notifyClosed(e)
                }
            }
        }
    }

    private var closeNotified = false

    private fun notifyClosed(error: Throwable?) {
        if (closeNotified) return
        closeNotified = true
        onClosed?.invoke(error)
    }

    fun sendBytes(bytes: ByteArray) {
        // Never touches the socket: enqueue for the IO writer.
        // When no shell is running, bytes are dropped, exactly as
        // the old null-session no-op did.
        if (session == null) return
        writeChannel.trySend(bytes)
    }

    fun sendText(text: String) {
        sendBytes(text.toByteArray(StandardCharsets.UTF_8))
    }

    fun sendByte(byte: Byte) {
        sendBytes(byteArrayOf(byte))
    }

    /** Enter, Ctrl-C, Ctrl-D, Tab, Escape, arrows — raw control bytes. */
    fun sendEnter() = sendByte('\r'.code.toByte())
    fun sendCtrlC() = sendByte(0x03)
    fun sendCtrlD() = sendByte(0x04)
    fun sendTab() = sendByte(0x09)
    fun sendEscape() = sendByte(0x1B)
    fun sendArrowUp() = sendText("\u001B[A")
    fun sendArrowDown() = sendText("\u001B[B")
    fun sendArrowRight() = sendText("\u001B[C")
    fun sendArrowLeft() = sendText("\u001B[D")

    fun resize(cols: Int, rows: Int) {
        // No window-change call: sshj 0.40.0 has no setWindowSize
        // API, so the server-side PTY size stays at its start
        // size. The local grid still follows the measured area.
        parser.resize(cols, rows)
        _snapshot.value = parser.snapshot()
    }

    /**
     * Close the shell channel. Callers must call this off the main
     * thread: closing sends SSH_MSG_CHANNEL_CLOSE over the socket
     * (see SessionViewModel.disconnect, which launches IO, and
     * onCleared, which uses a detached IO scope). The writer is
     * cancelled first so no queued bytes are written to a closing
     * channel.
     */
    fun close() {
        closed = true
        readerJob?.cancel()
        writerJob?.cancel()
        writeChannel.close()
        try {
            session?.close()
        } catch (_: Exception) {
            // Close is best-effort.
        }
        session = null
    }
}

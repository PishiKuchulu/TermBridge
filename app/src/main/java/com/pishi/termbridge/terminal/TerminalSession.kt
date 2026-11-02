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
 *   start. [resize] updates the local parser grid AND sends an SSH
 *   window-change request through the `Session.Shell` handle that
 *   `startShell()` returns (`changeWindowDimensions`), so full-screen
 *   programs (vim, less, htop) follow rotation and layout changes.
 *   An earlier comment claimed sshj had no window-change API; it
 *   does, on the Shell object, and the return value was discarded
 *   (review #2 F-10).
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
    initialRows: Int,
    /** TERM requested for the remote PTY (a user setting). */
    private val terminalType: String = "xterm-256color",
    /** Lines kept above the visible screen (a user setting). */
    scrollbackLines: Int = 1000
) {
    private val parser = VtParser(initialCols, initialRows, scrollbackLines)

    /** Review #2 F-10: the parser is mutated by the reader thread
     *  (feed) and by the UI (resize) and read for snapshots; it is
     *  not thread-safe, so every access goes through this lock. */
    private val parserLock = Any()

    private val _snapshot = MutableStateFlow(synchronized(parserLock) { parser.snapshot() })
    val snapshot: StateFlow<TerminalSnapshot> = _snapshot.asStateFlow()

    // These are touched from the reader, writer and UI threads.
    @Volatile private var session: Session? = null
    @Volatile private var shell: Session.Shell? = null
    @Volatile private var readerJob: Job? = null
    @Volatile private var closed = false

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
    @Volatile private var writeChannel = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var writerJob: Job? = null

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
            // Review #2 F-10: publish the (new) channel BEFORE the
            // session, so a key sent while the shell is starting is
            // queued for the writer instead of landing in a channel
            // that is replaced a moment later and dropped.
            writeChannel = Channel(Channel.UNLIMITED)
            val sess = connection.sshClient.startSession()
            session = sess
            sess.allocatePTY(
                terminalType,
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
            shell = sess.startShell()
            onEnvResult?.invoke(
                EnvApplyResult(appliedNames = applied, rejectedNames = rejected)
            )
            val channel = writeChannel
            writerJob = CoroutineScope(Dispatchers.IO).launch {
                for (bytes in channel) {
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
                        val next = synchronized(parserLock) {
                            parser.feed(String(buffer, 0, read))
                            parser.snapshot()
                        }
                        _snapshot.value = next
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
        // Reader and writer can both report; deliver exactly once.
        synchronized(this) {
            if (closeNotified) return
            closeNotified = true
        }
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
        val next = synchronized(parserLock) {
            parser.resize(cols, rows)
            parser.snapshot()
        }
        _snapshot.value = next
        // Tell the server too (SSH window-change). It is network I/O,
        // so it never runs on the calling (UI) thread; failures are
        // non-fatal — the local grid has already changed.
        val sh = shell ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sh.changeWindowDimensions(cols, rows, 0, 0)
            } catch (_: Exception) {
            }
        }
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
        shell = null
    }
}

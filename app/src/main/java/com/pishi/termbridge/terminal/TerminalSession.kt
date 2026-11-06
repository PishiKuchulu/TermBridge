package com.pishi.termbridge.terminal

import com.pishi.termbridge.domain.EnvApplyResult
import com.pishi.termbridge.transport.SshjConnection
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
     * into a swallowed catch. The channel is capacity-bounded
     * (audit #6 TB-006) and single-consumer, so byte order is
     * preserved exactly. The
     * channel is recreated on every [start] so a previous [close]
     * cannot leave stale bytes for a later shell.
     */
    @Volatile private var writeChannel = Channel<ByteArray>(WRITE_CHANNEL_CAPACITY)
    @Volatile private var writerJob: Job? = null

    // Audit #6 TB-020: reader / writer / resize jobs run in a
    // scope this session OWNS and cancels in [close]; the old
    // ad-hoc CoroutineScope(Dispatchers.IO) launches were never
    // cancelled and their containers outlived the session.
    @Volatile private var scopeJob = SupervisorJob()
    @Volatile private var sessionScope = CoroutineScope(scopeJob + Dispatchers.IO)

    private fun activeScope(): CoroutineScope {
        if (scopeJob.isCancelled) {
            scopeJob = SupervisorJob()
            sessionScope = CoroutineScope(scopeJob + Dispatchers.IO)
        }
        return sessionScope
    }

    // Audit #6 TB-006: the input queue is bounded in elements AND
    // in queued bytes; see [sendBytes].
    private val queuedInputBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val droppedInputBytesCounter =
        java.util.concurrent.atomic.AtomicLong(0)

    /** Input bytes dropped so far because the queue was at its
     *  bound (stalled connection, oversized paste). */
    val droppedInputBytes: Long get() = droppedInputBytesCounter.get()

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
     * This layer deliberately has NO fallback that injects
     * `export` text into the shell. Item F-02 later added one
     * anyway — but one layer up, in SessionViewModel, and only
     * behind a per-host opt-in toggle plus the shell
     * confirmation: the typed lines are visible on screen, the
     * session status names them as fallback-applied, and the
     * rendering refuses anything that cannot be typed safely
     * (see domain/ExportCommand). The refusal itself is never
     * hidden: names the fallback cannot cover stay in
     * [EnvApplyResult.rejectedNames].
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
            writeChannel = Channel(WRITE_CHANNEL_CAPACITY)
            queuedInputBytes.set(0)
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
            writerJob = activeScope().launch {
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
                    } finally {
                        queuedInputBytes.addAndGet(-bytes.size.toLong())
                    }
                }
            }
            readerJob = activeScope().launch {
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

    /** Local display clear (item T-19): nothing is sent to the
     *  remote shell; the parser's screen (and, on the main
     *  screen, its scrollback) is blanked and republished. */
    fun clearLocal() {
        val next = synchronized(parserLock) {
            parser.clearScreenAndScrollback()
            parser.snapshot()
        }
        _snapshot.value = next
    }

    fun sendBytes(bytes: ByteArray) {
        // Never touches the socket: enqueue for the IO writer.
        // When no shell is running, bytes are dropped, exactly as
        // the old null-session no-op did.
        if (session == null) return
        // Audit #6 TB-006: bounded queueing. A multi-megabyte
        // paste into a stalled connection used to grow the heap
        // without limit; excess input is now dropped and counted
        // in [droppedInputBytes] instead.
        val size = bytes.size.toLong()
        while (true) {
            val current = queuedInputBytes.get()
            if (current + size > MAX_QUEUED_INPUT_BYTES) {
                droppedInputBytesCounter.addAndGet(size)
                return
            }
            if (queuedInputBytes.compareAndSet(current, current + size)) break
        }
        if (writeChannel.trySend(bytes).isFailure) {
            queuedInputBytes.addAndGet(-size)
            droppedInputBytesCounter.addAndGet(size)
        }
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
        activeScope().launch {
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
        scopeJob.cancel()
        writeChannel.close()
        try {
            session?.close()
        } catch (_: Exception) {
            // Close is best-effort.
        }
        session = null
        shell = null
    }

    companion object {
        /** Audit #6 TB-006: element bound for the input queue. */
        const val WRITE_CHANNEL_CAPACITY = 8192

        /** Audit #6 TB-006: total queued input bytes bound. */
        const val MAX_QUEUED_INPUT_BYTES = 4L * 1024 * 1024
    }
}

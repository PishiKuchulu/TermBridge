package com.pishi.termbridge.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.domain.RfbKeys
import com.pishi.termbridge.transport.RfbConnector
import com.pishi.termbridge.domain.RfbException
import com.pishi.termbridge.transport.RfbServerMessage
import com.pishi.termbridge.transport.RfbSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class DesktopStatus { IDLE, CONNECTING, CONNECTED, FAILED }

data class DesktopUiState(
    val status: DesktopStatus = DesktopStatus.IDLE,
    val error: String? = null,
    val desktopName: String = ""
)

/** One rendered frame; [version] changes on every update so a redraw is triggered. */
class DesktopFrame(val bitmap: Bitmap, val version: Long) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height
}

/**
 * Drives a VNC (RFB) session: connect, keep the framebuffer fresh,
 * and forward pointer and key input. All socket I/O is on IO threads;
 * input is queued through a single consumer so events stay ordered
 * and never touch the network on the main thread. The VNC password is
 * used once for the handshake and is never stored.
 */
class RemoteDesktopViewModel : ViewModel() {
    private val _state = MutableStateFlow(DesktopUiState())
    val state: StateFlow<DesktopUiState> = _state.asStateFlow()

    private val _frame = MutableStateFlow<DesktopFrame?>(null)
    val frame: StateFlow<DesktopFrame?> = _frame.asStateFlow()

    private var session: RfbSession? = null
    private var job: Job? = null
    private var outbox = Channel<() -> Unit>(Channel.UNLIMITED)
    private var bitmap: Bitmap? = null
    private var version = 0L

    fun connect(host: String, port: Int, password: String, noPassword: Boolean) {
        disconnect()
        _state.value = DesktopUiState(status = DesktopStatus.CONNECTING)
        val secret = if (password.isEmpty()) null else password.toCharArray()
        job = viewModelScope.launch {
            try {
                val opened = RfbConnector.connect(
                    hostname = host,
                    port = port,
                    password = secret,
                    allowUnauthenticated = noPassword,
                    // No idle read timeout: a quiet desktop sends nothing.
                    socketTimeoutMs = Int.MAX_VALUE
                )
                session = opened
                _state.value = DesktopUiState(
                    status = DesktopStatus.CONNECTED,
                    desktopName = opened.desktopName
                )
                outbox = Channel(Channel.UNLIMITED)
                val sender = launch(Dispatchers.IO) {
                    for (action in outbox) {
                        try {
                            action()
                        } catch (_: Exception) {
                            // The reader loop reports the broken connection.
                        }
                    }
                }
                try {
                    launch(Dispatchers.IO) { readLoop(opened) }.join()
                } finally {
                    sender.cancel()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: RfbException) {
                fail(e.message ?: "Connection failed.")
            } catch (e: Exception) {
                fail("Connection failed.")
            } finally {
                secret?.fill('\u0000')
            }
        }
    }

    private fun readLoop(opened: RfbSession) {
        try {
            opened.requestUpdate(false)
            while (job?.isActive != false) {
                when (opened.readServerMessage()) {
                    RfbServerMessage.FramebufferApplied -> {
                        publish(opened)
                        opened.requestUpdate(true)
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            if (_state.value.status == DesktopStatus.CONNECTED) {
                fail(if (e is RfbException) e.message ?: "Connection lost." else "Connection lost.")
            }
        } finally {
            opened.close()
        }
    }

    private fun publish(opened: RfbSession) {
        val fb = opened.framebuffer
        var target = bitmap
        if (target == null || target.width != fb.width || target.height != fb.height) {
            target = Bitmap.createBitmap(fb.width, fb.height, Bitmap.Config.ARGB_8888)
            bitmap = target
        }
        target.setPixels(fb.pixels, 0, fb.width, 0, 0, fb.width, fb.height)
        version += 1
        _frame.value = DesktopFrame(target, version)
    }

    private fun fail(message: String) {
        _state.value = DesktopUiState(status = DesktopStatus.FAILED, error = message)
    }

    fun disconnect() {
        job?.cancel()
        job = null
        val old = session
        session = null
        outbox.close()
        if (old != null) {
            viewModelScope.launch(Dispatchers.IO) { old.close() }
        }
        _frame.value = null
        bitmap = null
        _state.value = DesktopUiState()
    }

    private fun post(action: (RfbSession) -> Unit) {
        val current = session ?: return
        outbox.trySend { action(current) }
    }

    fun pointer(buttonMask: Int, x: Int, y: Int) = post { it.sendPointer(buttonMask, x, y) }

    fun key(keysym: Int, down: Boolean) {
        if (keysym != 0) post { it.sendKey(down, keysym) }
    }

    /** Press and release one key. */
    fun tap(keysym: Int) {
        key(keysym, true)
        key(keysym, false)
    }

    fun typeText(text: String) {
        text.codePoints().forEach { cp ->
            val sym = RfbKeys.keysymForCodePoint(cp)
            if (sym != 0) tap(sym)
        }
    }

    fun ctrlAltDelete() {
        key(RfbKeys.CONTROL_L, true)
        key(RfbKeys.ALT_L, true)
        tap(RfbKeys.DELETE)
        key(RfbKeys.ALT_L, false)
        key(RfbKeys.CONTROL_L, false)
    }

    override fun onCleared() {
        disconnect()
    }
}

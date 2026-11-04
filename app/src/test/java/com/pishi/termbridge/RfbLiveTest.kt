package com.pishi.termbridge

import com.pishi.termbridge.domain.RfbException
import com.pishi.termbridge.transport.RfbConnector
import com.pishi.termbridge.transport.RfbServerMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Item F-03a: the RFB stack against a REAL VNC server, on the
 * JVM — the CI proof route for VNC that does not touch the
 * emulator job (installing a desktop stack inside that job
 * hung it twice and was reverted; see docs/VNC_NOTES.md).
 *
 * Gated on environment variables, set only by the dedicated
 * `vnc` CI job, which provisions Xvfb + x11vnc on its own
 * runner (800x600 display, per-run secret):
 *   TB_VNC_HOST, TB_VNC_PORT, TB_VNC_PASS
 * Everywhere else these tests SKIP — never a fake pass. The
 * instrumented RfbIntegrationTest remains the on-device twin.
 */
class RfbLiveTest {

    private fun hostName(): String = System.getenv("TB_VNC_HOST") ?: ""

    private fun port(): Int =
        System.getenv("TB_VNC_PORT")?.toIntOrNull() ?: 5901

    private fun secretChars(): CharArray =
        (System.getenv("TB_VNC_PASS") ?: "").toCharArray()

    private fun secretPresent(): Boolean =
        !System.getenv("TB_VNC_PASS").isNullOrEmpty()

    private fun assumeLive() {
        assumeTrue(
            "VNC live fixture not configured (TB_VNC_HOST/TB_VNC_PASS) — " +
                "only the vnc CI job sets these",
            hostName().isNotBlank() && secretPresent()
        )
    }

    @Test
    fun handshakeAndFirstFrameAgainstRealServer() = runBlocking {
        assumeLive()
        val session = RfbConnector.connect(
            hostName(), port(), secretChars(), false,
            socketTimeoutMs = 30_000
        )
        try {
            assertEquals(800, session.framebuffer.width)
            assertEquals(600, session.framebuffer.height)
            withContext(Dispatchers.IO) {
                session.requestUpdate(false)
                var applied = false
                var guard = 0
                while (!applied && guard++ < 20) {
                    if (session.readServerMessage() ==
                        RfbServerMessage.FramebufferApplied
                    ) {
                        applied = true
                    }
                }
                assertTrue("no framebuffer update received", applied)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun wrongSecretIsRejectedByRealServer() = runBlocking {
        assumeLive()
        val wrong = "definitely-wrong-secret".toCharArray()
        try {
            RfbConnector.connect(
                hostName(), port(), wrong, false,
                socketTimeoutMs = 30_000
            )
            fail("A wrong VNC secret must not connect")
        } catch (_: RfbException) {
            // Expected: authentication failure surfaces as the
            // fixed RfbException, with no secret in the message.
        }
    }
}

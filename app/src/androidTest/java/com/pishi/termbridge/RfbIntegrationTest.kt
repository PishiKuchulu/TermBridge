package com.pishi.termbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pishi.termbridge.transport.RfbConnector
import com.pishi.termbridge.transport.RfbServerMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first test of the RFB core against a REAL server (x11vnc on a
 * virtual 800x600 display, started by the CI fixture). Skipped when
 * the fixture could not provide a VNC server, so it can never report
 * a pass it did not earn.
 */
@RunWith(AndroidJUnit4::class)
class RfbIntegrationTest {
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun realVncServerHandshakeAndFirstFrame() = runBlocking {
        val password = args.getString("fixture_vnc_pass", "")
        assumeTrue("VNC fixture not available", password.isNotEmpty())
        val port = args.getString("fixture_vnc_port", "5901").toInt()
        val session = RfbConnector.connect(
            hostname = "10.0.2.2",
            port = port,
            password = password.toCharArray(),
            allowUnauthenticated = false,
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
                    if (session.readServerMessage() == RfbServerMessage.FramebufferApplied) applied = true
                }
                assertTrue("no framebuffer update received", applied)
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun wrongPasswordIsRejected() = runBlocking {
        val password = args.getString("fixture_vnc_pass", "")
        assumeTrue("VNC fixture not available", password.isNotEmpty())
        val port = args.getString("fixture_vnc_port", "5901").toInt()
        var failed = false
        try {
            RfbConnector.connect("10.0.2.2", port, "definitely-wrong".toCharArray(), false).close()
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("a wrong VNC password must not connect", failed)
    }
}

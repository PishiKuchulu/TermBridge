package com.pishi.termbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandRunner
import com.pishi.termbridge.domain.CommandTemplate
import com.pishi.termbridge.domain.CommandVariable
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.ProxyConfig
import com.pishi.termbridge.domain.ProxyKind
import com.pishi.termbridge.domain.RemoteShell
import com.pishi.termbridge.domain.TunnelConfig
import com.pishi.termbridge.domain.TunnelKind
import com.pishi.termbridge.domain.CdCommand
import com.pishi.termbridge.domain.TerminalSender
import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.terminal.TerminalSession
import com.pishi.termbridge.terminal.asText
import com.pishi.termbridge.domain.EnvApplyResult
import com.pishi.termbridge.transport.SftpError
import com.pishi.termbridge.transport.SftpException
import com.pishi.termbridge.transport.SftpManager
import com.pishi.termbridge.domain.HostKeyVerification
import com.pishi.termbridge.transport.JumpHopRequest
import com.pishi.termbridge.transport.SshConnection
import com.pishi.termbridge.transport.SshConnectionRequest
import com.pishi.termbridge.transport.SshExecChannel
import com.pishi.termbridge.transport.SshError
import com.pishi.termbridge.transport.SshTransport
import com.pishi.termbridge.transport.SshTransportException
import com.pishi.termbridge.transport.SshTunnelManager
import com.pishi.termbridge.transport.SshjConnection
import com.pishi.termbridge.transport.TunnelException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests against the controlled SSH fixture
 * (tests/fixture/setup-sshd-fixture.sh). Tier 1 below is
 * IMPLEMENTED and runs against a real OpenSSH server: every
 * test calls SshFixture.assumeAvailable() first, so without
 * a fixture the test SKIPS with a clear message — never a
 * fake pass, never a mock called end-to-end.
 *
 * Tier 2: the five forwarding cases (item T-20) are
 * implemented against the extended fixture (bastion sshd,
 * echo service, SOCKS5 proxy — see SshFixture and
 * tests/fixture/setup-sshd-fixture.sh); they skip only when
 * the fixture extras were not provisioned. changedHostKeyFlow
 * (item R-10) runs against a test-local relay re-pointed from
 * the main sshd to the bastion, which presents its own host
 * keys — no test in this class is @Ignore'd anymore.
 *
 * Shell-output assertions use a case trick, not wishful
 * thinking: the PTY echoes the typed command, so asserting
 * the command's own text proves nothing. Commands transform
 * their output (tr 'A-Z' 'a-z'), and tests await the
 * TRANSFORMED text, which appears only as real output.
 */
@RunWith(AndroidJUnit4::class)
class SshIntegrationTest {

    private fun transport(store: InMemoryTrustStore) = SshTransport(store)

    private suspend fun connect(
        store: InMemoryTrustStore,
        handler: TestDecisionHandler,
        profile: HostProfile = SshFixture.profile(),
        credential: ByteArray = SshFixture.credential()
    ): SshConnection {
        try {
            return transport(store).connect(
                SshConnectionRequest(profile = profile, credential = credential),
                handler
            )
        } finally {
            credential.wipe()
        }
    }

    private fun asSshj(conn: SshConnection): SshjConnection =
        conn as? SshjConnection ?: throw AssertionError("Not an sshj connection")

    private fun snapshotText(session: TerminalSession): String =
        session.snapshot.value.lines.joinToString("\n") { it.asText() }

    private suspend fun awaitOutput(
        session: TerminalSession,
        needle: String,
        timeoutMs: Long = 90_000
    ) {
        // runTest uses VIRTUAL time: a withTimeout inside it fires the
        // moment the test dispatcher idles, long before real I/O could
        // answer. Wait on a real-time dispatcher instead.
        val found = withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMs) {
                session.snapshot.first { snap ->
                    snap.lines.any { it.asText().contains(needle) }
                }
            }
        }
        // The fixture shell is not secret; show what the screen held.
        assertNotNull(
            "Timed out waiting for shell output: $needle; screen was:\n${snapshotText(session)}",
            found
        )
    }

    private suspend fun shell(
        conn: SshConnection,
        block: suspend (TerminalSession) -> Unit
    ) {
        val session = TerminalSession(asSshj(conn), 80, 24)
        try {
            session.start()
            block(session)
        } finally {
            session.close()
        }
    }

    private fun uniqueRemote(name: String): String =
        "/tmp/termbridge-it-${UUID.randomUUID()}-$name"

    // ---- Item T-20 TCP helpers (blocking I/O on real dispatchers;
    // runTest's virtual clock must never govern a socket) ----

    /** Write [payload] to host:port and read the same number of
     *  bytes back (the fixture echo service returns them). */
    private suspend fun tcpEcho(
        host: String,
        port: Int,
        payload: String
    ): String = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 10_000)
            socket.soTimeout = 20_000
            val expected = payload.toByteArray()
            socket.getOutputStream().write(expected)
            socket.getOutputStream().flush()
            val buffer = ByteArray(expected.size)
            var read = 0
            while (read < buffer.size) {
                val n = socket.getInputStream()
                    .read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read)
        }
    }

    /** Assert that nothing accepts TCP connections at host:port,
     *  polling on a real-time dispatcher (listener teardown on
     *  the server side is asynchronous). */
    private suspend fun assertTcpRefused(host: String, port: Int) {
        val refused = withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + 20_000
            var ok = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    Socket().use {
                        it.connect(InetSocketAddress(host, port), 2_000)
                    }
                    Thread.sleep(300)
                } catch (_: Exception) {
                    ok = true
                    break
                }
            }
            ok
        }
        assertTrue("Expected nothing listening on $host:$port", refused)
    }

    // ---------- Tier 1: implemented ----------

    /**
     * Review #2 F-12: the fixture used to be password-only, so the
     * private-key path (including the review #1 loadKeys fix) was
     * never exercised against a real server.
     */
    @Test
    fun publicKeyAuthSucceeds() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        org.junit.Assume.assumeTrue("Key fixture not configured", SshFixture.isKeyConfigured)
        val store = InMemoryTrustStore()
        val conn = connect(
            store,
            TestDecisionHandler(answerUnknown = true),
            profile = SshFixture.keyProfile(),
            credential = SshFixture.keyCredential()
        )
        try {
            assertTrue(conn.isConnected)
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun realHandshakeAndPasswordAuthSucceeds() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        val store = InMemoryTrustStore()
        val conn = connect(store, TestDecisionHandler(answerUnknown = true))
        try {
            assertTrue(conn.isConnected)
            assertTrue(store.stored(conn.profile.endpoint) != null)
        } finally {
            conn.disconnect()
        }
        assertFalse(conn.isConnected)
    }

    @Test
    fun wrongCredentialsFailSafely() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        val store = InMemoryTrustStore()
        val wrong = "wrong-password-12345".toByteArray()
        try {
            connect(store, TestDecisionHandler(), credential = wrong)
            fail("Wrong credentials must not connect")
        } catch (e: SshTransportException) {
            assertEquals(SshError.AuthFailed, e.error)
            // The full user-facing text (fixed message plus the
            // class/enum-name diagnostic) must not echo the input.
            assertFalse(e.userFacingMessage().contains("wrong-password-12345"))
            assertFalse(e.stackTraceToString().contains("wrong-password-12345"))
        } finally {
            wrong.wipe()
        }
        assertNull(store.stored(SshFixture.profile().endpoint))
    }

    @Test
    fun unknownHostKeyTrustFlow() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        val endpoint = SshFixture.profile().endpoint
        val store = InMemoryTrustStore()
        // Reject: connect fails and nothing is persisted.
        try {
            connect(store, TestDecisionHandler(answerUnknown = false))
            fail("Rejected host key must not connect")
        } catch (_: SshTransportException) {
            // Any transport failure is acceptable here; the
            // assertions that matter are persistence + retry.
        }
        assertNull(store.stored(endpoint))
        // Trust: connects and persists the fingerprint.
        val handler = TestDecisionHandler(answerUnknown = true)
        val conn = connect(store, handler)
        try {
            assertTrue(conn.isConnected)
            val stored = store.stored(endpoint)
            assertNotNull(stored)
            assertEquals(handler.decisions.single().fingerprint, stored)
        } finally {
            conn.disconnect()
        }
        // Second connect: stored fingerprint matches, so no
        // decision callback may fire at all — a handler that
        // throws on any call proves it.
        val conn2 = connect(store, object : TestDecisionHandler() {
            override suspend fun decideUnknown(
                pending: com.pishi.termbridge.transport.PendingTrust
            ): Boolean = throw AssertionError("No decision expected on match")
        })
        try {
            assertTrue(conn2.isConnected)
        } finally {
            conn2.disconnect()
        }
    }

    @Test
    fun disconnectAndCancelReleaseResources() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        val store = InMemoryTrustStore()
        val conn = connect(store, TestDecisionHandler())
        assertTrue(conn.isConnected)
        conn.disconnect()
        assertFalse(conn.isConnected)
        // Cancel mid-connect to an unroutable address, then
        // prove a normal connect still works (no leaked client).
        val unreachable = HostProfile(
            name = "unreachable",
            hostname = "10.255.255.1",
            port = 2222,
            username = "nobody",
            authType = AuthType.PASSWORD
        )
        val job = launch {
            try {
                transport(store).connect(
                    SshConnectionRequest(
                        profile = unreachable,
                        credential = "x".toByteArray(),
                        connectTimeoutMs = 10_000
                    ),
                    TestDecisionHandler()
                )
            } catch (_: Exception) {
                // The blocking connect cannot be interrupted: when the
                // cancel lands it ends with its own timeout/transport
                // error. That is the expected shape, not a test failure.
            }
        }
        delay(500)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        val conn2 = connect(store, TestDecisionHandler())
        try {
            assertTrue(conn2.isConnected)
        } finally {
            conn2.disconnect()
        }
    }

    @Test
    fun interactiveShellStreamsSeveralCommands() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        val conn = connect(InMemoryTrustStore(), TestDecisionHandler())
        try {
            shell(conn) { session ->
                session.sendText("echo STREAM_ONE | tr 'A-Z' 'a-z'")
                session.sendEnter()
                awaitOutput(session, "stream_one")
                session.sendText("echo STREAM_TWO | tr 'A-Z' 'a-z'")
                session.sendEnter()
                awaitOutput(session, "stream_two")
                session.sendText("echo STREAM_THREE | tr 'A-Z' 'a-z'")
                session.sendEnter()
                awaitOutput(session, "stream_three")
            }
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun sftpUploadDownloadRoundTrip() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        val conn = connect(InMemoryTrustStore(), TestDecisionHandler())
        val sftp = SftpManager(asSshj(conn))
        try {
            val remote = uniqueRemote("roundtrip.bin")
            val payload = "termbridge sftp roundtrip ✓".toByteArray()
            val written = sftp.upload(
                ByteArrayInputStream(payload), remote, {}, { false }
            )
            assertEquals(payload.size.toLong(), written)
            val out = ByteArrayOutputStream()
            sftp.download(remote, out, payload.size.toLong(), {}, { false })
            assertEquals(payload.toList(), out.toByteArray().toList())
        } finally {
            sftp.close()
            conn.disconnect()
        }
    }

    @Test
    fun sftpDeleteRemovesFileAndEmptyDirectory() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        val conn = connect(InMemoryTrustStore(), TestDecisionHandler())
        val sftp = SftpManager(asSshj(conn))
        try {
            // File delete goes through rm.
            val file = uniqueRemote("todelete.bin")
            sftp.upload(ByteArrayInputStream("x".toByteArray()), file, {}, { false })
            assertNotNull(sftp.statSize(file))
            sftp.delete(file, false)
            assertNull(sftp.statSize(file))
            // Empty-directory delete goes through rmdir. Review #1
            // (H-08): plain rm fails on directories on OpenSSH,
            // even empty ones, so this is the regression test.
            val dirName = "termbridge-it-${UUID.randomUUID()}-todeletedir"
            sftp.mkdir("/tmp", dirName)
            val dir = "/tmp/$dirName"
            sftp.delete(dir, true)
            assertNull(sftp.statSize(dir))
        } finally {
            sftp.close()
            conn.disconnect()
        }
    }

    @Test
    fun sftpZeroByteUnicodeConflictAndCancellation() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        val conn = connect(InMemoryTrustStore(), TestDecisionHandler())
        val sftp = SftpManager(asSshj(conn))
        try {
            // Zero-byte file round-trips.
            val zero = uniqueRemote("zero.bin")
            assertEquals(0L, sftp.upload(ByteArrayInputStream(ByteArray(0)), zero, {}, { false }))
            val zeroOut = ByteArrayOutputStream()
            sftp.download(zero, zeroOut, 0L, {}, { false })
            assertEquals(0, zeroOut.size())
            // Spaces + Unicode in the name round-trip.
            val unicode = uniqueRemote("termbridge it ünicode ✓.bin")
            val payload = "unicode-name".toByteArray()
            sftp.upload(ByteArrayInputStream(payload), unicode, {}, { false })
            val uniOut = ByteArrayOutputStream()
            sftp.download(unicode, uniOut, payload.size.toLong(), {}, { false })
            assertEquals(payload.toList(), uniOut.toByteArray().toList())
            // Cancellation mid-transfer: a large upload whose
            // progress callback flips the flag must fail as
            // Cancelled, not silently complete or corrupt.
            val big = uniqueRemote("big.bin")
            val bigPayload = ByteArray(16 * 1024 * 1024)
            var cancelled = false
            try {
                sftp.upload(
                    ByteArrayInputStream(bigPayload), big,
                    { seen -> if (seen > 1024 * 1024) cancelled = true },
                    { cancelled }
                )
                fail("Cancelled upload must not complete")
            } catch (e: SftpException) {
                assertEquals(SftpError.Cancelled, e.error)
            }
        } finally {
            sftp.close()
            conn.disconnect()
        }
    }

    @Test
    fun commandButtonExecuteOneAppearsInShell() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        val conn = connect(InMemoryTrustStore(), TestDecisionHandler())
        try {
            shell(conn) { session ->
                val sender = object : TerminalSender {
                    override fun sendText(text: String) = session.sendText(text)
                    override fun sendEnter() = session.sendEnter()
                }
                val button = CommandButton(
                    hostId = 0L,
                    label = "exec",
                    mode = CommandMode.EXECUTE_ONE,
                    template = "echo BTN_EXEC | tr 'A-Z' 'a-z'"
                )
                CommandRunner(sender).executeOne(
                    CommandTemplate.prepare(button, emptyMap(), RemoteShell.POSIX, true)
                )
                awaitOutput(session, "btn_exec")
            }
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun commandButtonInsertOnlyDoesNotExecute() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        val store = InMemoryTrustStore()
        val conn = connect(store, TestDecisionHandler())
        val probe = uniqueRemote("insert-probe")
        try {
            shell(conn) { session ->
                val sender = object : TerminalSender {
                    override fun sendText(text: String) = session.sendText(text)
                    override fun sendEnter() = session.sendEnter()
                }
                val button = CommandButton(
                    hostId = 0L,
                    label = "insert",
                    mode = CommandMode.INSERT_ONLY,
                    template = "touch $probe"
                )
                // Typing before the remote shell has printed its prompt
                // races its start-up (it may reset the line), so wait for
                // the prompt first, as a person would.
                awaitOutput(session, "$")
                CommandRunner(sender).insert(
                    CommandTemplate.prepare(button, emptyMap(), RemoteShell.POSIX, true)
                )
                // The text is placed (echoed) but never submitted. Match a
                // SHORT prefix: prompt + the full probe path is wider than
                // the 80-column grid, so it wraps across two rows and no
                // single row would ever contain the whole needle.
                awaitOutput(session, "touch /tmp/termbridge-it-")
                delay(2000)
            }
            // Proof it never ran: the probe file must not exist.
            val sftp = SftpManager(asSshj(conn))
            try {
                assertNull(sftp.statSize(probe))
            } finally {
                sftp.close()
            }
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun envVarAcceptedAndRejectedAreReported() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        val conn = connect(InMemoryTrustStore(), TestDecisionHandler())
        try {
            val session = TerminalSession(asSshj(conn), 80, 24)
            try {
                var result: EnvApplyResult? = null
                session.start(
                    listOf(
                        "TERM_BRIDGE_HELLO" to "AcceptedValue42",
                        "UNLISTED_VAR_X" to "rejected-99"
                    )
                ) { result = it }
                val applied = result?.appliedNames ?: emptyList()
                val rejected = result?.rejectedNames ?: emptyList()
                assertTrue(applied.contains("TERM_BRIDGE_HELLO"))
                assertTrue(rejected.contains("UNLISTED_VAR_X"))
                // Accepted var is really visible in the shell;
                // the rejected value never reaches it.
                session.sendText("printenv TERM_BRIDGE_HELLO | tr 'A-Z' 'a-z'")
                session.sendEnter()
                awaitOutput(session, "acceptedvalue42")
                assertFalse(snapshotText(session).contains("rejected-99"))
            } finally {
                session.close()
            }
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun terminalStartsInConfiguredWorkingDirectory() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        val conn = connect(InMemoryTrustStore(), TestDecisionHandler())
        try {
            shell(conn) { session ->
                // PWD output proven by adjacency: "/tmpENDPWD"
                // appears only as command output, never as echo.
                session.sendText(CdCommand.render("/tmp", RemoteShell.POSIX))
                session.sendEnter()
                session.sendText("pwd | tr -d '\n' && echo ENDPWD")
                session.sendEnter()
                awaitOutput(session, "/tmpENDPWD")
                // Missing directory: shell error, session alive.
                session.sendText("cd /tmp/no-such-dir-xyz-123; echo CDDONE")
                session.sendEnter()
                awaitOutput(session, "CDDONE")
                session.sendText("echo STILL_ALIVE | tr 'A-Z' 'a-z'")
                session.sendEnter()
                awaitOutput(session, "still_alive")
            }
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun sftpDownloadLandsInLocalFolder() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        // SftpManager has no working-directory concept: paths
        // are absolute only. This test proves what the code
        // actually does — absolute-path transfer plus landing
        // in a local folder — not a cwd premise the code lacks.
        val conn = connect(InMemoryTrustStore(), TestDecisionHandler())
        val sftp = SftpManager(asSshj(conn))
        try {
            val remote = uniqueRemote("local-landing.bin")
            val payload = "local landing ✓".toByteArray()
            sftp.upload(ByteArrayInputStream(payload), remote, {}, { false })
            val local = java.io.File(
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation().targetContext.cacheDir,
                "termbridge-it-landing-${UUID.randomUUID()}.bin"
            )
            try {
                java.io.FileOutputStream(local).use { out ->
                    sftp.download(remote, out, payload.size.toLong(), {}, { false })
                }
                assertEquals(payload.toList(), local.readBytes().toList())
            } finally {
                local.delete()
            }
        } finally {
            sftp.close()
            conn.disconnect()
        }
    }

    // ---------- Tier 2 ----------

    /**
     * Item R-10: the changed-host-key flow, end to end. A
     * test-local TCP relay on the emulator's own loopback is
     * the endpoint the app dials; the relay first forwards to
     * the main fixture sshd (host key A), then is re-pointed
     * at the bastion sshd, which since R-10 presents its OWN
     * host keys (key B). The dialed endpoint never changes, so
     * the app must report a CHANGED key — and a rejection must
     * leave the stored fingerprint untouched.
     */
    @Test
    fun changedHostKeyFlow() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        SshFixture.assumeForwardingAvailable()
        val relay = TcpRelay(SshFixture.host, SshFixture.port)
        relay.start()
        try {
            val relayProfile = HostProfile(
                name = "fixture-relay",
                hostname = "127.0.0.1",
                port = relay.port,
                username = SshFixture.username,
                authType = AuthType.PASSWORD
            )
            val endpoint = relayProfile.endpoint
            val store = InMemoryTrustStore()
            // First connect through the relay: unknown key A,
            // trusted and stored.
            val first = connect(
                store, TestDecisionHandler(answerUnknown = true),
                profile = relayProfile
            )
            first.disconnect()
            val storedA = store.stored(endpoint)
            assertNotNull(storedA)
            // Re-point the relay at the bastion: same endpoint,
            // different host key behind it now.
            relay.retarget(SshFixture.host, SshFixture.bastionPort)
            val handler = TestDecisionHandler(
                answerUnknown = true, answerChanged = false
            )
            try {
                connect(store, handler, profile = relayProfile)
                fail("A changed host key that the user rejects must not connect")
            } catch (_: SshTransportException) {
                // Expected: verification rejected the connection.
            }
            val changed = handler.decisions.filter {
                it.verification == HostKeyVerification.CHANGED
            }
            assertEquals(1, changed.size)
            assertEquals(storedA, changed.single().storedFingerprint)
            assertFalse(changed.single().fingerprint == storedA)
            // The rejection changed nothing in the store.
            assertEquals(storedA, store.stored(endpoint))
        } finally {
            relay.close()
        }
    }

    /**
     * Item R-10: an RSA client key authenticates against the
     * modern fixture sshd. OpenSSH 8.8+ refuses SHA-1 ssh-rsa
     * signatures; sshj signs with rsa-sha2-256/512, so this
     * passes only if the whole chain (key parse, SHA-2
     * signature, server acceptance) is right.
     */
    @Test
    fun rsaSha2ClientKeyAuthenticates() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        SshFixture.assumeRsaKeyAvailable()
        val conn = connect(
            InMemoryTrustStore(),
            TestDecisionHandler(answerUnknown = true),
            profile = SshFixture.keyProfile(),
            credential = SshFixture.rsaKeyCredential()
        )
        try {
            assertTrue(conn.isConnected)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Item T-20: a local forward carries real TCP traffic to
     * the fixture echo service, and the per-forward stop
     * (new in T-20) closes exactly that listener.
     */
    @Test
    fun localForwardReachesServiceAndStopClosesListener() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        SshFixture.assumeForwardingAvailable()
        val conn = connect(InMemoryTrustStore(), TestDecisionHandler(answerUnknown = true))
        val manager = SshTunnelManager()
        try {
            val config = TunnelConfig(
                id = 901, hostId = 0, kind = TunnelKind.LOCAL,
                bindAddress = "127.0.0.1", bindPort = 18_080,
                targetHost = "127.0.0.1", targetPort = SshFixture.echoPort,
                label = "it-local"
            )
            val active = manager.startLocal(conn, config)
            assertEquals(901L, active.configId)
            assertTrue(manager.getActive().any { it.configId == 901L })
            assertEquals("PING-T20", tcpEcho("127.0.0.1", 18_080, "PING-T20"))
            manager.stop(conn, 901)
            assertTrue(manager.getActive().isEmpty())
            assertTcpRefused("127.0.0.1", 18_080)
        } finally {
            manager.close(conn)
            conn.disconnect()
        }
    }

    /**
     * Item T-20: a remote forward binds on the server side
     * (reached from the emulator as the host's loopback) and
     * carries traffic back to the echo service; cancelling
     * the one forward unbinds it.
     *
     * Run #67: the remote target must be the fixture host, not
     * 127.0.0.1. For a REMOTE forward, sshj's
     * SocketForwardingConnectListener connects to the target
     * FROM THE CLIENT side, so 127.0.0.1 would mean the
     * emulator itself — where no echo service listens — and
     * the forwarded connection is refused/reset. The local
     * forward keeps 127.0.0.1 because its targets resolve
     * server-side. Only this remote test's target changes.
     */
    @Test
    fun remoteForwardBindsAndCancelUnbinds() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        SshFixture.assumeForwardingAvailable()
        val conn = connect(InMemoryTrustStore(), TestDecisionHandler(answerUnknown = true))
        val manager = SshTunnelManager()
        try {
            val config = TunnelConfig(
                id = 902, hostId = 0, kind = TunnelKind.REMOTE,
                bindAddress = "127.0.0.1", bindPort = 19_090,
                targetHost = SshFixture.host, targetPort = SshFixture.echoPort,
                label = "it-remote"
            )
            manager.startRemote(conn, config)
            assertTrue(manager.getActive().any { it.configId == 902L })
            assertEquals("PONG-T20", tcpEcho(SshFixture.host, 19_090, "PONG-T20"))
            manager.stop(conn, 902)
            assertTrue(manager.getActive().isEmpty())
            assertTcpRefused(SshFixture.host, 19_090)
        } finally {
            manager.close(conn)
            conn.disconnect()
        }
    }

    /**
     * Item T-20: a jump chain through the bastion fixture sshd
     * reaches the main fixture sshd (addressed as 127.0.0.1
     * from the bastion's side) and runs a command; a wrong target
     * password fails the chain with AuthFailed.
     *
     * Run #67: connectChain returns ChainedSshConnection, not
     * SshjConnection, so the shell() helper's asSshj cast (and
     * TerminalSession, which also requires SshjConnection)
     * cannot verify the chain. Both connection holders implement
     * SshExecChannel, so verification runs one exec command
     * over that interface instead — no production change, and
     * the reject path below is untouched.
     */
    @Test
    fun jumpChainReachesTargetAndRejectFailsCleanly() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        SshFixture.assumeForwardingAvailable()
        val store = InMemoryTrustStore()
        val bastion = HostProfile(
            name = "fixture-bastion",
            hostname = SshFixture.host,
            port = SshFixture.bastionPort,
            username = SshFixture.username,
            authType = AuthType.PASSWORD
        )
        val targetProfile = HostProfile(
            name = "fixture-via-bastion",
            hostname = "127.0.0.1",
            port = SshFixture.port,
            username = SshFixture.username,
            authType = AuthType.PASSWORD
        )
        val hopCredential = SshFixture.credential()
        val targetCredential = SshFixture.credential()
        val conn = try {
            transport(store).connectChain(
                listOf(JumpHopRequest(bastion, hopCredential)),
                SshConnectionRequest(
                    profile = targetProfile,
                    credential = targetCredential
                ),
                TestDecisionHandler(answerUnknown = true)
            )
        } finally {
            hopCredential.wipe()
            targetCredential.wipe()
        }
        try {
            assertTrue(conn.isConnected)
            val exec = conn as? SshExecChannel
                ?: throw AssertionError("Chain connection does not support exec")
            val output = exec.execOnce("echo CHAIN_OK | tr 'A-Z' 'a-z'")
            assertTrue("exec over the jump chain must return chain_ok, got: $output",
                output.contains("chain_ok"))
        } finally {
            conn.disconnect()
        }
        // Reject path: the hop works, the target refuses the
        // password — the chain must fail as AuthFailed, not hang
        // or half-connect.
        val hopCredential2 = SshFixture.credential()
        val wrong = "wrong-chain-password".toByteArray()
        try {
            transport(store).connectChain(
                listOf(JumpHopRequest(bastion, hopCredential2)),
                SshConnectionRequest(profile = targetProfile, credential = wrong),
                TestDecisionHandler(answerUnknown = true)
            )
            fail("Wrong target credentials must not connect through the chain")
        } catch (e: SshTransportException) {
            assertEquals(SshError.AuthFailed, e.error)
        } finally {
            hopCredential2.wipe()
            wrong.wipe()
        }
    }

    /**
     * Item T-20: a connection through the fixture SOCKS5 proxy
     * authenticates and runs a shell. The profile's hostname is
     * "localhost": from the emulator that is the emulator
     * itself (nothing listens there), so this can only succeed
     * if the proxy — on the fixture host — resolves and dials
     * it. That is what makes the proxy path proven, not
     * assumed.
     */
    @Test
    fun socksProxyConnectionAuthenticates() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        SshFixture.assumeForwardingAvailable()
        val profile = HostProfile(
            name = "fixture-via-socks",
            hostname = "localhost",
            port = SshFixture.port,
            username = SshFixture.username,
            authType = AuthType.PASSWORD
        )
        val credential = SshFixture.credential()
        val conn = try {
            transport(InMemoryTrustStore()).connect(
                SshConnectionRequest(
                    profile = profile,
                    credential = credential,
                    proxy = ProxyConfig(
                        kind = ProxyKind.SOCKS,
                        proxyHost = SshFixture.host,
                        proxyPort = SshFixture.socksPort
                    )
                ),
                TestDecisionHandler(answerUnknown = true)
            )
        } finally {
            credential.wipe()
        }
        try {
            assertTrue(conn.isConnected)
            shell(conn) { session ->
                session.sendText("echo SOCKS_OK | tr 'A-Z' 'a-z'")
                session.sendEnter()
                awaitOutput(session, "socks_ok")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Item T-20: one forward starts, a second is refused by
     * the server (its bind port is the echo service's — already
     * bound), and applying the app's partial-failure rule —
     * AdvancedViewModel closes the manager on any start
     * failure — leaves nothing running. The rule itself is a
     * ten-line composition in the ViewModel; this test proves
     * every mechanism it composes: independent starts, a typed
     * refusal, and a close that really frees the listener.
     */
    @Test
    fun partialTunnelFailureStopsStartedForwards() = runTest(timeout = 5.minutes) {
        SshFixture.assumeAvailable()
        SshFixture.assumeForwardingAvailable()
        val conn = connect(InMemoryTrustStore(), TestDecisionHandler(answerUnknown = true))
        val manager = SshTunnelManager()
        try {
            val good = TunnelConfig(
                id = 951, hostId = 0, kind = TunnelKind.LOCAL,
                bindAddress = "127.0.0.1", bindPort = 18_180,
                targetHost = "127.0.0.1", targetPort = SshFixture.echoPort,
                label = "it-partial-good"
            )
            manager.startLocal(conn, good)
            assertEquals("PART-T20", tcpEcho("127.0.0.1", 18_180, "PART-T20"))
            val doomed = TunnelConfig(
                id = 952, hostId = 0, kind = TunnelKind.REMOTE,
                bindAddress = "127.0.0.1", bindPort = SshFixture.echoPort,
                targetHost = "127.0.0.1", targetPort = SshFixture.echoPort,
                label = "it-partial-doomed"
            )
            try {
                manager.startRemote(conn, doomed)
                fail("A remote forward onto an already-bound port must be refused")
            } catch (_: TunnelException) {
                // Expected: the server refuses the bind.
            }
            // The AdvancedViewModel partial-failure rule.
            manager.close(conn)
            assertTrue(manager.getActive().isEmpty())
            assertTcpRefused("127.0.0.1", 18_180)
        } finally {
            manager.close(conn)
            conn.disconnect()
        }
    }

    /**
     * A minimal loopback TCP relay for the R-10 changed-key
     * test: accepts on 127.0.0.1 (emulator-local), forwards to
     * a mutable target. One connection at a time is all the
     * test needs; pumps are daemon threads and die with the
     * sockets on [close].
     */
    private class TcpRelay(initialHost: String, initialPort: Int) {
        @Volatile
        private var targetHost: String = initialHost

        @Volatile
        private var targetPort: Int = initialPort

        private val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        @Volatile
        private var closed = false
        private val live = java.util.Collections.synchronizedList(mutableListOf<Socket>())

        fun start() {
            Thread {
                while (!closed) {
                    val clientSide = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    live += clientSide
                    val upstream = try {
                        Socket(targetHost, targetPort).also { live += it }
                    } catch (_: Exception) {
                        runCatching { clientSide.close() }
                        continue
                    }
                    pump(clientSide, upstream)
                    pump(upstream, clientSide)
                }
            }.apply { isDaemon = true }.start()
        }

        private fun pump(from: Socket, to: Socket) {
            Thread {
                try {
                    val input = from.getInputStream()
                    val output = to.getOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        output.flush()
                    }
                } catch (_: Exception) {
                    // Peer went away; the other pump ends too.
                } finally {
                    runCatching { to.shutdownOutput() }
                }
            }.apply { isDaemon = true }.start()
        }

        fun retarget(host: String, port: Int) {
            targetHost = host
            targetPort = port
        }

        fun close() {
            closed = true
            runCatching { server.close() }
            synchronized(live) { live.forEach { runCatching { it.close() } } }
        }
    }
}

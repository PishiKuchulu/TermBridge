package com.pishi.termbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandRunner
import com.pishi.termbridge.domain.CommandTemplate
import com.pishi.termbridge.domain.CommandVariable
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.RemoteShell
import com.pishi.termbridge.domain.CdCommand
import com.pishi.termbridge.domain.TerminalSender
import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.terminal.TerminalSession
import com.pishi.termbridge.terminal.asText
import com.pishi.termbridge.domain.EnvApplyResult
import com.pishi.termbridge.transport.SftpError
import com.pishi.termbridge.transport.SftpException
import com.pishi.termbridge.transport.SftpManager
import com.pishi.termbridge.transport.SshConnection
import com.pishi.termbridge.transport.SshConnectionRequest
import com.pishi.termbridge.transport.SshError
import com.pishi.termbridge.transport.SshTransport
import com.pishi.termbridge.transport.SshTransportException
import com.pishi.termbridge.transport.SshjConnection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
import org.junit.Ignore
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
 * Tier 2 stays @Ignore'd with a specific reason each. The
 * original blanket blocker (docs/SSH_INTEGRATION_BLOCKER.md)
 * is updated, not deleted: the fixture now exists, but these
 * cases need fixture capabilities that do not exist yet.
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
                CommandRunner(sender).insert(
                    CommandTemplate.prepare(button, emptyMap(), RemoteShell.POSIX, true)
                )
                // The text is placed (echoed) but never submitted.
                awaitOutput(session, "touch $probe")
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

    // ---------- Tier 2: still blocked, specific reasons ----------

    @Ignore("Needs fixture-side host-key rotation on the SAME endpoint: restart the fixture sshd with new host keys between connects. The fixture script has no such hook yet.")
    @Test
    fun changedHostKeyFlow() {
        throw UnsupportedOperationException("Fixture capability missing — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Ignore("Needs a local TCP echo service on the fixture host plus TunnelManager coverage: fixture script does not start one yet.")
    @Test
    fun localForwardReachesServiceAndStopClosesListener() {
        throw UnsupportedOperationException("Fixture capability missing — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Ignore("Needs a fixture-side service reachable only from the fixture host plus remote-forward coverage: not provisioned yet.")
    @Test
    fun remoteForwardBindsAndCancelUnbinds() {
        throw UnsupportedOperationException("Fixture capability missing — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Ignore("Needs a second fixture sshd as bastion plus connectChain coverage: fixture script provisions one instance only.")
    @Test
    fun jumpChainReachesTargetAndRejectFailsCleanly() {
        throw UnsupportedOperationException("Fixture capability missing — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Ignore("Needs a fixture-side unauthenticated SOCKS proxy: not provisioned yet.")
    @Test
    fun socksProxyConnectionAuthenticates() {
        throw UnsupportedOperationException("Fixture capability missing — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Ignore("Needs a fixture that rejects one forward while accepting another: not provisioned yet.")
    @Test
    fun partialTunnelFailureStopsStartedForwards() {
        throw UnsupportedOperationException("Fixture capability missing — see SSH_INTEGRATION_BLOCKER.md")
    }
}

package com.pishi.termbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests against a controlled SSH server.
 *
 * IGNORED by design — see docs/SSH_INTEGRATION_BLOCKER.md. There is
 * currently no controlled SSH test server in this sandbox or in CI,
 * and a mock must never be called an end-to-end test. When the
 * fixture exists (OpenSSH container + test user + known host key),
 * remove @Ignore from the tests below and fill in the fixture
 * coordinates from the test environment (never hardcode a real
 * server or credential here).
 *
 * What these tests must prove when enabled:
 * 1. Real handshake + password auth succeeds against the fixture.
 * 2. Wrong credentials fail with AuthFailed, no crash, no secret
 *    in the error.
 * 3. Unknown host key triggers the trust decision and rejects on
 *    a Reject answer; a Trust answer persists the fingerprint and
 *    the second connect matches without a dialog.
 * 4. Changed host key triggers the CHANGED path.
 * 5. Disconnect releases the connection; cancel during connect
 *    does not leak a client.
 * 9. (Prompt 08) An execute-one button's command appears in the
 *    shell output; an insert-only button's text does not execute
 *    until the user presses Enter.
 * 10. (Prompt 09) An env var accepted by the fixture server appears
 *    in `printenv` output; a var the default OpenSSH config rejects
 *    is reported rejected (by name) and does not fail the session.
 * 11. (Prompt 09) A configured POSIX working directory is the
 *    shell's directory on connect (`pwd` shows it); a missing
 *    directory leaves a usable shell with a shell error, not a
 *    failed session.
 * 12. (Prompt 09) SFTP starts in the host working directory when
 *    it is a POSIX path; a download to a picked local folder
 *    lands in that folder.
 * 13. (Prompt 10) A local forward accepts a connection on
 *    127.0.0.1 and reaches the fixture's service through the
 *    tunnel; stopping the forward closes the listener.
 * 14. (Prompt 10) A remote forward bound to loopback on the
 *    fixture is reachable from the fixture and forwards to a
 *    local test service; cancelling the forward unbinds it.
 * 15. (Prompt 10) A jump chain through a fixture bastion reaches
 *    the target; rejecting any hop's host key fails the chain
 *    and leaves no hop connected.
 * 16. (Prompt 10) A connection through a fixture SOCKS proxy
 *    authenticates; a wrong proxy address fails without a crash.
 * 17. (Prompt 10) A partial tunnel failure (one forward rejected
 *    by the server) stops the already-started forwards and
 *    disconnects, leaving no half-started set running.
 */
@Ignore("Requires a controlled SSH test server — see docs/SSH_INTEGRATION_BLOCKER.md")
@RunWith(AndroidJUnit4::class)
class SshIntegrationTest {
    @Test
    fun realHandshakeAndPasswordAuthSucceeds() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun wrongCredentialsFailSafely() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun unknownHostKeyTrustFlow() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun changedHostKeyFlow() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun disconnectAndCancelReleaseResources() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun interactiveShellStreamsSeveralCommands() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun sftpUploadDownloadRoundTrip() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun sftpZeroByteUnicodeConflictAndCancellation() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun commandButtonExecuteOneAppearsInShell() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun commandButtonInsertOnlyDoesNotExecute() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun envVarAcceptedAndRejectedAreReported() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun terminalStartsInConfiguredWorkingDirectory() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun sftpStartsInWorkingDirectoryAndLocalFolderDownload() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun localForwardReachesServiceAndStopClosesListener() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun remoteForwardBindsAndCancelUnbinds() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun jumpChainReachesTargetAndRejectFailsCleanly() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun socksProxyConnectionAuthenticates() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }

    @Test
    fun partialTunnelFailureStopsStartedForwards() {
        throw UnsupportedOperationException("Fixture not available — see SSH_INTEGRATION_BLOCKER.md")
    }
}

package com.pishi.termbridge

import androidx.test.platform.app.InstrumentationRegistry
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.transport.HostKeyDecisionHandler
import com.pishi.termbridge.transport.HostKeyTrustStore
import com.pishi.termbridge.transport.PendingTrust
import org.junit.Assume

/**
 * Controlled SSH fixture coordinates for instrumented tests.
 *
 * The fixture is a throwaway OpenSSH server provisioned by
 * tests/fixture/setup-sshd-fixture.sh (CI) or by hand (local).
 * Nothing here is a real server or a real credential: the
 * password travels only as an instrumentation argument, never
 * committed, and the fixture user exists only on the test host.
 *
 * From the emulator, the host's loopback is 10.0.2.2 — that is
 * the default host. Tests call [assumeAvailable] first, so
 * without a fixture they SKIP with a clear message instead of
 * failing or, worse, passing against a mock.
 */
object SshFixture {
    private val args get() = InstrumentationRegistry.getArguments()

    val host: String get() = args.getString("fixture_host", "10.0.2.2")
    val port: Int get() = args.getString("fixture_port", "2222").toIntOrNull() ?: 2222
    val username: String get() = args.getString("fixture_user", "termbridge-test")
    val password: String get() = args.getString("fixture_password", "")

    /** True only when a password was supplied — the fixture gate. */
    val isConfigured: Boolean get() = password.isNotEmpty()

    fun assumeAvailable() {
        Assume.assumeTrue(
            "SSH fixture not configured — pass fixture_password " +
                "(see tests/fixture/setup-sshd-fixture.sh)",
            isConfigured
        )
    }

    fun profile(): HostProfile = HostProfile(
        name = "fixture",
        hostname = host,
        port = port,
        username = username,
        authType = AuthType.PASSWORD
    )

    fun credential(): ByteArray = password.toByteArray()
}

/** In-memory trust store: no Room, no Keystore, no persistence. */
class InMemoryTrustStore : HostKeyTrustStore {
    private val store = mutableMapOf<String, String>()

    override suspend fun getTrustedFingerprint(endpoint: String): String? =
        store[endpoint]

    override suspend fun trustHostKey(
        endpoint: String,
        fingerprint: String,
        keyType: String
    ) {
        store[endpoint] = fingerprint
    }

    override suspend fun clearTrustedKey(endpoint: String) {
        store.remove(endpoint)
    }

    fun stored(endpoint: String): String? = store[endpoint]
}

/** Test-controlled trust decisions; records every decision made. */
class TestDecisionHandler(
    var answerUnknown: Boolean = true,
    var answerChanged: Boolean = false
) : HostKeyDecisionHandler {
    val decisions = mutableListOf<PendingTrust>()

    override suspend fun decideUnknown(pending: PendingTrust): Boolean {
        decisions += pending
        return answerUnknown
    }

    override suspend fun decideChanged(pending: PendingTrust): Boolean {
        decisions += pending
        return answerChanged
    }
}

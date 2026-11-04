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

    val keyUsername: String get() = args.getString("fixture_key_user", "termbridge-key")
    private val keyB64: String get() = args.getString("fixture_key_b64", "")

    // Item R-10: a second client key, RSA — proves the
    // rsa-sha2-256/512 signature path against modern OpenSSH
    // (which refuses SHA-1 ssh-rsa).
    private val rsaKeyB64: String get() = args.getString("fixture_rsa_key_b64", "")

    /** True only when the RSA client key was supplied. */
    val isRsaKeyConfigured: Boolean get() = rsaKeyB64.isNotEmpty()

    fun assumeRsaKeyAvailable() {
        Assume.assumeTrue(
            "R-10 RSA client key not configured — pass fixture_rsa_key_b64 " +
                "(see tests/fixture/setup-sshd-fixture.sh)",
            isRsaKeyConfigured
        )
    }

    /** PEM bytes of the per-run RSA client key (caller wipes). */
    fun rsaKeyCredential(): ByteArray =
        android.util.Base64.decode(rsaKeyB64, android.util.Base64.DEFAULT)

    // Item T-20 fixture extras (0 = not provisioned by this run,
    // the forwarding tests then skip with a stated reason).
    val bastionPort: Int get() = args.getString("fixture_bastion_port", "0").toIntOrNull() ?: 0
    val echoPort: Int get() = args.getString("fixture_echo_port", "0").toIntOrNull() ?: 0
    val socksPort: Int get() = args.getString("fixture_socks_port", "0").toIntOrNull() ?: 0

    /** True when the T-20 forwarding fixtures were provisioned. */
    val isForwardingConfigured: Boolean
        get() = bastionPort > 0 && echoPort > 0 && socksPort > 0

    fun assumeForwardingAvailable() {
        Assume.assumeTrue(
            "T-20 forwarding fixture not configured — pass " +
                "fixture_bastion_port, fixture_echo_port and " +
                "fixture_socks_port (see tests/fixture/setup-sshd-fixture.sh)",
            isForwardingConfigured
        )
    }

    /** True only when the public-key user's private key was supplied. */
    val isKeyConfigured: Boolean get() = keyB64.isNotEmpty()

    /** PEM bytes of the per-run client key (the caller wipes them). */
    fun keyCredential(): ByteArray = android.util.Base64.decode(keyB64, android.util.Base64.DEFAULT)

    fun keyProfile(): HostProfile = HostProfile(
        name = "fixture-key",
        hostname = host,
        port = port,
        username = keyUsername,
        authType = AuthType.PRIVATE_KEY
    )

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

/**
 * Test-controlled trust decisions; records every decision made.
 * Open (class + methods) because one trust-flow test extends
 * it with a handler that throws on any decision — CI run #20
 * failed to compile the test class when this was final
 * (Kotlin classes and members are final by default).
 */
open class TestDecisionHandler(
    var answerUnknown: Boolean = true,
    var answerChanged: Boolean = false
) : HostKeyDecisionHandler {
    val decisions = mutableListOf<PendingTrust>()

    open override suspend fun decideUnknown(pending: PendingTrust): Boolean {
        decisions += pending
        return answerUnknown
    }

    open override suspend fun decideChanged(pending: PendingTrust): Boolean {
        decisions += pending
        return answerChanged
    }
}

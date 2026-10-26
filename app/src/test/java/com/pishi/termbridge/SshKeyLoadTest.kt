package com.pishi.termbridge

import java.security.KeyPairGenerator
import java.util.Base64
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Review #1 (C-01): the old code called the two-argument
 * `loadKeys(pemText, passphrase)` overload, which treats its first
 * argument as a FILE LOCATION (and NPEs on a null passphrase) — so
 * private-key auth could never work. The fix uses the three-argument
 * content overload. This JVM test proves the content overload parses
 * a generated key from PEM text with no server involved: the key is
 * generated at runtime (no test private key is stored in the repo),
 * encoded as an unencrypted PKCS#8 PEM, and loaded through a plain
 * `SSHClient` (no connection needed for parsing).
 *
 * Known sshj 0.40.0 limitation, verified here (not a TermBridge
 * bug): sshj's PKCS#8 parser rejects Ed25519 keys
 * ("PKCS8 Private Key Algorithm [1.3.101.112] not supported").
 * Ed25519 keys in OpenSSH format (the ssh-keygen default) go through
 * sshj's OpenSSH parser instead. The encrypted-key path and the
 * OpenSSH-format key path are covered by the fixture integration
 * test once the fixture enables PubkeyAuthentication (review #1
 * stage-1 item 5, still open).
 */
class SshKeyLoadTest {

    private fun pemFor(encodedDer: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(encodedDer)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
    }

    @Test
    fun `unencrypted RSA PKCS8 PEM loads from text`() {
        val pair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }.generateKeyPair()
        val pem = pemFor(pair.private.encoded)
        val client = SSHClient()
        try {
            val provider = client.loadKeys(pem, null, null)
            assertNotNull(provider)
            assertArrayEquals(pair.public.encoded, provider.public.encoded)
        } finally {
            try {
                client.disconnect()
            } catch (_: Exception) {
                // Unconnected client teardown is best-effort.
            }
        }
    }
}

package com.pishi.termbridge.security

import java.security.Provider
import java.security.Security

/**
 * Makes sshj use the Bouncy Castle that ships INSIDE the app instead
 * of Android's platform provider that happens to share the name "BC"
 * (review #2 F-01).
 *
 * sshj registers its bundled provider only when no provider named
 * "BC" exists, then looks algorithms up by that NAME. On Android the
 * platform "BC" already exists, so lookups such as
 * `KeyAgreement.getInstance("X25519", "BC")` and
 * `KeyFactory.getInstance("Ed25519", "BC")` hit the platform copy,
 * which can lack those algorithms.
 *
 * The bundled provider is APPENDED (not inserted at position 1), so
 * every other lookup in the process (AndroidKeyStore, Conscrypt TLS)
 * keeps resolving exactly as before. It is created by reflection so
 * this file has no compile-time dependency on bcprov.
 */
object SshCryptoProvider {
    private const val BC = "BC"
    private const val BC_CLASS = "org.bouncycastle.jce.provider.BouncyCastleProvider"

    /** Idempotent and thread-safe. */
    @Synchronized
    fun install() {
        val current = Security.getProvider(BC)
        if (current != null && current.javaClass.name == BC_CLASS) return
        val bundled = Class.forName(BC_CLASS).getDeclaredConstructor().newInstance() as Provider
        if (current != null) Security.removeProvider(BC)
        Security.addProvider(bundled)
    }

    /**
     * Secret-free probe used by the instrumented provider test: which
     * algorithms sshj needs resolve through "BC". Only exception
     * CLASS names are reported, never messages.
     */
    fun probe(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        fun check(label: String, block: () -> Unit) {
            result[label] = try {
                block()
                "ok"
            } catch (e: Throwable) {
                e.javaClass.simpleName
            }
        }
        check("KeyAgreement X25519") { javax.crypto.KeyAgreement.getInstance("X25519", BC) }
        check("KeyFactory Ed25519") { java.security.KeyFactory.getInstance("Ed25519", BC) }
        check("KeyFactory EC") { java.security.KeyFactory.getInstance("EC", BC) }
        check("KeyFactory RSA") { java.security.KeyFactory.getInstance("RSA", BC) }
        check("MessageDigest SHA-256") { java.security.MessageDigest.getInstance("SHA-256", BC) }
        return result
    }
}

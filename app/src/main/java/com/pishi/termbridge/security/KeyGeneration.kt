package com.pishi.termbridge.security

import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.transport.HostKeyFingerprint
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters

/** A key failure that is safe to show: never carries key
 *  material, a passphrase, or an underlying library message. */
class KeyGenerationException(message: String) : Exception(message)

/** A freshly generated key pair, ready to store as a keychain
 *  entry (item T-01). [pemText] is the private key — secret
 *  material, encrypted at rest by the repository layer and
 *  never logged. [publicKeyText] is the OpenSSH authorized_keys
 *  line; [fingerprint] is the OpenSSH-style SHA256 value. */
data class GeneratedKey(
    val pemText: String,
    val publicKeyText: String,
    val fingerprint: String
)

/** What the import flow shows BEFORE anything is saved
 *  (item F-01): the public half and fingerprint of a key the
 *  user picked, derived from a real sshj load — a key that
 *  cannot be loaded is never offered for saving. */
data class KeyDescription(
    val publicKeyText: String,
    val fingerprint: String
)

/**
 * Key generation and load-proving for the keychain
 * (items T-01 + F-01).
 *
 * - RSA keys are generated with the platform JCA provider
 *   and stored as unencrypted PKCS#8 PEM (the at-rest
 *   protection is the Keystore layer, like every other
 *   credential in this app; the PEM itself is never written
 *   anywhere else).
 * - Ed25519 keys are generated with the Bouncy Castle
 *   lightweight API — the JCA Ed25519 provider does not
 *   exist on API 26 — and stored in OpenSSH format, because
 *   sshj 0.40.0's PKCS#8 parser rejects Ed25519 (verified,
 *   see SshKeyLoadTest).
 * - [describe] proves a PEM loads through sshj (with the
 *   passphrase when encrypted) and derives the public half
 *   and fingerprint the UI shows before saving.
 */
object KeyGeneration {

    const val DEFAULT_RSA_BITS = 3072
    const val MIN_RSA_BITS = 2048
    const val MAX_RSA_BITS = 8192

    fun generateRsa(bits: Int = DEFAULT_RSA_BITS): GeneratedKey {
        require(bits in MIN_RSA_BITS..MAX_RSA_BITS) {
            "RSA key size must be between $MIN_RSA_BITS and $MAX_RSA_BITS bits."
        }
        val pair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(bits) }
            .generateKeyPair()
        val pem = pemEncode("PRIVATE KEY", pair.private.encoded)
        val description = describe(pem, null)
        return GeneratedKey(pem, description.publicKeyText, description.fingerprint)
    }

    fun generateEd25519(): GeneratedKey {
        val random = SecureRandom()
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(random))
        val pair = generator.generateKeyPair()
        val seed = (pair.private as Ed25519PrivateKeyParameters).encoded
        val publicRaw = (pair.public as Ed25519PublicKeyParameters).encoded
        try {
            val pem = openSshEd25519Pem(seed, publicRaw, random)
            val description = describe(pem, null)
            return GeneratedKey(pem, description.publicKeyText, description.fingerprint)
        } finally {
            seed.wipe()
        }
    }

    /**
     * Load [pemText] through sshj and describe its public
     * half. Takes ownership of [passphrase]: the array is
     * wiped before this function returns, on every path.
     * Throws [KeyGenerationException] with a generic message
     * when the key cannot be loaded — corrupt file, unsupported
     * type, or wrong passphrase are deliberately
     * indistinguishable here.
     */
    fun describe(pemText: String, passphrase: CharArray?): KeyDescription {
        val client = SSHClient()
        try {
            val finder = passphrase?.let { PasswordUtils.createOneOff(it) }
            val provider = client.loadKeys(pemText, null, finder)
            val public = provider.public
            val blob = Buffer.PlainBuffer().putPublicKey(public).compactData
            val line = HostKeyFingerprint.sshKeyType(public) + " " +
                Base64.getEncoder().encodeToString(blob)
            return KeyDescription(
                publicKeyText = line,
                fingerprint = HostKeyFingerprint.compute(public)
            )
        } catch (e: KeyGenerationException) {
            throw e
        } catch (e: Exception) {
            throw KeyGenerationException(
                "This key could not be loaded. It may be corrupt, " +
                    "unsupported, or the passphrase is wrong."
            )
        } finally {
            passphrase?.wipe()
            try {
                client.disconnect()
            } catch (ignored: Exception) {
                // Never connected; disconnect is best-effort.
            }
        }
    }

    private fun pemEncode(label: String, der: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(der)
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }

    /** The OpenSSH private-key container (openssh-key-v1,
     *  unencrypted) for one Ed25519 key. The private field is
     *  seed || public, per the OpenSSH format. */
    private fun openSshEd25519Pem(
        seed: ByteArray,
        publicRaw: ByteArray,
        random: SecureRandom
    ): String {
        val publicBlob = Buffer.PlainBuffer()
            .putString("ssh-ed25519")
            .putBytes(publicRaw)
            .compactData
        val check = random.nextInt()
        val privateSection = Buffer.PlainBuffer()
            .putUInt32FromInt(check)
            .putUInt32FromInt(check)
            .putString("ssh-ed25519")
            .putBytes(publicRaw)
            .putBytes(seed + publicRaw)
            .putString("")
        // Pad the private section with 1, 2, 3, ... to a
        // multiple of the 8-byte block size ("none" cipher).
        val sectionBytes = privateSection.compactData
        val padding = (8 - sectionBytes.size % 8) % 8
        val padded = Buffer.PlainBuffer()
        padded.putRawBytes(sectionBytes)
        for (i in 1..padding) {
            padded.putByte(i.toByte())
        }
        val file = Buffer.PlainBuffer()
        file.putRawBytes("openssh-key-v1".toByteArray(Charsets.US_ASCII))
        file.putByte(0.toByte())
        file.putString("none")
        file.putString("none")
        file.putString("")
        file.putUInt32(1)
        file.putBytes(publicBlob)
        file.putBytes(padded.compactData)
        return pemEncode("OPENSSH PRIVATE KEY", file.compactData)
    }
}

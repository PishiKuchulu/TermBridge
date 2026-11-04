package com.pishi.termbridge.domain

import java.util.Base64

/** PEM flavour of a private-key file, from its header line. */
enum class KeyFileFormat {
    OPENSSH,
    PKCS8,
    RSA_PEM,
    EC_PEM
}

/**
 * Outcome of classifying a credential file's bytes (item F-01).
 * This is CLASSIFICATION only: the parser proves the file's
 * shape, never that the key loads — that proof is sshj's
 * loadKeys at import/connect time, with the user's passphrase.
 */
sealed class KeyFileParse {
    /** A private key in PEM text form. [encrypted] is the
     *  file's own claim (header or OpenSSH cipher name); the
     *  import flow still asks for a passphrase only when the
     *  real load demands one. */
    data class PrivateKey(
        val pemText: String,
        val format: KeyFileFormat,
        val encrypted: Boolean
    ) : KeyFileParse()

    /** A password file: exactly one line of printable text. */
    class Password(val password: CharArray) : KeyFileParse()

    /** Not a credential file we accept; [reason] is safe to
     *  show to the user (it never contains file content). */
    data class Rejected(val reason: String) : KeyFileParse()
}

/**
 * Classifies the bytes of a user-picked credential file
 * (item F-01). Pure and total: every input maps to exactly
 * one [KeyFileParse], nothing throws, and no file content is
 * ever placed in a rejection reason.
 *
 * Rules, in order:
 * - Empty, larger than 64 KB, NUL-containing, or non-UTF-8
 *   content is rejected — keys and password files are small
 *   text files; anything else is the wrong file.
 * - A PEM private key (OpenSSH, PKCS#8, traditional RSA/EC)
 *   is recognised by its BEGIN/END pair, which must match.
 * - Anything else must be a single-line password: one
 *   optional trailing line ending, no other line breaks, no
 *   control characters (the ControlChars policy), at most
 *   1024 characters.
 */
object KeyFileParser {
    const val MAX_FILE_BYTES = 64 * 1024
    const val MAX_PASSWORD_CHARS = 1024

    private const val OPENSSH_MAGIC = "openssh-key-v1"

    fun parse(bytes: ByteArray): KeyFileParse {
        if (bytes.isEmpty()) {
            return KeyFileParse.Rejected("The file is empty.")
        }
        if (bytes.size > MAX_FILE_BYTES) {
            return KeyFileParse.Rejected("The file is larger than 64 KB.")
        }
        if (bytes.any { it == 0.toByte() }) {
            return KeyFileParse.Rejected(
                "The file looks binary — it is not a text key or password file."
            )
        }
        val text = String(bytes, Charsets.UTF_8)
        if (text.contains('\uFFFD')) {
            return KeyFileParse.Rejected("The file is not valid UTF-8 text.")
        }
        val trimmed = text.trim()
        return if (trimmed.startsWith("-----BEGIN ")) {
            parsePem(trimmed)
        } else {
            parsePassword(text)
        }
    }

    private fun parsePem(text: String): KeyFileParse {
        val lines = text.lines()
        val header = lines.first()
        val footer = lines.last()
        val label = header.removePrefix("-----BEGIN ").removeSuffix("-----")
        if (!label.endsWith("PRIVATE KEY")) {
            return KeyFileParse.Rejected(
                "This PEM file is not a private key."
            )
        }
        if (footer != "-----END $label-----") {
            return KeyFileParse.Rejected(
                "The key file's BEGIN and END lines do not match."
            )
        }
        val format = when (label) {
            "OPENSSH PRIVATE KEY" -> KeyFileFormat.OPENSSH
            "PRIVATE KEY", "ENCRYPTED PRIVATE KEY" -> KeyFileFormat.PKCS8
            "RSA PRIVATE KEY" -> KeyFileFormat.RSA_PEM
            "EC PRIVATE KEY" -> KeyFileFormat.EC_PEM
            else -> return KeyFileParse.Rejected(
                "Unsupported private key type: $label."
            )
        }
        var encrypted = label == "ENCRYPTED PRIVATE KEY" ||
            lines.any { it.startsWith("Proc-Type:") && it.contains("ENCRYPTED") }
        if (format == KeyFileFormat.OPENSSH) {
            val cipher = opensshCipherName(lines)
                ?: return KeyFileParse.Rejected(
                    "The OpenSSH key file is malformed."
                )
            encrypted = cipher != "none"
        }
        return KeyFileParse.PrivateKey(
            pemText = text + "\n",
            format = format,
            encrypted = encrypted
        )
    }

    /** The cipher name is the first length-prefixed string
     *  after the "openssh-key-v1\0" magic in the decoded body.
     *  Returns null when the structure is not really an
     *  OpenSSH key, whatever the header claims. */
    private fun opensshCipherName(lines: List<String>): String? {
        val body = lines.drop(1).dropLast(1)
            .filter { it.isNotBlank() && !it.contains(':') }
            .joinToString("")
        val raw = try {
            Base64.getMimeDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val magic = OPENSSH_MAGIC.toByteArray(Charsets.US_ASCII)
        if (raw.size < magic.size + 1 + 4) return null
        for (i in magic.indices) {
            if (raw[i] != magic[i]) return null
        }
        if (raw[magic.size] != 0.toByte()) return null
        var offset = magic.size + 1
        if (offset + 4 > raw.size) return null
        val length = ((raw[offset].toInt() and 0xFF) shl 24) or
            ((raw[offset + 1].toInt() and 0xFF) shl 16) or
            ((raw[offset + 2].toInt() and 0xFF) shl 8) or
            (raw[offset + 3].toInt() and 0xFF)
        offset += 4
        if (length < 1 || length > 64 || offset + length > raw.size) return null
        return String(raw, offset, length, Charsets.US_ASCII)
    }

    private fun parsePassword(text: String): KeyFileParse {
        var body = text
        // Exactly one trailing line ending is the file
        // convention, not part of the password.
        body = when {
            body.endsWith("\r\n") -> body.dropLast(2)
            body.endsWith("\n") -> body.dropLast(1)
            else -> body
        }
        if (body.isEmpty()) {
            return KeyFileParse.Rejected("The file is empty.")
        }
        if (body.any { it == '\n' || it == '\r' }) {
            return KeyFileParse.Rejected(
                "The file has more than one line — it is neither a private key nor a single-password file."
            )
        }
        if (body.any { ControlChars.isControl(it) }) {
            return KeyFileParse.Rejected(
                "The password contains control characters."
            )
        }
        if (body.length > MAX_PASSWORD_CHARS) {
            return KeyFileParse.Rejected(
                "The password is longer than $MAX_PASSWORD_CHARS characters."
            )
        }
        return KeyFileParse.Password(body.toCharArray())
    }
}

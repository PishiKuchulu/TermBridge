package com.pishi.termbridge.domain

/** Authentication choice for a host. No credential value lives here:
 *  the secret itself is stored only as a Keystore-encrypted blob in
 *  the data layer (see security/SecretCrypto.kt). */
enum class AuthType {
    PASSWORD,
    PRIVATE_KEY
}

/** A host profile without any secret value. */
data class HostProfile(
    val id: Long = 0L,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.PASSWORD,
    val notes: String = "",
    val tags: String = ""
) {
    /** Endpoint identity used by the host-key trust store. */
    val endpoint: String get() = "$hostname:$port"
}

/** Credential supplied at save time. Values are wipeable buffers:
 *  callers must wipe them after the repository has consumed them,
 *  and the repository never logs them. */
sealed class CredentialInput {
    data class Password(val password: CharArray) : CredentialInput()
    data class PrivateKey(val keyPem: String, val passphrase: CharArray?) : CredentialInput()
}

/** One validation problem, named by field so the UI can show it
 *  next to the right field instead of a generic error. */
data class ValidationIssue(val field: String, val message: String)

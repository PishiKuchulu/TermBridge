package com.pishi.termbridge.domain

/** Authentication choice for a host. No credential value lives here:
 *  the secret itself is stored only as a Keystore-encrypted blob in
 *  the data layer (see security/SecretCrypto.kt). */
enum class AuthType {
    PASSWORD,
    PRIVATE_KEY
}

/** A host profile without any secret value. [keychainEntryId]
 *  references a shared keychain entry (item T-01) instead of an
 *  inline credential; it is a pointer, not secret material, so
 *  it is safe to copy on duplicate — the entry itself stays the
 *  single stored copy. */
data class HostProfile(
    val id: Long = 0L,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.PASSWORD,
    val notes: String = "",
    val tags: String = "",
    val keychainEntryId: Long? = null,
    /** Item T-22: user-curated organization — a favorite pin
     *  and a free-text group/folder name. Both are plain data,
     *  safe to copy on duplicate and to export in a backup. */
    val favorite: Boolean = false,
    val groupName: String = "",
    /** Item T-22: epoch ms of the last successful connection,
     *  or null when never connected. Device-local history: it
     *  is NOT carried in backups and NOT inherited by a
     *  duplicate (the copy has never been connected to). */
    val lastConnectedAt: Long? = null
) {
    /** Endpoint identity used by the host-key trust store. */
    val endpoint: String get() = "$hostname:$port"
}

/** Credential supplied at save time. The repository never logs
 *  them. Password CharArrays are wiped by the repository after
 *  encryption (see HostRepository); the private-key PEM is a
 *  String, which CANNOT be wiped — the form clears its state
 *  and the repository drops the reference after encrypting,
 *  but the characters may linger in memory until garbage
 *  collection. That asymmetry is inherent to String, not a
 *  bug to paper over with a "wipeable buffers" claim. */
sealed class CredentialInput {
    data class Password(val password: CharArray) : CredentialInput()
    data class PrivateKey(val keyPem: String, val passphrase: CharArray?) : CredentialInput()
}

/** One validation problem, named by field so the UI can show it
 *  next to the right field instead of a generic error. */
data class ValidationIssue(val field: String, val message: String)

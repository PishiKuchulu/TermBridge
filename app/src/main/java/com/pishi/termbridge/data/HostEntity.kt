package com.pishi.termbridge.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for a host profile. The secret is NEVER a plaintext
 * column: [credentialBlob] is the opaque Keystore-encrypted blob
 * from SecretCrypto, or null when no credential is stored (e.g. a
 * freshly duplicated host, which must not inherit the original's
 * secret — see HostRepository.duplicate).
 *
 * [authType] is stored as the AuthType enum name ("PASSWORD" /
 * "PRIVATE_KEY"); renaming an enum value later requires a migration.
 */
@Entity(tableName = "hosts")
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val authType: String,
    val notes: String,
    val tags: String,
    val credentialBlob: ByteArray?,
    val createdAt: Long,
    val updatedAt: Long,
    // Item T-01 (database v6): reference to a shared keychain
    // entry, or null when the host uses its inline
    // credentialBlob. No foreign-key constraint — the same
    // plain-column pattern as the v5 shellConfirmed addition;
    // referential integrity is a repository rule (deleting a
    // referenced entry is refused or detaches explicitly).
    val keychainEntryId: Long? = null,
    // Item T-22 (database v8): list organization. favorite and
    // groupName are user-curated; lastConnectedAt is stamped by
    // the session layer on a successful connect and is never
    // edited by hand.
    val favorite: Boolean = false,
    val groupName: String = "",
    val lastConnectedAt: Long? = null
)

/**
 * Trust record for one endpoint (hostname:port). This is trust data,
 * not a secret, but it is security-critical: a changed fingerprint
 * must never be overwritten silently. Trust records are
 * endpoint-scoped and intentionally survive host-profile deletion —
 * deleting a profile deletes its secret, not the user's trust
 * decision about that endpoint. Clearing trust is an explicit,
 * separate action (used by the prompt-05 trust flow).
 */
@Entity(tableName = "trusted_host_keys", primaryKeys = ["endpoint"])
data class TrustedHostKeyEntity(
    val endpoint: String,
    val fingerprint: String,
    val keyType: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long
)

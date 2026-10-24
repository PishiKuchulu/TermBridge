package com.pishi.termbridge.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Prompt 09 persistence. Two tables, both cascade on host delete
 * (a deleted host's settings and env vars must not linger — the
 * repository delete is the first net, the cascade is the second).
 *
 * host_settings holds NON-SECRET settings only: a working
 * directory path, a SAF URI, and a shell name. No env value is
 * ever a column here. Prompt 18 adds shellConfirmed (see
 * HostEnvironmentSettings): false for every row created before
 * the flag existed (MIGRATION_4_5 DEFAULT 0).
 *
 * host_env_vars holds one row per variable. [valueBlob] is the
 * opaque Keystore-encrypted value (same SecretCrypto as host
 * credentials) — never plaintext, for exactly the reason the pack
 * states: env values may contain secrets. The name is plaintext
 * (names are shown in the UI and in the applied/rejected lists).
 * The UNIQUE index on (hostId, name) enforces one value per name
 * per host at the database level, not just in the UI.
 */
@Entity(
    tableName = "host_settings",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class HostSettingsEntity(
    @PrimaryKey val hostId: Long,
    val remoteWorkingDirectory: String,
    val localDirectoryUri: String?,
    val remoteShell: String,
    val shellConfirmed: Boolean = false
)

@Entity(
    tableName = "host_env_vars",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("hostId"),
        Index(value = ["hostId", "name"], unique = true)
    ]
)
data class HostEnvVarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val hostId: Long,
    val name: String,
    val valueBlob: ByteArray,
    val sortOrder: Int
)

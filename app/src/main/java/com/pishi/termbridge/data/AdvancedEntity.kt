package com.pishi.termbridge.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Prompt 10 persistence. All three tables are NON-SECRET
 * configuration (ports, hosts, bind addresses, chain order) — no
 * credential, passphrase, or key material column exists here.
 * That is why host duplication copies these rows (see
 * HostRepository): they describe the route, not the secret.
 * Every foreign key cascades on host delete, so a deleted host's
 * advanced configuration cannot linger; the repository delete is
 * the first net, the cascade is the second.
 */
@Entity(
    tableName = "tunnels",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("hostId")]
)
data class TunnelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val hostId: Long,
    /** TunnelKind name ("LOCAL" / "REMOTE"). */
    val kind: String,
    val bindAddress: String,
    val bindPort: Int,
    val targetHost: String,
    val targetPort: Int,
    val allowNonLoopback: Boolean,
    val enabled: Boolean,
    val label: String,
    val sortOrder: Int
)

@Entity(
    tableName = "jump_hops",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetHostId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["jumpHostId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("targetHostId"),
        // Audit #7 AUD-006: jumpHostId is a foreign key too;
        // without its own leading index, lookups by jump host
        // (delete/replace cleanup) scan the table — the exact
        // warning Room's compiler raised.
        Index("jumpHostId"),
        Index(value = ["targetHostId", "jumpHostId"], unique = true)
    ]
)
data class JumpHopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val targetHostId: Long,
    val jumpHostId: Long,
    /** Connection order: 0 = first jump contacted. */
    val sortOrder: Int
)

@Entity(
    tableName = "proxies",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ProxyEntity(
    @PrimaryKey val hostId: Long,
    /** ProxyKind name ("NONE" / "SOCKS"). NONE rows are never
     *  written — clearing a proxy deletes the row instead. */
    val kind: String,
    val proxyHost: String,
    val proxyPort: Int
)

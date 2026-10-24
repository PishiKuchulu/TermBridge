package com.pishi.termbridge.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Prompt 08 persistence. Buttons are per-host (hostId). Steps and
 * variables live in their own tables so ordering and defaults are
 * real columns, not serialised blobs — and so a button can be
 * listed without parsing anything.
 *
 * Foreign keys cascade: deleting a host deletes its buttons, and
 * deleting a button deletes its steps and variables. The
 * repositories also delete explicitly, so the cascade is a second
 * net, not the only mechanism.
 *
 * No secret column exists here: templates and defaults are scanned
 * on save (SecretScanner) and a match blocks the save. A user could
 * still type a secret at execution time (a parameter value) —
 * that value is never persisted; it lives only in the dialog and
 * the rendered submission.
 */
@Entity(
    tableName = "command_buttons",
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
data class CommandButtonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val hostId: Long,
    val label: String,
    val category: String,
    val sortOrder: Int,
    val mode: String,
    val template: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "command_steps",
    foreignKeys = [
        ForeignKey(
            entity = CommandButtonEntity::class,
            parentColumns = ["id"],
            childColumns = ["buttonId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("buttonId")]
)
data class CommandStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val buttonId: Long,
    val sortOrder: Int,
    val template: String
)

@Entity(
    tableName = "command_variables",
    foreignKeys = [
        ForeignKey(
            entity = CommandButtonEntity::class,
            parentColumns = ["id"],
            childColumns = ["buttonId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("buttonId")]
)
data class CommandVariableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val buttonId: Long,
    val name: String,
    val defaultValue: String,
    val required: Boolean
)

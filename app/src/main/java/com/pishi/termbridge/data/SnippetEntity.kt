package com.pishi.termbridge.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Global snippets (item T-02): command buttons that belong to
 * no single host and are offered in every session. They get
 * their own tables rather than a sentinel hostId inside
 * command_buttons, because that table's host foreign key is
 * load-bearing (host deletion cascades) and the migration
 * audit forbids destructive rebuilds — new CREATE TABLEs keep
 * both guarantees. Shape mirrors the button tables column for
 * column so the domain model, validator and runner are shared:
 * a snippet IS a [com.pishi.termbridge.domain.CommandButton]
 * whose hostId is [GLOBAL_HOST_ID].
 */
const val GLOBAL_HOST_ID = -2L

@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val label: String,
    val category: String,
    val sortOrder: Int,
    val mode: String,
    val template: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "snippet_steps",
    foreignKeys = [
        ForeignKey(
            entity = SnippetEntity::class,
            parentColumns = ["id"],
            childColumns = ["snippetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("snippetId")]
)
data class SnippetStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val snippetId: Long,
    val sortOrder: Int,
    val template: String
)

@Entity(
    tableName = "snippet_variables",
    foreignKeys = [
        ForeignKey(
            entity = SnippetEntity::class,
            parentColumns = ["id"],
            childColumns = ["snippetId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("snippetId")]
)
data class SnippetVariableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val snippetId: Long,
    val name: String,
    val defaultValue: String,
    val required: Boolean
)

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets ORDER BY sortOrder ASC, id ASC")
    fun getAll(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllOnce(): List<SnippetEntity>

    @Query("SELECT * FROM snippets WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SnippetEntity?

    @Query("SELECT * FROM snippet_steps WHERE snippetId = :snippetId ORDER BY sortOrder ASC, id ASC")
    suspend fun getSteps(snippetId: Long): List<SnippetStepEntity>

    @Query("SELECT * FROM snippet_variables WHERE snippetId = :snippetId ORDER BY id ASC")
    suspend fun getVariables(snippetId: Long): List<SnippetVariableEntity>

    @Insert
    suspend fun insertSnippet(snippet: SnippetEntity): Long

    @Insert
    suspend fun insertSteps(steps: List<SnippetStepEntity>)

    @Insert
    suspend fun insertVariables(variables: List<SnippetVariableEntity>)

    @Update
    suspend fun updateSnippet(snippet: SnippetEntity)

    @Query("DELETE FROM snippet_steps WHERE snippetId = :snippetId")
    suspend fun deleteSteps(snippetId: Long)

    @Query("DELETE FROM snippet_variables WHERE snippetId = :snippetId")
    suspend fun deleteVariables(snippetId: Long)

    @Query("DELETE FROM snippets WHERE id = :id")
    suspend fun deleteSnippet(id: Long)

    /** Same atomic child replacement as the button DAO. */
    @Transaction
    suspend fun replaceChildren(
        snippetId: Long,
        steps: List<SnippetStepEntity>,
        variables: List<SnippetVariableEntity>
    ) {
        deleteSteps(snippetId)
        deleteVariables(snippetId)
        insertSteps(steps)
        insertVariables(variables)
    }

    /** Same atomic sort-order swap as the button DAO. */
    @Transaction
    suspend fun swapSortOrders(
        first: SnippetEntity,
        second: SnippetEntity
    ) {
        updateSnippet(first)
        updateSnippet(second)
    }
}

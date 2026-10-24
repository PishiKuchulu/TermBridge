package com.pishi.termbridge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CommandButtonDao {
    @Query("SELECT * FROM command_buttons WHERE hostId = :hostId ORDER BY sortOrder ASC, id ASC")
    fun getForHost(hostId: Long): Flow<List<CommandButtonEntity>>

    @Query("SELECT * FROM command_buttons WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CommandButtonEntity?

    @Query("SELECT * FROM command_buttons WHERE hostId = :hostId ORDER BY sortOrder ASC, id ASC")
    suspend fun getForHostOnce(hostId: Long): List<CommandButtonEntity>

    @Query("SELECT * FROM command_steps WHERE buttonId = :buttonId ORDER BY sortOrder ASC, id ASC")
    suspend fun getSteps(buttonId: Long): List<CommandStepEntity>

    @Query("SELECT * FROM command_variables WHERE buttonId = :buttonId ORDER BY id ASC")
    suspend fun getVariables(buttonId: Long): List<CommandVariableEntity>

    @Insert
    suspend fun insertButton(button: CommandButtonEntity): Long

    @Insert
    suspend fun insertSteps(steps: List<CommandStepEntity>)

    @Insert
    suspend fun insertVariables(variables: List<CommandVariableEntity>)

    @Update
    suspend fun updateButton(button: CommandButtonEntity)

    @Query("DELETE FROM command_steps WHERE buttonId = :buttonId")
    suspend fun deleteSteps(buttonId: Long)

    @Query("DELETE FROM command_variables WHERE buttonId = :buttonId")
    suspend fun deleteVariables(buttonId: Long)

    @Query("DELETE FROM command_buttons WHERE id = :id")
    suspend fun deleteButton(id: Long)

    @Query("DELETE FROM command_buttons WHERE hostId = :hostId")
    suspend fun deleteForHost(hostId: Long)

    /**
     * Prompt 15: replace a button's steps and variables atomically.
     * Before prompt 15 the repository deleted then inserted in
     * separate calls, so a crash mid-save could leave a button
     * with partial children. Inside this transaction Room rolls
     * the whole replacement back on failure, so a saved button
     * always has exactly the children the caller supplied. The
     * repository must call this — never the four calls directly —
     * for saves (see CommandRepositoryTransactionTest).
     */
    @Transaction
    suspend fun replaceChildren(
        buttonId: Long,
        steps: List<CommandStepEntity>,
        variables: List<CommandVariableEntity>
    ) {
        deleteSteps(buttonId)
        deleteVariables(buttonId)
        insertSteps(steps)
        insertVariables(variables)
    }

    /**
     * Prompt 15: swap two buttons' sort orders atomically. Same
     * class of partial-write risk as replaceChildren: two separate
     * updates could leave two buttons sharing one sort order if
     * the process died between them.
     */
    @Transaction
    suspend fun swapSortOrders(
        first: CommandButtonEntity,
        second: CommandButtonEntity
    ) {
        updateButton(first)
        updateButton(second)
    }
}

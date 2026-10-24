package com.pishi.termbridge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HostSettingsDao {
    @Query("SELECT * FROM host_settings WHERE hostId = :hostId LIMIT 1")
    fun getForHost(hostId: Long): Flow<HostSettingsEntity?>

    @Query("SELECT * FROM host_settings WHERE hostId = :hostId LIMIT 1")
    suspend fun getForHostOnce(hostId: Long): HostSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: HostSettingsEntity)

    @Query("DELETE FROM host_settings WHERE hostId = :hostId")
    suspend fun deleteForHost(hostId: Long)
}

@Dao
interface HostEnvVarDao {
    @Query("SELECT * FROM host_env_vars WHERE hostId = :hostId ORDER BY sortOrder ASC, name ASC")
    fun getForHost(hostId: Long): Flow<List<HostEnvVarEntity>>

    @Query("SELECT * FROM host_env_vars WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): HostEnvVarEntity?

    @Query("SELECT * FROM host_env_vars WHERE hostId = :hostId AND name = :name LIMIT 1")
    suspend fun getByName(hostId: Long, name: String): HostEnvVarEntity?

    @Query("SELECT * FROM host_env_vars WHERE hostId = :hostId ORDER BY sortOrder ASC, name ASC")
    suspend fun getForHostOnce(hostId: Long): List<HostEnvVarEntity>

    @Insert
    suspend fun insert(variable: HostEnvVarEntity): Long

    @Update
    suspend fun update(variable: HostEnvVarEntity)

    @Query("DELETE FROM host_env_vars WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM host_env_vars WHERE hostId = :hostId")
    suspend fun deleteForHost(hostId: Long)
}

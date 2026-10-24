package com.pishi.termbridge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY name COLLATE NOCASE ASC")
    fun getAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): HostEntity?

    @Insert
    suspend fun insert(host: HostEntity): Long

    @Update
    suspend fun update(host: HostEntity)

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface TrustedHostKeyDao {
    @Query("SELECT * FROM trusted_host_keys WHERE endpoint = :endpoint LIMIT 1")
    suspend fun getByEndpoint(endpoint: String): TrustedHostKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: TrustedHostKeyEntity)

    @Query("DELETE FROM trusted_host_keys WHERE endpoint = :endpoint")
    suspend fun deleteByEndpoint(endpoint: String)
}

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

    /** Item T-22: stamp the last successful connection without
     *  touching any other column (in particular not the
     *  credential blob). */
    @Query("UPDATE hosts SET lastConnectedAt = :atMs WHERE id = :id")
    suspend fun touchLastConnected(id: Long, atMs: Long)

    /** Item T-22: favorite toggle from the list — a one-column
     *  update, never a full save (a full save would re-run the
     *  credential rules for a pure display change). */
    @Query("UPDATE hosts SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("SELECT COUNT(*) FROM hosts WHERE keychainEntryId = :entryId")
    suspend fun countByKeychainEntryId(entryId: Long): Int

    @Query("UPDATE hosts SET keychainEntryId = NULL WHERE keychainEntryId = :entryId")
    suspend fun clearKeychainEntryId(entryId: Long)
}

@Dao
interface TrustedHostKeyDao {
    @Query("SELECT * FROM trusted_host_keys WHERE endpoint = :endpoint LIMIT 1")
    suspend fun getByEndpoint(endpoint: String): TrustedHostKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: TrustedHostKeyEntity)

    @Query("DELETE FROM trusted_host_keys WHERE endpoint = :endpoint")
    suspend fun deleteByEndpoint(endpoint: String)

    @Query("SELECT * FROM trusted_host_keys ORDER BY endpoint COLLATE NOCASE")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<TrustedHostKeyEntity>>
}

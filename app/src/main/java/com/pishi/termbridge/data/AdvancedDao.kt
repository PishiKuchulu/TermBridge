package com.pishi.termbridge.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TunnelDao {
    @Query("SELECT * FROM tunnels WHERE hostId = :hostId ORDER BY sortOrder ASC, id ASC")
    fun getForHost(hostId: Long): Flow<List<TunnelEntity>>

    @Query("SELECT * FROM tunnels WHERE hostId = :hostId ORDER BY sortOrder ASC, id ASC")
    suspend fun getForHostOnce(hostId: Long): List<TunnelEntity>

    @Query("SELECT * FROM tunnels WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TunnelEntity?

    @Insert
    suspend fun insert(tunnel: TunnelEntity): Long

    @Update
    suspend fun update(tunnel: TunnelEntity)

    @Query("DELETE FROM tunnels WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tunnels WHERE hostId = :hostId")
    suspend fun deleteForHost(hostId: Long)
}

@Dao
interface JumpHopDao {
    @Query(
        "SELECT * FROM jump_hops WHERE targetHostId = :targetHostId " +
            "ORDER BY sortOrder ASC, id ASC"
    )
    fun getForTarget(targetHostId: Long): Flow<List<JumpHopEntity>>

    @Query(
        "SELECT * FROM jump_hops WHERE targetHostId = :targetHostId " +
            "ORDER BY sortOrder ASC, id ASC"
    )
    suspend fun getForTargetOnce(targetHostId: Long): List<JumpHopEntity>

    @Insert
    suspend fun insert(hop: JumpHopEntity)

    @Query("DELETE FROM jump_hops WHERE targetHostId = :targetHostId")
    suspend fun deleteForTarget(targetHostId: Long)

    /** Replace a chain atomically: old hops never coexist with new
     *  ones, so a failed save cannot leave a half-old chain. */
    @Transaction
    suspend fun replaceChain(targetHostId: Long, jumpHostIds: List<Long>) {
        deleteForTarget(targetHostId)
        jumpHostIds.forEachIndexed { index, jumpHostId ->
            insert(
                JumpHopEntity(
                    targetHostId = targetHostId,
                    jumpHostId = jumpHostId,
                    sortOrder = index
                )
            )
        }
    }
}

@Dao
interface ProxyDao {
    @Query("SELECT * FROM proxies WHERE hostId = :hostId LIMIT 1")
    suspend fun getForHostOnce(hostId: Long): ProxyEntity?

    @Query("SELECT * FROM proxies WHERE hostId = :hostId LIMIT 1")
    fun getForHost(hostId: Long): Flow<ProxyEntity?>

    @Insert
    suspend fun insert(proxy: ProxyEntity)

    @Update
    suspend fun update(proxy: ProxyEntity)

    @Query("DELETE FROM proxies WHERE hostId = :hostId")
    suspend fun deleteForHost(hostId: Long)
}

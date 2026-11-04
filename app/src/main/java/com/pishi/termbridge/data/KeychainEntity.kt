package com.pishi.termbridge.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room row for one keychain entry (item T-01, database v6).
 * The secret is NEVER a plaintext column: [materialBlob] is the
 * opaque Keystore-encrypted blob from SecretCrypto holding the
 * password bytes or the private-key PEM — the same discipline
 * as HostEntity.credentialBlob. A private key's passphrase is
 * NOT stored here either; it is requested at connection time,
 * exactly as for inline host credentials.
 *
 * [kind] is the AuthType enum name ("PASSWORD" / "PRIVATE_KEY").
 * [fingerprint] and [publicKeyText] are public-key data, safe
 * to store and display in plaintext.
 */
@Entity(
    tableName = "keychain_entries",
    indices = [Index(value = ["label"], unique = true)]
)
data class KeychainEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val label: String,
    val kind: String,
    val materialBlob: ByteArray,
    val publicKeyText: String?,
    val fingerprint: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Dao
interface KeychainDao {
    @Query("SELECT * FROM keychain_entries ORDER BY label COLLATE NOCASE ASC")
    fun getAll(): Flow<List<KeychainEntryEntity>>

    @Query("SELECT * FROM keychain_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): KeychainEntryEntity?

    @Query("SELECT * FROM keychain_entries WHERE label = :label LIMIT 1")
    suspend fun getByLabel(label: String): KeychainEntryEntity?

    @Insert
    suspend fun insert(entry: KeychainEntryEntity): Long

    @Update
    suspend fun update(entry: KeychainEntryEntity)

    @Query("DELETE FROM keychain_entries WHERE id = :id")
    suspend fun deleteById(id: Long)
}

package com.pishi.termbridge

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.pishi.termbridge.data.AdvancedRepository
import com.pishi.termbridge.data.BackupService
import com.pishi.termbridge.data.CommandRepository
import com.pishi.termbridge.data.HostEnvironmentRepository
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.data.MIGRATION_1_2
import com.pishi.termbridge.data.MIGRATION_2_3
import com.pishi.termbridge.data.MIGRATION_3_4
import com.pishi.termbridge.data.MIGRATION_4_5
import com.pishi.termbridge.data.TermBridgeDatabase
import com.pishi.termbridge.data.TransactionRunner
import com.pishi.termbridge.security.AndroidKeystoreSecretCrypto

/**
 * Manual dependency container (prompt 02 decision: no DI framework
 * yet). The database and repository are created lazily on first
 * access, not at app start, so a user who never opens Hosts pays
 * no database cost. Nothing here holds a credential: the repository
 * encrypts on save and decrypts only on a connection attempt.
 */
class TermBridgeApplication : Application() {
    val database: TermBridgeDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            TermBridgeDatabase::class.java,
            "termbridge.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
    }

    val advancedRepository: AdvancedRepository by lazy {
        AdvancedRepository(
            tunnelDao = database.tunnelDao(),
            jumpHopDao = database.jumpHopDao(),
            proxyDao = database.proxyDao(),
            hostDao = database.hostDao()
        )
    }

    /**
     * Security fix 16 (M3, L4): the single-database transaction
     * runner shared by host deletion and import replace. Every
     * repository in this app uses this one database, so a
     * withTransaction block here covers all of their DAOs.
     */
    private val transactionRunner: TransactionRunner by lazy {
        // Regular interface, not a SAM lambda: a fun interface
        // cannot declare a generic method, so the lambda form
        // does not compile (CI run #4, compileDebugKotlin).
        object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T {
                return database.withTransaction(block)
            }
        }
    }

    val hostRepository: HostRepository by lazy {
        HostRepository(
            hostDao = database.hostDao(),
            trustedHostKeyDao = database.trustedHostKeyDao(),
            secretCrypto = AndroidKeystoreSecretCrypto(),
            commandButtonDao = database.commandButtonDao(),
            hostSettingsDao = database.hostSettingsDao(),
            hostEnvVarDao = database.hostEnvVarDao(),
            advancedRepository = advancedRepository,
            transactionRunner = transactionRunner
        )
    }

    val commandRepository: CommandRepository by lazy {
        CommandRepository(dao = database.commandButtonDao())
    }

    val environmentRepository: HostEnvironmentRepository by lazy {
        HostEnvironmentRepository(
            settingsDao = database.hostSettingsDao(),
            envVarDao = database.hostEnvVarDao(),
            secretCrypto = AndroidKeystoreSecretCrypto()
        )
    }

    val backupService: BackupService by lazy {
        BackupService(
            hostRepository = hostRepository,
            commandRepository = commandRepository,
            environmentRepository = environmentRepository,
            advancedRepository = advancedRepository,
            transactionRunner = transactionRunner
        )
    }
}

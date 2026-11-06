package com.pishi.termbridge

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.pishi.termbridge.data.AdvancedRepository
import com.pishi.termbridge.data.BackupService
import com.pishi.termbridge.data.CommandRepository
import com.pishi.termbridge.data.HostEnvironmentRepository
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.data.KeychainRepository
import com.pishi.termbridge.data.MIGRATION_1_2
import com.pishi.termbridge.data.MIGRATION_2_3
import com.pishi.termbridge.data.MIGRATION_3_4
import com.pishi.termbridge.data.MIGRATION_4_5
import com.pishi.termbridge.data.MIGRATION_5_6
import com.pishi.termbridge.data.MIGRATION_6_7
import com.pishi.termbridge.data.MIGRATION_7_8
import com.pishi.termbridge.data.MIGRATION_8_9
import com.pishi.termbridge.data.MIGRATION_9_10
import com.pishi.termbridge.data.TermBridgeDatabase
import com.pishi.termbridge.data.TransactionRunner
import com.pishi.termbridge.security.AndroidKeystoreSecretCrypto
import com.pishi.termbridge.security.SshCryptoProvider
import com.pishi.termbridge.settings.AppLockStore
import com.pishi.termbridge.settings.AppSettingsStore
import com.pishi.termbridge.settings.ConnectionLogStore

/**
 * Manual dependency container (prompt 02 decision: no DI framework
 * yet). The database and repository are created lazily on first
 * access, not at app start, so a user who never opens Hosts pays
 * no database cost. Nothing here holds a credential: the repository
 * encrypts on save and decrypts only on a connection attempt.
 */
class TermBridgeApplication : Application() {
    /**
     * Review #2 F-01: sshj resolves its crypto by the provider NAME
     * "BC". Android ships its own, older "BC" provider, so without
     * this the bundled Bouncy Castle is never reachable and key
     * exchange / Ed25519 host-key decoding can fail before
     * authentication. Install the bundled provider before the first
     * SSHClient is ever constructed.
     */
    override fun onCreate() {
        super.onCreate()
        SshCryptoProvider.install()
    }

    val appSettings: AppSettingsStore by lazy { AppSettingsStore(this) }
    val customThemeRepository: com.pishi.termbridge.settings.CustomThemeRepository by lazy {
        com.pishi.termbridge.settings.CustomThemeRepository(
            com.pishi.termbridge.settings.CustomThemeStore(this)
        )
    }
    val commandHistoryStore: com.pishi.termbridge.settings.CommandHistoryStore by lazy {
        com.pishi.termbridge.settings.CommandHistoryStore(this)
    }
    val transcriptStore: com.pishi.termbridge.settings.TranscriptStore by lazy {
        com.pishi.termbridge.settings.TranscriptStore(this)
    }
    val desktopProfileRepository: com.pishi.termbridge.settings.DesktopProfileRepository by lazy {
        com.pishi.termbridge.settings.DesktopProfileRepository(
            com.pishi.termbridge.settings.DesktopProfileStore(this)
        )
    }
    val connectionLog: ConnectionLogStore by lazy { ConnectionLogStore(this) }
    val appLock: AppLockStore by lazy { AppLockStore(this) }

    /** One shared instance: the Keystore key-creation lock is
     *  process-wide, but a single instance keeps the intent obvious
     *  (review #2 F-04). */
    private val secretCrypto: AndroidKeystoreSecretCrypto by lazy { AndroidKeystoreSecretCrypto() }

    val database: TermBridgeDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            TermBridgeDatabase::class.java,
            "termbridge.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10).build()
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
            secretCrypto = secretCrypto,
            commandButtonDao = database.commandButtonDao(),
            hostSettingsDao = database.hostSettingsDao(),
            hostEnvVarDao = database.hostEnvVarDao(),
            advancedRepository = advancedRepository,
            transactionRunner = transactionRunner,
            keychainDao = database.keychainDao(),
            // Audit #3 (A-05): deleting a host purges its
            // session transcripts.
            transcriptStorage = transcriptStore
        )
    }

    val keychainRepository: KeychainRepository by lazy {
        KeychainRepository(
            keychainDao = database.keychainDao(),
            hostDao = database.hostDao(),
            secretCrypto = secretCrypto
        )
    }

    val snippetRepository: com.pishi.termbridge.data.SnippetRepository by lazy {
        com.pishi.termbridge.data.SnippetRepository(dao = database.snippetDao())
    }

    val commandRepository: CommandRepository by lazy {
        CommandRepository(dao = database.commandButtonDao())
    }

    val environmentRepository: HostEnvironmentRepository by lazy {
        HostEnvironmentRepository(
            settingsDao = database.hostSettingsDao(),
            envVarDao = database.hostEnvVarDao(),
            secretCrypto = secretCrypto
        )
    }

    val backupService: BackupService by lazy {
        BackupService(
            hostRepository = hostRepository,
            commandRepository = commandRepository,
            environmentRepository = environmentRepository,
            advancedRepository = advancedRepository,
            transactionRunner = transactionRunner,
            keychainRepository = keychainRepository,
            snippetRepository = snippetRepository,
            transcriptStore = transcriptStore
        )
    }
}

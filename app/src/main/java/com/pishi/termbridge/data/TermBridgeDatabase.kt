package com.pishi.termbridge.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * TermBridge database, version 9 (v5: prompt 18 adds the
 * shellConfirmed flag to host_settings; v6: the keychain —
 * a keychain_entries table plus a nullable keychainEntryId
 * reference on hosts, item T-01; v7: global snippets tables,
 * item T-02; v8: host list organization columns — favorite,
 * groupName, lastConnectedAt — item T-22; v9: the env-export
 * fallback opt-in on host_settings, item F-02; v10: the
 * session-transcript opt-ins on host_settings, item T-03).
 *
 * Migration decision (explicit, same as prompts 08–10): real
 * Migrations, NOT fallbackToDestructiveMigration, even though
 * the app is pre-release. Creating three small tables is trivial;
 * destroying a user's hosts, trust records, buttons, and env vars
 * for it would be data loss for no benefit. The migration SQL must
 * match the entity definitions exactly — if those entities change,
 * this migration must change with them, and the version must bump
 * again.
 *
 * Schema export is disabled (exportSchema = false): the schema is
 * small and versioned here, and a future migration will document
 * its schema change in its own prompt report.
 */
@Database(
    entities = [
        HostEntity::class,
        TrustedHostKeyEntity::class,
        CommandButtonEntity::class,
        CommandStepEntity::class,
        CommandVariableEntity::class,
        HostSettingsEntity::class,
        HostEnvVarEntity::class,
        TunnelEntity::class,
        JumpHopEntity::class,
        ProxyEntity::class,
        KeychainEntryEntity::class,
        SnippetEntity::class,
        SnippetStepEntity::class,
        SnippetVariableEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class TermBridgeDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun keychainDao(): KeychainDao
    abstract fun snippetDao(): SnippetDao
    abstract fun trustedHostKeyDao(): TrustedHostKeyDao
    abstract fun commandButtonDao(): CommandButtonDao
    abstract fun hostSettingsDao(): HostSettingsDao
    abstract fun hostEnvVarDao(): HostEnvVarDao
    abstract fun tunnelDao(): TunnelDao
    abstract fun jumpHopDao(): JumpHopDao
    abstract fun proxyDao(): ProxyDao
}

/**
 * Prompt 18 (audit-17 R1): every row created before shell
 * confirmation tracking existed is UNCONFIRMED by definition —
 * its stored POSIX value was never presented to the user as a
 * choice. DEFAULT 0 therefore preserves the warning for all
 * pre-existing rows until their environment screen is saved.
 * ALTER TABLE ... ADD COLUMN has no IF NOT EXISTS form in
 * SQLite; Room runs each migration exactly once per version
 * step, which is what makes this safe to re-run in practice
 * (see MigrationSqlAuditTest's documented ALTER exception).
 */
/**
 * Item T-01: the keychain. One new table plus one nullable
 * reference column on hosts. Existing hosts keep working
 * unchanged: keychainEntryId is NULL, which means "use the
 * inline credentialBlob", exactly the pre-v6 behaviour. The
 * label index is UNIQUE so two entries can never share a
 * label (imports and host re-linking address entries by
 * label); the repository checks first and reports the
 * conflict in words, the index is the second net.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `keychain_entries` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`label` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`materialBlob` BLOB NOT NULL, " +
                "`publicKeyText` TEXT, " +
                "`fingerprint` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_keychain_entries_label` " +
                "ON `keychain_entries` (`label`)"
        )
        db.execSQL(
            "ALTER TABLE `hosts` " +
                "ADD COLUMN `keychainEntryId` INTEGER"
        )
    }
}

/**
 * Item T-02: global snippets. Three new tables mirroring the
 * command-button tables, minus the host column. CREATE-only,
 * like every migration here — the audit contract forbids
 * destructive statements, which is exactly why snippets did
 * not become a sentinel row inside command_buttons (its host
 * foreign key cannot be relaxed without a table rebuild).
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `snippets` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`label` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`mode` TEXT NOT NULL, " +
                "`template` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `snippet_steps` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`snippetId` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`template` TEXT NOT NULL, " +
                "FOREIGN KEY(`snippetId`) REFERENCES `snippets`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_snippet_steps_snippetId` " +
                "ON `snippet_steps` (`snippetId`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `snippet_variables` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`snippetId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`defaultValue` TEXT NOT NULL, " +
                "`required` INTEGER NOT NULL, " +
                "FOREIGN KEY(`snippetId`) REFERENCES `snippets`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_snippet_variables_snippetId` " +
                "ON `snippet_variables` (`snippetId`)"
        )
    }
}

/**
 * Item F-02: the shell-export fallback opt-in on host_settings
 * (default 0 = off). ALTER-only, matching the v5 shellConfirmed
 * pattern on the same table.
 */
val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `host_settings` " +
                "ADD COLUMN `envExportFallback` INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * Item T-03: the session-transcript opt-ins on host_settings
 * (both default 0 = off). ALTER-only, same pattern.
 */
val MIGRATION_9_10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `host_settings` " +
                "ADD COLUMN `sessionLogEnabled` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `host_settings` " +
                "ADD COLUMN `sessionLogInBackup` INTEGER NOT NULL DEFAULT 0"
        )
    }
}

/**
 * Item T-22: host list organization. Three plain columns on
 * hosts — favorite (0/1, default 0 = not a favorite),
 * groupName (default '' = ungrouped) and lastConnectedAt
 * (nullable; NULL = never connected). ALTER-only, matching
 * the v6 keychainEntryId pattern.
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `hosts` " +
                "ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL(
            "ALTER TABLE `hosts` " +
                "ADD COLUMN `groupName` TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "ALTER TABLE `hosts` " +
                "ADD COLUMN `lastConnectedAt` INTEGER"
        )
    }
}

val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `host_settings` " +
                "ADD COLUMN `shellConfirmed` INTEGER NOT NULL DEFAULT 0"
        )
    }
}

val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tunnels` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`hostId` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`bindAddress` TEXT NOT NULL, " +
                "`bindPort` INTEGER NOT NULL, " +
                "`targetHost` TEXT NOT NULL, " +
                "`targetPort` INTEGER NOT NULL, " +
                "`allowNonLoopback` INTEGER NOT NULL, " +
                "`enabled` INTEGER NOT NULL, " +
                "`label` TEXT NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tunnels_hostId` " +
                "ON `tunnels` (`hostId`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `jump_hops` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`targetHostId` INTEGER NOT NULL, " +
                "`jumpHostId` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "FOREIGN KEY(`targetHostId`) REFERENCES `hosts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(`jumpHostId`) REFERENCES `hosts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_jump_hops_targetHostId` " +
                "ON `jump_hops` (`targetHostId`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_jump_hops_targetHostId_jumpHostId` " +
                "ON `jump_hops` (`targetHostId`, `jumpHostId`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `proxies` (" +
                "`hostId` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`proxyHost` TEXT NOT NULL, " +
                "`proxyPort` INTEGER NOT NULL, " +
                "PRIMARY KEY(`hostId`), " +
                "FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
    }
}

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `host_settings` (" +
                "`hostId` INTEGER NOT NULL, " +
                "`remoteWorkingDirectory` TEXT NOT NULL, " +
                "`localDirectoryUri` TEXT, " +
                "`remoteShell` TEXT NOT NULL, " +
                "PRIMARY KEY(`hostId`), " +
                "FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `host_env_vars` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`hostId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`valueBlob` BLOB NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_host_env_vars_hostId` " +
                "ON `host_env_vars` (`hostId`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "`index_host_env_vars_hostId_name` " +
                "ON `host_env_vars` (`hostId`, `name`)"
        )
    }
}

val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `command_buttons` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`hostId` INTEGER NOT NULL, " +
                "`label` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`mode` TEXT NOT NULL, " +
                "`template` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`hostId`) REFERENCES `hosts`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_command_buttons_hostId` " +
                "ON `command_buttons` (`hostId`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `command_steps` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`buttonId` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`template` TEXT NOT NULL, " +
                "FOREIGN KEY(`buttonId`) REFERENCES `command_buttons`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_command_steps_buttonId` " +
                "ON `command_steps` (`buttonId`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `command_variables` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`buttonId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`defaultValue` TEXT NOT NULL, " +
                "`required` INTEGER NOT NULL, " +
                "FOREIGN KEY(`buttonId`) REFERENCES `command_buttons`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_command_variables_buttonId` " +
                "ON `command_variables` (`buttonId`)"
        )
    }
}

package com.pishi.termbridge.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * TermBridge database, version 5 (prompt 18 adds the
 * shellConfirmed flag to host_settings).
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
        ProxyEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class TermBridgeDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
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

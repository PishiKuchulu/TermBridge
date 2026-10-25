package com.pishi.termbridge

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pishi.termbridge.data.MIGRATION_1_2
import com.pishi.termbridge.data.MIGRATION_2_3
import com.pishi.termbridge.data.MIGRATION_3_4
import com.pishi.termbridge.data.MIGRATION_4_5
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt 15 migration audit — a JVM-runnable substitute for
 * Room's MigrationTestHelper, chosen deliberately (see
 * docs/INTEGRATION_NOTES.md §3 for the full decision):
 *
 * MigrationTestHelper requires exported schema JSONs for every
 * version, and this project has exportSchema = false since
 * prompt 02, so no v1–v3 schema JSON exists. Hand-reconstructing
 * historical schemas would be unproven assertions, not evidence.
 *
 * What this test DOES prove, on the JVM in the existing CI unit
 * test job (no emulator needed): each Migration object, when run
 * against a recording database, issues exactly the CREATE TABLE,
 * CREATE INDEX, and (prompt 18 only) ALTER TABLE ... ADD COLUMN
 * statements its entity definitions require — same tables, same
 * columns, same types, same nullability, same primary keys, same
 * foreign keys (targets resolvable, all ON DELETE CASCADE), same
 * indices (including uniqueness) — and nothing destructive
 * (no DROP/DELETE/PRAGMA, and ALTER only as ADD COLUMN).
 * The prompt-18 ALTER exception is deliberate: SQLite has no
 * IF NOT EXISTS form for ADD COLUMN, and Room runs each
 * migration exactly once per version step, so a single
 * ADD COLUMN with a DEFAULT is non-destructive in practice
 * (existing rows get the default). The expected schemas below
 * MIRROR the entity files by hand; if an entity changes, this
 * test must change with it, and a drift between entity and
 * migration SQL fails here instead of on a user's device. What
 * it does NOT prove: that Room's own schema comparison accepts
 * the result, or that data survives the migration — both need a
 * real Room database and are covered by the instrumented CI
 * job's other tests plus the first-run manual migration check
 * in docs/INTEGRATION_NOTES.md §3.
 */
class MigrationSqlAuditTest {

    private data class ColumnDef(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val primaryKey: Boolean,
        val autoIncrement: Boolean
    )

    private data class ForeignKeyDef(
        val childColumn: String,
        val parentTable: String,
        val parentColumn: String,
        val onDeleteCascade: Boolean
    )

    private data class TableDef(
        val name: String,
        val columns: List<ColumnDef>,
        val tablePrimaryKey: List<String>,
        val foreignKeys: List<ForeignKeyDef>
    )

    private data class IndexDef(
        val name: String,
        val table: String,
        val columns: List<String>,
        val unique: Boolean
    )

    /** One ALTER TABLE ... ADD COLUMN, with its DEFAULT value. */
    private data class AlterDef(
        val table: String,
        val column: ColumnDef,
        val defaultValue: String?
    )

    private data class ExpectedMigration(
        val migration: Migration,
        val fromVersion: Int,
        val toVersion: Int,
        val tables: List<TableDef>,
        val indices: List<IndexDef>,
        val alters: List<AlterDef> = emptyList()
    )

    // Column helpers: Room maps Kotlin Long -> INTEGER, Int ->
    // INTEGER, String -> TEXT, Boolean -> INTEGER, ByteArray -> BLOB.
    private fun pkId() = ColumnDef("id", "INTEGER", true, true, true)
    private fun col(name: String, type: String, notNull: Boolean = true) =
        ColumnDef(name, type, notNull, false, false)

    private val expected = listOf(
        ExpectedMigration(
            MIGRATION_1_2, 1, 2,
            tables = listOf(
                TableDef(
                    "command_buttons",
                    listOf(
                        pkId(), col("hostId", "INTEGER"), col("label", "TEXT"),
                        col("category", "TEXT"), col("sortOrder", "INTEGER"),
                        col("mode", "TEXT"), col("template", "TEXT"),
                        col("createdAt", "INTEGER"), col("updatedAt", "INTEGER")
                    ),
                    emptyList(),
                    listOf(ForeignKeyDef("hostId", "hosts", "id", true))
                ),
                TableDef(
                    "command_steps",
                    listOf(
                        pkId(), col("buttonId", "INTEGER"),
                        col("sortOrder", "INTEGER"), col("template", "TEXT")
                    ),
                    emptyList(),
                    listOf(ForeignKeyDef("buttonId", "command_buttons", "id", true))
                ),
                TableDef(
                    "command_variables",
                    listOf(
                        pkId(), col("buttonId", "INTEGER"), col("name", "TEXT"),
                        col("defaultValue", "TEXT"), col("required", "INTEGER")
                    ),
                    emptyList(),
                    listOf(ForeignKeyDef("buttonId", "command_buttons", "id", true))
                )
            ),
            indices = listOf(
                IndexDef("index_command_buttons_hostId", "command_buttons", listOf("hostId"), false),
                IndexDef("index_command_steps_buttonId", "command_steps", listOf("buttonId"), false),
                IndexDef("index_command_variables_buttonId", "command_variables", listOf("buttonId"), false)
            )
        ),
        ExpectedMigration(
            MIGRATION_2_3, 2, 3,
            tables = listOf(
                TableDef(
                    "host_settings",
                    listOf(
                        col("hostId", "INTEGER"),
                        col("remoteWorkingDirectory", "TEXT"),
                        col("localDirectoryUri", "TEXT", notNull = false),
                        col("remoteShell", "TEXT")
                    ),
                    listOf("hostId"),
                    listOf(ForeignKeyDef("hostId", "hosts", "id", true))
                ),
                TableDef(
                    "host_env_vars",
                    listOf(
                        pkId(), col("hostId", "INTEGER"), col("name", "TEXT"),
                        col("valueBlob", "BLOB"), col("sortOrder", "INTEGER")
                    ),
                    emptyList(),
                    listOf(ForeignKeyDef("hostId", "hosts", "id", true))
                )
            ),
            indices = listOf(
                IndexDef("index_host_env_vars_hostId", "host_env_vars", listOf("hostId"), false),
                IndexDef(
                    "index_host_env_vars_hostId_name", "host_env_vars",
                    listOf("hostId", "name"), true
                )
            )
        ),
        ExpectedMigration(
            MIGRATION_3_4, 3, 4,
            tables = listOf(
                TableDef(
                    "tunnels",
                    listOf(
                        pkId(), col("hostId", "INTEGER"), col("kind", "TEXT"),
                        col("bindAddress", "TEXT"), col("bindPort", "INTEGER"),
                        col("targetHost", "TEXT"), col("targetPort", "INTEGER"),
                        col("allowNonLoopback", "INTEGER"), col("enabled", "INTEGER"),
                        col("label", "TEXT"), col("sortOrder", "INTEGER")
                    ),
                    emptyList(),
                    listOf(ForeignKeyDef("hostId", "hosts", "id", true))
                ),
                TableDef(
                    "jump_hops",
                    listOf(
                        pkId(), col("targetHostId", "INTEGER"),
                        col("jumpHostId", "INTEGER"), col("sortOrder", "INTEGER")
                    ),
                    emptyList(),
                    listOf(
                        ForeignKeyDef("targetHostId", "hosts", "id", true),
                        ForeignKeyDef("jumpHostId", "hosts", "id", true)
                    )
                ),
                TableDef(
                    "proxies",
                    listOf(
                        col("hostId", "INTEGER"), col("kind", "TEXT"),
                        col("proxyHost", "TEXT"), col("proxyPort", "INTEGER")
                    ),
                    listOf("hostId"),
                    listOf(ForeignKeyDef("hostId", "hosts", "id", true))
                )
            ),
            indices = listOf(
                IndexDef("index_tunnels_hostId", "tunnels", listOf("hostId"), false),
                IndexDef("index_jump_hops_targetHostId", "jump_hops", listOf("targetHostId"), false),
                IndexDef(
                    "index_jump_hops_targetHostId_jumpHostId", "jump_hops",
                    listOf("targetHostId", "jumpHostId"), true
                )
            )
        ),
        ExpectedMigration(
            MIGRATION_4_5, 4, 5,
            tables = emptyList(),
            indices = emptyList(),
            alters = listOf(
                // Prompt 18: every pre-existing host_settings row
                // becomes UNCONFIRMED (DEFAULT 0) until its
                // environment screen is saved.
                AlterDef(
                    "host_settings",
                    col("shellConfirmed", "INTEGER"),
                    "0"
                )
            )
        )
    )

    /** Run a migration against a recording proxy database and
     *  return every SQL string it issued, in order. */
    private fun captureSql(migration: Migration): List<String> {
        val captured = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    captured += args?.get(0) as String
                    null
                }
                "toString" -> "RecordingSupportSQLiteDatabase"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> throw UnsupportedOperationException(
                    "Migration must only call execSQL, got ${method.name}."
                )
            }
        } as SupportSQLiteDatabase
        migration.migrate(db)
        return captured
    }

    private fun splitTopLevel(body: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in body.indices) {
            when (body[i]) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    parts += body.substring(start, i)
                    start = i + 1
                }
            }
        }
        parts += body.substring(start)
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseCreateTable(sql: String): TableDef {
        val match = Regex(
            """(?i)CREATE TABLE IF NOT EXISTS `?(\w+)`?\s*\((.*)\)\s*$""",
            RegexOption.DOT_MATCHES_ALL
        ).find(sql) ?: throw AssertionError("Not a CREATE TABLE statement: $sql")
        val name = match.groupValues[1]
        val columns = mutableListOf<ColumnDef>()
        var tablePk: List<String> = emptyList()
        val fks = mutableListOf<ForeignKeyDef>()
        for (part in splitTopLevel(match.groupValues[2])) {
            when {
                part.startsWith("FOREIGN KEY", ignoreCase = true) -> {
                    val fk = Regex(
                        """(?i)FOREIGN KEY\s*\(`?(\w+)`?\)\s*REFERENCES\s+`?(\w+)`?\s*\(`?(\w+)`?\)"""
                    ).find(part) ?: throw AssertionError("Unparseable FOREIGN KEY: $part")
                    fks += ForeignKeyDef(
                        fk.groupValues[1], fk.groupValues[2], fk.groupValues[3],
                        part.contains("ON DELETE CASCADE", ignoreCase = true)
                    )
                }
                part.startsWith("PRIMARY KEY", ignoreCase = true) -> {
                    val inner = part.substringAfter('(').substringBeforeLast(')')
                    tablePk = inner.split(',').map { it.trim().trim('`') }
                }
                else -> {
                    val tokens = part.split(Regex("\\s+"))
                    require(tokens.size >= 2) { "Unparseable column: $part" }
                    columns += ColumnDef(
                        name = tokens[0].trim('`'),
                        type = tokens[1].uppercase(),
                        notNull = part.contains("NOT NULL", ignoreCase = true),
                        primaryKey = part.contains("PRIMARY KEY", ignoreCase = true),
                        autoIncrement = part.contains("AUTOINCREMENT", ignoreCase = true)
                    )
                }
            }
        }
        return TableDef(name, columns, tablePk, fks)
    }

    private fun parseCreateIndex(sql: String): IndexDef {
        val match = Regex(
            """(?i)CREATE\s+(UNIQUE\s+)?INDEX\s+IF NOT EXISTS\s+`?(\w+)`?\s+ON\s+`?(\w+)`?\s*\(([^)]*)\)"""
        ).find(sql) ?: throw AssertionError("Not a CREATE INDEX statement: $sql")
        return IndexDef(
            name = match.groupValues[2],
            table = match.groupValues[3],
            columns = match.groupValues[4].split(',').map { it.trim().trim('`') },
            unique = match.groupValues[1].isNotBlank()
        )
    }

    @Test
    fun migrationVersionsChainWithoutGaps() {
        for (e in expected) {
            assertEquals(e.fromVersion, e.migration.startVersion)
            assertEquals(e.toVersion, e.migration.endVersion)
        }
        // 1 -> 2 -> 3 -> 4 -> 5, no missing step.
        for (i in 1 until expected.size) {
            assertEquals(expected[i - 1].toVersion, expected[i].fromVersion)
        }
    }

    @Test
    fun migrationsIssueOnlyNonDestructiveCreateStatements() {
        for (e in expected) {
            val statements = captureSql(e.migration)
            assertTrue("Migration ${e.fromVersion}->${e.toVersion} issued no SQL.", statements.isNotEmpty())
            for (sql in statements) {
                val trimmed = sql.trimStart()
                val isAddColumn = trimmed.matches(
                    Regex(
                        "(?i)ALTER TABLE `?\\w+`? ADD COLUMN .*"
                    )
                )
                assertFalse(
                    "Destructive statement in ${e.fromVersion}->${e.toVersion}: $sql",
                    sql.contains("DROP TABLE", ignoreCase = true) ||
                        trimmed.startsWith("DELETE", ignoreCase = true) ||
                        trimmed.startsWith("PRAGMA", ignoreCase = true) ||
                        (trimmed.startsWith("ALTER", ignoreCase = true) && !isAddColumn)
                )
                // CREATE statements must be IF NOT EXISTS (safe
                // re-run). ALTER ... ADD COLUMN has no such syntax
                // in SQLite and is exempt: Room runs each migration
                // exactly once per version step.
                if (trimmed.startsWith("CREATE", ignoreCase = true)) {
                    assertTrue(
                        "Expected IF NOT EXISTS (safe re-run) in: $sql",
                        sql.contains("IF NOT EXISTS", ignoreCase = true)
                    )
                }
            }
        }
    }

    private fun parseAlterAddColumn(sql: String): AlterDef {
        val match = Regex(
            """(?i)ALTER TABLE `?(\w+)`? ADD COLUMN `?(\w+)`? (\w+)(.*)"""
        ).find(sql) ?: throw AssertionError("Not an ALTER TABLE ... ADD COLUMN statement: $sql")
        val rest = match.groupValues[4]
        val default = Regex("""(?i)DEFAULT\s+(\S+)""").find(rest)?.groupValues?.get(1)
        return AlterDef(
            table = match.groupValues[1],
            column = ColumnDef(
                name = match.groupValues[2],
                type = match.groupValues[3].uppercase(),
                notNull = rest.contains("NOT NULL", ignoreCase = true),
                primaryKey = false,
                autoIncrement = false
            ),
            defaultValue = default
        )
    }

    @Test
    fun migrationAlterAddColumnsMatchExpected() {
        for (e in expected) {
            val statements = captureSql(e.migration)
            val alters = statements
                .filter { it.trimStart().startsWith("ALTER", ignoreCase = true) }
                .map { parseAlterAddColumn(it) }
            assertEquals(
                "ALTER set for ${e.fromVersion}->${e.toVersion}",
                e.alters.toSet(), alters.toSet()
            )
        }
    }

    @Test
    fun hostSettingsFinalSchemaIncludesShellConfirmed() {
        // Apply every migration's schema effect in order and
        // check the final host_settings columns against the
        // entity: the v3 CREATE plus the v5 ADD COLUMN.
        val columns = mutableMapOf<String, MutableList<ColumnDef>>()
        for (e in expected) {
            val statements = captureSql(e.migration)
            for (table in statements
                .filter { it.trimStart().startsWith("CREATE TABLE", ignoreCase = true) }
                .map { parseCreateTable(it) }
            ) {
                columns[table.name] = table.columns.toMutableList()
            }
            for (alter in statements
                .filter { it.trimStart().startsWith("ALTER", ignoreCase = true) }
                .map { parseAlterAddColumn(it) }
            ) {
                columns.getValue(alter.table) += alter.column
            }
        }
        assertEquals(
            "Final host_settings columns",
            setOf(
                "hostId", "remoteWorkingDirectory", "localDirectoryUri",
                "remoteShell", "shellConfirmed"
            ),
            columns.getValue("host_settings").map { it.name }.toSet()
        )
    }

    @Test
    fun migrationTablesMatchEntitySchemasExactly() {
        for (e in expected) {
            val statements = captureSql(e.migration)
            val tables = statements
                .filter { it.trimStart().startsWith("CREATE TABLE", ignoreCase = true) }
                .map { parseCreateTable(it) }
            assertEquals(
                "Table set for ${e.fromVersion}->${e.toVersion}",
                e.tables.map { it.name }.toSet(),
                tables.map { it.name }.toSet()
            )
            for (want in e.tables) {
                val got = tables.first { it.name == want.name }
                assertEquals(
                    "Columns of ${want.name}",
                    want.columns.toSet(), got.columns.toSet()
                )
                assertEquals(
                    "Table-level primary key of ${want.name}",
                    want.tablePrimaryKey, got.tablePrimaryKey
                )
                assertEquals(
                    "Foreign keys of ${want.name}",
                    want.foreignKeys.toSet(), got.foreignKeys.toSet()
                )
            }
        }
    }

    @Test
    fun foreignKeyTargetsResolveAndIndicesReferenceRealColumns() {
        // Base v1 tables (created by Room, not by any migration).
        val knownTables = mutableMapOf<String, Set<String>>(
            "hosts" to setOf("id", "name", "hostname", "port", "username", "authType"),
            "trusted_host_keys" to setOf("endpoint", "fingerprint", "keyType")
        )
        for (e in expected) {
            val statements = captureSql(e.migration)
            val tables = statements
                .filter { it.trimStart().startsWith("CREATE TABLE", ignoreCase = true) }
                .map { parseCreateTable(it) }
            val indices = statements
                .filter { it.trimStart().startsWith("CREATE", ignoreCase = true) &&
                    !it.trimStart().startsWith("CREATE TABLE", ignoreCase = true) }
                .map { parseCreateIndex(it) }
            // Tables of this migration join the known set before
            // indices are checked, so an index may reference a
            // table created by the same migration.
            for (t in tables) knownTables[t.name] = t.columns.map { it.name }.toSet()
            assertEquals(
                "Index set for ${e.fromVersion}->${e.toVersion}",
                e.indices.toSet(), indices.toSet()
            )
            for (t in tables) {
                for (fk in t.foreignKeys) {
                    assertTrue(
                        "FK ${t.name}.${fk.childColumn} -> unknown table ${fk.parentTable}",
                        fk.parentTable in knownTables
                    )
                    assertTrue(
                        "FK ${t.name}.${fk.childColumn} must cascade on delete",
                        fk.onDeleteCascade
                    )
                    assertEquals(
                        "FK parent column for ${t.name}.${fk.childColumn}",
                        "id", fk.parentColumn
                    )
                }
            }
            for (idx in indices) {
                val cols = knownTables[idx.table]
                    ?: throw AssertionError("Index ${idx.name} references unknown table ${idx.table}")
                for (c in idx.columns) {
                    assertTrue(
                        "Index ${idx.name} references unknown column ${idx.table}.$c",
                        c in cols
                    )
                }
            }
        }
        // Final schema: v1 base (2) + v2 (3) + v3 (2) + v4 (3).
        assertEquals(10, knownTables.size)
    }
}

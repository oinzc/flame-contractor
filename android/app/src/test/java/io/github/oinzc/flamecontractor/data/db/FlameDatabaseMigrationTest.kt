package io.github.oinzc.flamecontractor.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlameDatabaseMigrationTest {

    @Test
    fun entityDefaultsMatchVersion3Schema() {
        val account = AccountEntity()
        assertTrue(account.autoScanEnabled)
        assertEquals(24, account.autoScanIntervalHours)
        assertNull(account.lastAutoScanAttemptAt)

        val scanRun = ScanRunEntity()
        assertNull(scanRun.alertSummary)
    }

    @Test
    fun migration2To3StatementsAndMetadataAreExact() {
        assertEquals(
            listOf(
                "ALTER TABLE accounts ADD COLUMN auto_scan_enabled INTEGER NOT NULL DEFAULT 1",
                "ALTER TABLE accounts ADD COLUMN auto_scan_interval_hours INTEGER NOT NULL DEFAULT 24",
                "ALTER TABLE accounts ADD COLUMN last_auto_scan_attempt_at INTEGER",
                "ALTER TABLE scan_runs ADD COLUMN alert_summary TEXT",
            ),
            FlameDatabase.MIGRATION_2_3_STATEMENTS,
        )
        assertEquals(2, FlameDatabase.MIGRATION_2_3.startVersion)
        assertEquals(3, FlameDatabase.MIGRATION_2_3.endVersion)
    }

    @Test
    fun migration2To3ExecutesAllAlterStatementsInOrder() {
        val recorder = RecordingSqliteDb()
        FlameDatabase.MIGRATION_2_3.migrate(recorder.db)

        assertEquals(4, recorder.calls.size)
        assertEquals(
            FlameDatabase.MIGRATION_2_3_STATEMENTS,
            recorder.calls.map { it.sql },
        )
        recorder.calls.forEach { call ->
            assertNull(call.bindArgs)
        }
    }

    @Test
    fun seedInitialDataBindsAll22AccountColumns() {
        val recorder = RecordingSqliteDb()
        val fixedNow = 1_700_000_000_000L
        FlameDatabase.seedInitialData(
            db = recorder.db,
            nowEpochMs = fixedNow,
            defaultTimezone = "Asia/Shanghai",
        )

        val accountInsert = recorder.calls.firstOrNull {
            it.sql.contains("INSERT OR IGNORE INTO accounts")
        } ?: error("Expected INSERT OR IGNORE INTO accounts statement")

        val columnsSection = accountInsert.sql
            .substringAfter("INSERT OR IGNORE INTO accounts (")
            .substringBefore(") VALUES (")
        val columns = columnsSection.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val placeholderCount = accountInsert.sql.substringAfter("VALUES (").count { it == '?' }
        val bindings = accountInsert.bindArgs ?: error("Expected bind arguments for account insert")

        assertEquals(22, columns.size)
        assertEquals(22, placeholderCount)
        assertEquals(22, bindings.size)

        val colToValue = columns.zip(bindings).toMap()
        assertEquals(1, colToValue["auto_scan_enabled"])
        assertEquals(24, colToValue["auto_scan_interval_hours"])
        assertNull(colToValue["last_auto_scan_attempt_at"])
    }

    @Test
    fun templateTypeImageAndMigration3To4AreExact() {
        assertEquals(TemplateType.IMAGE, TemplateType.fromWireValue("image"))
        val imgTemplate = MessageTemplateEntity(
            name = "早安图",
            type = TemplateType.IMAGE,
        )
        assertNull(imgTemplate.imagePath)

        assertEquals(
            listOf("ALTER TABLE message_templates ADD COLUMN image_path TEXT"),
            FlameDatabase.MIGRATION_3_4_STATEMENTS,
        )
        assertEquals(3, FlameDatabase.MIGRATION_3_4.startVersion)
        assertEquals(4, FlameDatabase.MIGRATION_3_4.endVersion)

        val recorder = RecordingSqliteDb()
        FlameDatabase.MIGRATION_3_4.migrate(recorder.db)
        assertEquals(1, recorder.calls.size)
        assertEquals(
            FlameDatabase.MIGRATION_3_4_STATEMENTS,
            recorder.calls.map { it.sql },
        )
        assertNull(recorder.calls.single().bindArgs)
    }

    private class RecordingSqliteDb {
        data class ExecCall(
            val sql: String,
            val bindArgs: List<Any?>? = null,
        )

        val calls = mutableListOf<ExecCall>()

        val db: SupportSQLiteDatabase = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    val sql = args[0] as String
                    val bindArgs = if (args.size >= 2 && args[1] is Array<*>) {
                        (args[1] as Array<*>).toList()
                    } else {
                        null
                    }
                    calls += ExecCall(sql = sql, bindArgs = bindArgs)
                    Unit
                }
                "toString" -> "RecordingSqliteDb"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> null
            }
        } as SupportSQLiteDatabase
    }
}

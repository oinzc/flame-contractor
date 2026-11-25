package io.github.oinzc.flamecontractor.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.ZoneId

class FlameTypeConverters {

    @TypeConverter
    fun fromAccountStatus(value: AccountStatus): String = value.wireValue

    @TypeConverter
    fun toAccountStatus(value: String): AccountStatus = AccountStatus.fromWireValue(value)

    @TypeConverter
    fun fromTemplateType(value: TemplateType): String = value.wireValue

    @TypeConverter
    fun toTemplateType(value: String): TemplateType = TemplateType.fromWireValue(value)

    @TypeConverter
    fun fromJobStatus(value: JobStatus): String = value.wireValue

    @TypeConverter
    fun toJobStatus(value: String): JobStatus = JobStatus.fromWireValue(value)

    @TypeConverter
    fun fromAttemptStatus(value: AttemptStatus): String = value.wireValue

    @TypeConverter
    fun toAttemptStatus(value: String): AttemptStatus = AttemptStatus.fromWireValue(value)

    @TypeConverter
    fun fromFailureClass(value: FailureClass): String = value.wireValue

    @TypeConverter
    fun toFailureClass(value: String): FailureClass = FailureClass.fromWireValue(value)

    @TypeConverter
    fun fromScanRunStatus(value: ScanRunStatus): String = value.wireValue

    @TypeConverter
    fun toScanRunStatus(value: String): ScanRunStatus = ScanRunStatus.fromWireValue(value)
}

@Database(
    entities = [
        AccountEntity::class,
        ContractTargetEntity::class,
        MessageTemplateEntity::class,
        TemplatePoolItemEntity::class,
        SendJobEntity::class,
        SendAttemptEntity::class,
        ScanRunEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(FlameTypeConverters::class)
abstract class FlameDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao

    abstract fun targetDao(): TargetDao

    abstract fun templateDao(): TemplateDao

    abstract fun sendJobDao(): SendJobDao

    abstract fun scanRunDao(): ScanRunDao

    companion object {
        const val DATABASE_NAME = "flame_contractor.db"
        internal const val LEGACY_DEFAULT_TEXT_TEMPLATE_NAME = "每日续火"
        internal const val LEGACY_DEFAULT_TEXT_TEMPLATE_CONTENT = "每日续火打卡 🔥"
        private const val MAINTENANCE_PREFS_NAME = "flame_db_maintenance"
        internal const val KEY_LEGACY_DEFAULT_TEXT_TEMPLATE_MIGRATED_V1 =
            "legacy_default_text_template_migrated_v1"

        internal data class DefaultStickerSeed(
            val id: String,
            val name: String,
            val stickerName: String,
            val stickerLabel: String,
        )

        internal val DEFAULT_STICKER_SEEDS: List<DefaultStickerSeed> = listOf(
            DefaultStickerSeed(
                id = DEFAULT_STICKER_SPARK_TEMPLATE_ID,
                name = "续火花",
                stickerName = "续火花",
                stickerLabel = "续火花",
            ),
            DefaultStickerSeed(
                id = DEFAULT_STICKER_TEMPLATE_ID,
                name = "比心",
                stickerName = "比心",
                stickerLabel = "比心",
            ),
            DefaultStickerSeed(
                id = DEFAULT_STICKER_MORNING_TEMPLATE_ID,
                name = "早上好",
                stickerName = "早上好",
                stickerLabel = "早上好",
            ),
        )

        internal val MIGRATION_1_2_STICKER_SEEDS: List<DefaultStickerSeed> = listOf(
            DefaultStickerSeed(
                id = DEFAULT_STICKER_MORNING_TEMPLATE_ID,
                name = "早上好",
                stickerName = "早上好",
                stickerLabel = "早上好",
            ),
            DefaultStickerSeed(
                id = DEFAULT_STICKER_SPARK_TEMPLATE_ID,
                name = "续火花",
                stickerName = "续火花",
                stickerLabel = "续火花",
            ),
        )

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                seedDefaultStickerTemplates(
                    db = db,
                    seeds = MIGRATION_1_2_STICKER_SEEDS,
                )
            }
        }

        val MIGRATION_2_3_STATEMENTS: List<String> = listOf(
            "ALTER TABLE accounts ADD COLUMN auto_scan_enabled INTEGER NOT NULL DEFAULT 1",
            "ALTER TABLE accounts ADD COLUMN auto_scan_interval_hours INTEGER NOT NULL DEFAULT 24",
            "ALTER TABLE accounts ADD COLUMN last_auto_scan_attempt_at INTEGER",
            "ALTER TABLE scan_runs ADD COLUMN alert_summary TEXT",
        )

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_2_3_STATEMENTS.forEach { sql ->
                    db.execSQL(sql)
                }
            }
        }

        val MIGRATION_3_4_STATEMENTS: List<String> = listOf(
            "ALTER TABLE message_templates ADD COLUMN image_path TEXT",
        )

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_3_4_STATEMENTS.forEach { sql ->
                    db.execSQL(sql)
                }
            }
        }

        fun create(context: Context): FlameDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                FlameDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(seedCallback())
                .build()

        fun createInMemory(context: Context): FlameDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                FlameDatabase::class.java,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(seedCallback())
                .build()

        private fun seedCallback(): Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                seedInitialData(db)
            }
        }

        internal fun seedDefaultStickerTemplates(
            db: SupportSQLiteDatabase,
            seeds: List<DefaultStickerSeed> = DEFAULT_STICKER_SEEDS,
            nowEpochMs: Long = System.currentTimeMillis(),
        ) {
            seeds.forEachIndexed { index, seed ->
                val createdAt = nowEpochMs + index.toLong()
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO message_templates (
                        id, account_id, name, type, content,
                        sticker_name, sticker_label, sticker_category, sticker_fallback_index,
                        enabled, created_at, updated_at
                    ) SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    WHERE EXISTS (SELECT 1 FROM accounts WHERE id = ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        seed.id,
                        DEFAULT_ACCOUNT_ID,
                        seed.name,
                        TemplateType.STICKER.wireValue,
                        "",
                        seed.stickerName,
                        seed.stickerLabel,
                        null,
                        null,
                        1,
                        createdAt,
                        createdAt,
                        DEFAULT_ACCOUNT_ID,
                    ),
                )
            }
        }

        internal fun seedInitialData(
            db: SupportSQLiteDatabase,
            nowEpochMs: Long = System.currentTimeMillis(),
            defaultTimezone: String = ZoneId.systemDefault().id,
        ) {
            db.execSQL(
                """
                INSERT OR IGNORE INTO accounts (
                    id, display_name, status, enabled, timezone,
                    send_window_start, send_window_end, send_time_hhmm,
                    jitter_max_minutes, cooldown_min_sec, cooldown_max_sec,
                    auth_fail_count, failure_streak, self_uid, douyin_nickname,
                    last_verified_at, last_success_at, created_at, updated_at,
                    auto_scan_enabled, auto_scan_interval_hours, last_auto_scan_attempt_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    DEFAULT_ACCOUNT_ID,
                    "默认抖音号",
                    AccountStatus.PENDING_LOGIN.wireValue,
                    1,
                    defaultTimezone,
                    "20:00",
                    "22:00",
                    null,
                    15,
                    3,
                    8,
                    0,
                    0,
                    null,
                    null,
                    null,
                    null,
                    nowEpochMs,
                    nowEpochMs,
                    1,
                    24,
                    null,
                ),
            )

            seedDefaultStickerTemplates(
                db = db,
                seeds = DEFAULT_STICKER_SEEDS,
                nowEpochMs = nowEpochMs,
            )
        }

        /**
         * 为新建的多账号播种 3 个内置表情模板（续火花排首位），幂等执行（仅当该账号尚无任何模板时写入）。
         */
        suspend fun seedDefaultTemplatesForAccount(
            database: FlameDatabase,
            accountId: String,
            nowEpochMs: Long = System.currentTimeMillis(),
        ) {
            val templateDao = database.templateDao()
            if (templateDao.countTemplates(accountId) > 0) return

            DEFAULT_STICKER_SEEDS.forEachIndexed { index, seed ->
                val ts = nowEpochMs + index.toLong()
                val scopedId = if (accountId == DEFAULT_ACCOUNT_ID) seed.id else "$accountId:${seed.id}"
                templateDao.upsertTemplate(
                    MessageTemplateEntity(
                        id = scopedId,
                        accountId = accountId,
                        name = seed.name,
                        type = TemplateType.STICKER,
                        content = "",
                        stickerName = seed.stickerName,
                        stickerLabel = seed.stickerLabel,
                        stickerCategory = null,
                        stickerFallbackIndex = null,
                        enabled = true,
                        createdAt = ts,
                        updatedAt = ts,
                    ),
                )
            }
        }

        /**
         * 老用户一次性幂等平滑迁移：
         * - 仅定位未改动过的旧默认文本模板（id 为 `default-text-daily` 或 `<accountId>:default-text-daily`，
         *   且 `type == TEXT`、`name == "每日续火"`、`content.trim() == "每日续火打卡 🔥"`）；
         * - 确保该账号具备「续火花」表情模板后，把仍指向旧模板的好友迁移至该表情模板并删除未改动的旧模板；
         * - 用户修改过文案、改过名称或自定义同名模板的记录一律原样保留，绝不误删。
         */
        suspend fun migrateLegacyDefaultTextTemplateOnce(
            context: Context,
            database: FlameDatabase,
            nowEpochMs: Long = System.currentTimeMillis(),
        ): Int {
            val prefs = (context.applicationContext ?: context)
                .getSharedPreferences(MAINTENANCE_PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_LEGACY_DEFAULT_TEXT_TEMPLATE_MIGRATED_V1, false)) {
                return 0
            }
            return try {
                val migratedTemplates = migrateLegacyDefaultTextTemplatesInDatabase(
                    database = database,
                    nowEpochMs = nowEpochMs,
                )
                prefs.edit().putBoolean(KEY_LEGACY_DEFAULT_TEXT_TEMPLATE_MIGRATED_V1, true).apply()
                migratedTemplates
            } catch (_: Throwable) {
                0
            }
        }

        internal suspend fun migrateLegacyDefaultTextTemplatesInDatabase(
            database: FlameDatabase,
            nowEpochMs: Long = System.currentTimeMillis(),
        ): Int = database.withTransaction {
            val accountIds = database.accountDao().listAll().map { it.id }.distinct()
            if (accountIds.isEmpty()) return@withTransaction 0

            val templateDao = database.templateDao()
            val targetDao = database.targetDao()
            var removedLegacyCount = 0

            for (accountId in accountIds) {
                val templates = templateDao.listTemplates(accountId)
                val legacyCandidates = templates.filter { tpl ->
                    (tpl.id == DEFAULT_TEXT_TEMPLATE_ID || tpl.id == "$accountId:$DEFAULT_TEXT_TEMPLATE_ID") &&
                        tpl.type == TemplateType.TEXT &&
                        tpl.name == LEGACY_DEFAULT_TEXT_TEMPLATE_NAME &&
                        tpl.content.trim() == LEGACY_DEFAULT_TEXT_TEMPLATE_CONTENT
                }
                if (legacyCandidates.isEmpty()) continue

                val expectedSparkId = if (accountId == DEFAULT_ACCOUNT_ID) {
                    DEFAULT_STICKER_SPARK_TEMPLATE_ID
                } else {
                    "$accountId:$DEFAULT_STICKER_SPARK_TEMPLATE_ID"
                }
                val existingSpark = templates.firstOrNull {
                    it.id == expectedSparkId || it.id.endsWith(":$DEFAULT_STICKER_SPARK_TEMPLATE_ID")
                } ?: templates.firstOrNull {
                    it.type == TemplateType.STICKER && it.name.trim() == "续火花"
                }

                val sparkTemplateId = if (existingSpark != null) {
                    existingSpark.id
                } else {
                    val occupiedNames = templates.map { it.name }.toSet()
                    val sparkName = resolveNonCollidingSparkName(occupiedNames)
                    templateDao.upsertTemplate(
                        MessageTemplateEntity(
                            id = expectedSparkId,
                            accountId = accountId,
                            name = sparkName,
                            type = TemplateType.STICKER,
                            content = "",
                            stickerName = "续火花",
                            stickerLabel = "续火花",
                            stickerCategory = null,
                            stickerFallbackIndex = null,
                            enabled = true,
                            createdAt = nowEpochMs,
                            updatedAt = nowEpochMs,
                        ),
                    )
                    expectedSparkId
                }

                val legacyIds = legacyCandidates.mapTo(HashSet()) { it.id }
                val allTargets = targetDao.listAllTargetsIncludingDeleted(accountId)
                for (target in allTargets) {
                    if (target.templateId in legacyIds) {
                        targetDao.update(
                            target.copy(
                                templateId = sparkTemplateId,
                                updatedAt = nowEpochMs,
                            ),
                        )
                    }
                }

                for (legacy in legacyCandidates) {
                    templateDao.deleteTemplateById(legacy.id)
                    removedLegacyCount += 1
                }
            }
            removedLegacyCount
        }

        private fun resolveNonCollidingSparkName(occupiedNames: Set<String>): String {
            if ("续火花" !in occupiedNames) return "续火花"
            if ("续火花（表情）" !in occupiedNames) return "续火花（表情）"
            var index = 2
            while (true) {
                val candidate = "续火花（表情 $index）"
                if (candidate !in occupiedNames) return candidate
                index += 1
            }
        }
    }
}

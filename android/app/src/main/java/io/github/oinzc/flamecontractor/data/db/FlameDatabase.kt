package io.github.oinzc.flamecontractor.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
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
    version = 1,
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

        fun create(context: Context): FlameDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                FlameDatabase::class.java,
                DATABASE_NAME,
            )
                .addCallback(seedCallback())
                .build()

        fun createInMemory(context: Context): FlameDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                FlameDatabase::class.java,
            )
                .addCallback(seedCallback())
                .build()

        private fun seedCallback(): Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                seedInitialData(db)
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
                    last_verified_at, last_success_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                ),
            )

            db.execSQL(
                """
                INSERT OR IGNORE INTO message_templates (
                    id, account_id, name, type, content,
                    sticker_name, sticker_label, sticker_category, sticker_fallback_index,
                    enabled, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    DEFAULT_STICKER_TEMPLATE_ID,
                    DEFAULT_ACCOUNT_ID,
                    "比心",
                    TemplateType.STICKER.wireValue,
                    "",
                    "比心",
                    "比心",
                    null,
                    null,
                    1,
                    nowEpochMs,
                    nowEpochMs,
                ),
            )

            db.execSQL(
                """
                INSERT OR IGNORE INTO message_templates (
                    id, account_id, name, type, content,
                    sticker_name, sticker_label, sticker_category, sticker_fallback_index,
                    enabled, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    DEFAULT_TEXT_TEMPLATE_ID,
                    DEFAULT_ACCOUNT_ID,
                    "每日续火",
                    TemplateType.TEXT.wireValue,
                    "每日续火打卡 🔥",
                    null,
                    null,
                    null,
                    null,
                    1,
                    nowEpochMs + 1L,
                    nowEpochMs + 1L,
                ),
            )
        }
    }
}

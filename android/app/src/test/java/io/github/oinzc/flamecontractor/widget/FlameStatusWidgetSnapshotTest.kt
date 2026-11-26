package io.github.oinzc.flamecontractor.widget

import io.github.oinzc.flamecontractor.core.theme.ThemeMode
import io.github.oinzc.flamecontractor.data.db.AccountEntity
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.data.db.ContractTargetEntity
import io.github.oinzc.flamecontractor.data.db.DEFAULT_ACCOUNT_ID
import io.github.oinzc.flamecontractor.data.db.JobStatus
import io.github.oinzc.flamecontractor.data.db.SendJobEntity
import io.github.oinzc.flamecontractor.domain.SendGuard
import io.github.oinzc.flamecontractor.ui.shell.MainNavState
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlameStatusWidgetSnapshotTest {

    private val shanghaiZone = ZoneId.of("Asia/Shanghai")

    private fun instantAt(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Instant = LocalDateTime.of(year, month, day, hour, minute)
        .atZone(shanghaiZone)
        .toInstant()

    private fun sampleAccount(
        id: String = DEFAULT_ACCOUNT_ID,
        displayName: String = "主账号",
        status: AccountStatus = AccountStatus.HEALTHY,
        enabled: Boolean = true,
        sendWindowStart: String = "20:00",
        sendWindowEnd: String = "22:00",
        sendTimeHhmm: String? = null,
    ): AccountEntity = AccountEntity(
        id = id,
        displayName = displayName,
        status = status,
        enabled = enabled,
        timezone = "Asia/Shanghai",
        sendWindowStart = sendWindowStart,
        sendWindowEnd = sendWindowEnd,
        sendTimeHhmm = sendTimeHhmm,
    )

    private fun sampleTarget(
        id: String,
        nickname: String = "测试好友$id",
        enabled: Boolean = true,
        deletedAt: Long? = null,
        sendTimeHhmm: String? = null,
    ): ContractTargetEntity = ContractTargetEntity(
        id = id,
        accountId = DEFAULT_ACCOUNT_ID,
        nicknameSnapshot = nickname,
        enabled = enabled,
        deletedAt = deletedAt,
        sendTimeHhmm = sendTimeHhmm,
    )

    private fun sampleJob(
        targetId: String,
        businessDate: String = "2026-11-23",
        slotId: String = SendGuard.DAILY_SLOT_ID,
        status: JobStatus = JobStatus.SUCCEEDED,
    ): SendJobEntity = SendJobEntity(
        id = "job_${targetId}_${businessDate}_$slotId",
        accountId = DEFAULT_ACCOUNT_ID,
        targetId = targetId,
        businessDate = businessDate,
        messageSlotId = slotId,
        status = status,
    )

    @Test
    fun `normal progress renders healthy pill next wake time and scan action`() {
        val now = instantAt(2026, 11, 23, 18, 0)
        val account = sampleAccount(status = AccountStatus.HEALTHY)
        val targets = listOf(
            sampleTarget("t1", sendTimeHhmm = "19:30"),
            sampleTarget("t2", sendTimeHhmm = "20:30"),
            sampleTarget("t3", sendTimeHhmm = "21:00"),
        )
        val todayJobs = listOf(
            sampleJob("t1", businessDate = "2026-11-23", status = JobStatus.SUCCEEDED),
        )

        val snapshot = FlameStatusWidgetCalculator.calculate(
            account = account,
            enabledTargets = targets,
            todayJobs = todayJobs,
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 300,
        )

        assertEquals("主账号", snapshot.accountName)
        assertEquals("运行正常", snapshot.statusLabel)
        assertEquals("● 运行正常", snapshot.statusPillText)
        assertEquals(WidgetStatusTone.HEALTHY, snapshot.statusTone)
        assertFalse(snapshot.isAlert)
        assertEquals(1, snapshot.succeededTodayCount)
        assertEquals(3, snapshot.enabledTargetCount)
        assertEquals("今日已完成 1 / 3 位", snapshot.progressHeadline)
        assertEquals("下次排程 20:30", snapshot.footerHint)
        assertEquals("扫描对账 →", snapshot.actionLabel)
        assertEquals(MainNavState.SHORTCUT_TARGETS, snapshot.actionCategory)
        assertFalse(snapshot.isCompactWidth)
        assertTrue(snapshot.showActionPill)
    }

    @Test
    fun `all targets completed today renders completion footer`() {
        val now = instantAt(2026, 11, 23, 20, 45)
        val account = sampleAccount(status = AccountStatus.HEALTHY)
        val targets = listOf(
            sampleTarget("t1", sendTimeHhmm = "20:00"),
            sampleTarget("t2", sendTimeHhmm = "20:15"),
        )
        val todayJobs = listOf(
            sampleJob("t1", businessDate = "2026-11-23", status = JobStatus.SUCCEEDED),
            sampleJob("t2", businessDate = "2026-11-23", status = JobStatus.SUCCEEDED),
        )

        val snapshot = FlameStatusWidgetCalculator.calculate(
            account = account,
            enabledTargets = targets,
            todayJobs = todayJobs,
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 320,
        )

        assertEquals("运行正常", snapshot.statusLabel)
        assertEquals(WidgetStatusTone.HEALTHY, snapshot.statusTone)
        assertFalse(snapshot.isAlert)
        assertEquals("今日已完成 2 / 2 位", snapshot.progressHeadline)
        assertEquals("今日已全部完成", snapshot.footerHint)
        assertEquals("扫描对账 →", snapshot.actionLabel)
        assertEquals(MainNavState.SHORTCUT_TARGETS, snapshot.actionCategory)
    }

    @Test
    fun `unknown review count overrides healthy account state with high contrast alert`() {
        val now = instantAt(2026, 11, 23, 20, 30)
        val account = sampleAccount(status = AccountStatus.HEALTHY)
        val targets = listOf(
            sampleTarget("t1", sendTimeHhmm = "20:00"),
            sampleTarget("t2", sendTimeHhmm = "21:00"),
        )
        val todayJobs = listOf(
            sampleJob("t1", businessDate = "2026-11-23", status = JobStatus.UNKNOWN_REQUIRES_REVIEW),
        )

        val snapshot = FlameStatusWidgetCalculator.calculate(
            account = account,
            enabledTargets = targets,
            todayJobs = todayJobs,
            unknownReviewCount = 2,
            now = now,
            minWidthDp = 280,
        )

        assertEquals("待核对 2 条", snapshot.statusLabel)
        assertEquals(WidgetStatusTone.DANGER, snapshot.statusTone)
        assertTrue(snapshot.isAlert)
        assertEquals("待核对 2 条", snapshot.footerHint)
        assertEquals("立即处理 →", snapshot.actionLabel)
        assertEquals(MainNavState.SHORTCUT_TODAY, snapshot.actionCategory)
        assertTrue(snapshot.showActionPill)
    }

    @Test
    fun `auth expired and other unhealthy statuses render alert state and route to today`() {
        val now = instantAt(2026, 11, 23, 19, 0)
        val targets = listOf(sampleTarget("t1"))

        val authExpired = FlameStatusWidgetCalculator.calculate(
            account = sampleAccount(status = AccountStatus.AUTH_EXPIRED),
            enabledTargets = targets,
            todayJobs = emptyList(),
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 300,
        )
        assertEquals("登录已失效", authExpired.statusLabel)
        assertEquals(WidgetStatusTone.DANGER, authExpired.statusTone)
        assertTrue(authExpired.isAlert)
        assertEquals("立即处理 →", authExpired.actionLabel)
        assertEquals(MainNavState.SHORTCUT_TODAY, authExpired.actionCategory)

        val verification = FlameStatusWidgetCalculator.calculate(
            account = sampleAccount(status = AccountStatus.VERIFICATION_REQUIRED),
            enabledTargets = targets,
            todayJobs = emptyList(),
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 300,
        )
        assertEquals("需身份验证", verification.statusLabel)
        assertEquals(WidgetStatusTone.WARNING, verification.statusTone)
        assertTrue(verification.isAlert)
        assertEquals("立即处理 →", verification.actionLabel)
        assertEquals(MainNavState.SHORTCUT_TODAY, verification.actionCategory)

        val cooldown = FlameStatusWidgetCalculator.calculate(
            account = sampleAccount(status = AccountStatus.COOLDOWN),
            enabledTargets = targets,
            todayJobs = emptyList(),
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 300,
        )
        assertEquals("冷却保护", cooldown.statusLabel)
        assertEquals(WidgetStatusTone.WARNING, cooldown.statusTone)
        assertTrue(cooldown.isAlert)
        assertEquals("立即处理 →", cooldown.actionLabel)

        val domBroken = FlameStatusWidgetCalculator.calculate(
            account = sampleAccount(status = AccountStatus.DOM_BROKEN),
            enabledTargets = targets,
            todayJobs = emptyList(),
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 300,
        )
        assertEquals("页面异常", domBroken.statusLabel)
        assertEquals(WidgetStatusTone.DANGER, domBroken.statusTone)
        assertTrue(domBroken.isAlert)
        assertEquals("立即处理 →", domBroken.actionLabel)
    }

    @Test
    fun `disabled or paused account renders neutral stopped status`() {
        val now = instantAt(2026, 11, 23, 19, 0)
        val targets = listOf(sampleTarget("t1"))

        val disabled = FlameStatusWidgetCalculator.calculate(
            account = sampleAccount(status = AccountStatus.HEALTHY, enabled = false),
            enabledTargets = targets,
            todayJobs = emptyList(),
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 300,
        )
        assertEquals("已停用", disabled.statusLabel)
        assertEquals(WidgetStatusTone.NEUTRAL, disabled.statusTone)
        assertFalse(disabled.isAlert)
        assertEquals("扫描对账 →", disabled.actionLabel)

        val paused = FlameStatusWidgetCalculator.calculate(
            account = sampleAccount(status = AccountStatus.PAUSED, enabled = true),
            enabledTargets = targets,
            todayJobs = emptyList(),
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 300,
        )
        assertEquals("已停用", paused.statusLabel)
        assertEquals(WidgetStatusTone.NEUTRAL, paused.statusTone)
        assertFalse(paused.isAlert)
    }

    @Test
    fun `compact width threshold hides action pill at 1 to 200dp and shows above 200dp or 0`() {
        val now = instantAt(2026, 11, 23, 19, 0)
        val account = sampleAccount(status = AccountStatus.HEALTHY)
        val targets = listOf(sampleTarget("t1", sendTimeHhmm = "20:30"))

        val at150 = FlameStatusWidgetCalculator.calculate(
            account = account,
            enabledTargets = targets,
            todayJobs = emptyList(),
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 150,
        )
        assertTrue(at150.isCompactWidth)
        assertFalse(at150.showActionPill)

        val at200 = FlameStatusWidgetCalculator.calculate(
            account = account,
            enabledTargets = targets,
            todayJobs = emptyList(),
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 200,
        )
        assertTrue(at200.isCompactWidth)
        assertFalse(at200.showActionPill)

        val at201 = FlameStatusWidgetCalculator.calculate(
            account = account,
            enabledTargets = targets,
            todayJobs = emptyList(),
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 201,
        )
        assertFalse(at201.isCompactWidth)
        assertTrue(at201.showActionPill)

        val unknownWidth = FlameStatusWidgetCalculator.calculate(
            account = account,
            enabledTargets = targets,
            todayJobs = emptyList(),
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 0,
        )
        assertFalse(unknownWidth.isCompactWidth)
        assertTrue(unknownWidth.showActionPill)
    }

    @Test
    fun `empty enabled targets displays fallback headline and send window footer`() {
        val now = instantAt(2026, 11, 23, 19, 0)
        val account = sampleAccount(
            status = AccountStatus.HEALTHY,
            sendWindowStart = "20:00",
            sendWindowEnd = "22:00",
        )

        val snapshot = FlameStatusWidgetCalculator.calculate(
            account = account,
            enabledTargets = emptyList(),
            todayJobs = emptyList(),
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 300,
        )

        assertEquals("暂无启用好友", snapshot.progressHeadline)
        assertEquals("发送窗口 20:00-22:00", snapshot.footerHint)
        assertEquals("运行正常", snapshot.statusLabel)
        assertFalse(snapshot.isAlert)
    }

    @Test
    fun `null account and non daily or previous day jobs are handled safely`() {
        val now = instantAt(2026, 11, 23, 19, 0)

        val missingAccount = FlameStatusWidgetCalculator.calculate(
            account = null,
            enabledTargets = emptyList(),
            todayJobs = emptyList(),
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 300,
        )
        assertEquals(DEFAULT_ACCOUNT_ID, missingAccount.accountId)
        assertEquals("默认抖音号", missingAccount.accountName)
        assertEquals("待登录", missingAccount.statusLabel)
        assertEquals("暂无启用好友", missingAccount.progressHeadline)

        val targets = listOf(
            sampleTarget("t1", enabled = true, sendTimeHhmm = "20:30"),
            sampleTarget("t2", enabled = false, sendTimeHhmm = "20:40"),
            sampleTarget("t3", enabled = true, deletedAt = 123456L, sendTimeHhmm = "20:50"),
        )
        val mixedJobs = listOf(
            sampleJob("t1", businessDate = "2026-11-22", status = JobStatus.SUCCEEDED),
            sampleJob("t1", businessDate = "2026-11-23", slotId = "manual_1", status = JobStatus.SUCCEEDED),
            sampleJob("t2", businessDate = "2026-11-23", status = JobStatus.SUCCEEDED),
        )
        val filtered = FlameStatusWidgetCalculator.calculate(
            account = sampleAccount(status = AccountStatus.HEALTHY),
            enabledTargets = targets,
            todayJobs = mixedJobs,
            unknownReviewCount = 0,
            now = now,
            minWidthDp = 300,
        )
        assertEquals(1, filtered.enabledTargetCount)
        assertEquals(0, filtered.succeededTodayCount)
        assertEquals("今日已完成 0 / 1 位", filtered.progressHeadline)
    }

    @Test
    fun `maskFriendDisplayName handles single char, group, emoji and blank safely`() {
        assertEquals("林**", FlameStatusWidgetCalculator.maskFriendDisplayName("林测试"))
        assertEquals("时**（群）", FlameStatusWidgetCalculator.maskFriendDisplayName("时光小队", isGroup = true))
        assertEquals("友**", FlameStatusWidgetCalculator.maskFriendDisplayName("   "))
        assertEquals("群**", FlameStatusWidgetCalculator.maskFriendDisplayName("", isGroup = true))
        assertEquals("🔥**", FlameStatusWidgetCalculator.maskFriendDisplayName("🔥小火伴"))
    }

    @Test
    fun `recent7Days strip and topFriends ranking compute accurate multi-widget metrics`() {
        val now = instantAt(2026, 11, 24, 18, 0)
        val targets = listOf(
            sampleTarget("t1", nickname = "好友甲").copy(sparkDays = 1031, streakDays = 1),
            sampleTarget("t2", nickname = "群聊乙").copy(
                conversationType = "group",
                sparkDays = 935,
                streakDays = 2,
            ),
            sampleTarget("t3", nickname = "好友丙").copy(sparkLabel = "重燃中 2/3"),
            sampleTarget("t4", nickname = "好友丁").copy(sparkDays = 12),
        )
        val recentJobs = listOf(
            sampleJob("t1", businessDate = "2026-11-18", status = JobStatus.SUCCEEDED),
            sampleJob("t1", businessDate = "2026-11-20", status = JobStatus.FAILED_TERMINAL),
            sampleJob("t1", businessDate = "2026-11-23", status = JobStatus.SUCCEEDED),
            sampleJob("t1", businessDate = "2026-11-24", status = JobStatus.SUCCEEDED),
            sampleJob("t2", businessDate = "2026-11-24", status = JobStatus.SUCCEEDED),
        )

        val snapshot = FlameStatusWidgetCalculator.calculate(
            account = sampleAccount(status = AccountStatus.HEALTHY),
            enabledTargets = targets,
            todayJobs = recentJobs.filter { it.businessDate == "2026-11-24" },
            unknownReviewCount = 0,
            now = now,
            recentJobs7Days = recentJobs,
        )

        assertEquals(1031, snapshot.maxOfficialSparkDays)
        assertEquals("🔥 1031天", snapshot.sparkHighlightText)
        assertEquals(
            listOf(
                WidgetDayStatus.DONE, // 11-18
                WidgetDayStatus.EMPTY, // 11-19
                WidgetDayStatus.ALERT, // 11-20
                WidgetDayStatus.EMPTY, // 11-21
                WidgetDayStatus.EMPTY, // 11-22
                WidgetDayStatus.DONE, // 11-23
                WidgetDayStatus.TODAY_IN_PROGRESS, // 11-24
            ),
            snapshot.recent7Days,
        )
        assertEquals(3, snapshot.topFriends.size)
        assertEquals("好友甲", snapshot.topFriends[0].displayName)
        assertEquals("好**", snapshot.topFriends[0].maskedName)
        assertTrue(snapshot.topFriends[0].isSentToday)
        assertEquals("🔥 1031天", snapshot.topFriends[0].badgeText)
        assertEquals("群聊乙（群）", snapshot.topFriends[1].displayName)
        assertEquals("群**（群）", snapshot.topFriends[1].maskedName)
        assertEquals("好友丁", snapshot.topFriends[2].displayName)
        assertFalse(snapshot.topFriends[2].isSentToday)
    }

    @Test
    fun `widget appearance style resolves surface independently of system night mode`() {
        assertEquals(
            ResolvedWidgetSurface.DARK_CARBON,
            WidgetPreferencesStore.resolveSurface(
                style = WidgetAppearanceStyle.DARK_CARBON,
                appThemeMode = ThemeMode.LIGHT,
                systemDark = false,
            ),
        )
        assertEquals(
            ResolvedWidgetSurface.LIGHT,
            WidgetPreferencesStore.resolveSurface(
                style = WidgetAppearanceStyle.LIGHT_PURE,
                appThemeMode = ThemeMode.DARK,
                systemDark = true,
            ),
        )
        assertEquals(
            ResolvedWidgetSurface.GLASS_DARK,
            WidgetPreferencesStore.resolveSurface(
                style = WidgetAppearanceStyle.GLASS_DARK,
                appThemeMode = ThemeMode.LIGHT,
                systemDark = false,
            ),
        )
        assertEquals(
            ResolvedWidgetSurface.DARK_CARBON,
            WidgetPreferencesStore.resolveSurface(
                style = WidgetAppearanceStyle.FOLLOW_APP,
                appThemeMode = ThemeMode.DARK,
                systemDark = false,
            ),
        )
        assertEquals(
            ResolvedWidgetSurface.LIGHT,
            WidgetPreferencesStore.resolveSurface(
                style = WidgetAppearanceStyle.FOLLOW_APP,
                appThemeMode = ThemeMode.SYSTEM,
                systemDark = false,
            ),
        )
    }

    @Test
    fun `widget refresh state formats spinning and receipt copy cleanly`() {
        assertFalse(WidgetRefreshState.IDLE.isRefreshing)
        assertFalse(WidgetRefreshState.IDLE.isJustUpdated)
        assertEquals("", WidgetRefreshState.IDLE.statusHintText)
        assertEquals("", WidgetRefreshState.IDLE.compactStatusHintText)

        val spinning = WidgetRefreshState(phase = WidgetRefreshPhase.REFRESHING)
        assertTrue(spinning.isRefreshing)
        assertFalse(spinning.isJustUpdated)
        assertEquals("同步中…", spinning.statusHintText)
        assertEquals("同步中…", spinning.compactStatusHintText)

        val receipt = WidgetRefreshState(phase = WidgetRefreshPhase.JUST_UPDATED, updatedAtHhmm = "11:28")
        assertFalse(receipt.isRefreshing)
        assertTrue(receipt.isJustUpdated)
        assertEquals("✓ 已刷新 11:28", receipt.statusHintText)
        assertEquals("✓ 11:28", receipt.compactStatusHintText)

        val receiptFallback = WidgetRefreshState(phase = WidgetRefreshPhase.JUST_UPDATED, updatedAtHhmm = "  ")
        assertEquals("✓ 已刷新", receiptFallback.statusHintText)
        assertEquals("✓ 已刷新", receiptFallback.compactStatusHintText)
    }

    @Test
    fun `topFriends does not render no-spark label as spark status and ranks real official sparks higher`() {
        val now = instantAt(2026, 11, 26, 18, 0)
        val targets = listOf(
            sampleTarget("t_none", nickname = "无火花好友").copy(sparkLabel = "无火花", streakDays = 10),
            sampleTarget("t_rekindle", nickname = "重燃中好友").copy(sparkLabel = "重燃中 2/3", streakDays = 1),
            sampleTarget("t_days", nickname = "天数好友").copy(sparkDays = 50, streakDays = 2),
        )
        val snapshot = FlameStatusWidgetCalculator.calculate(
            account = sampleAccount(status = AccountStatus.HEALTHY),
            enabledTargets = targets,
            todayJobs = emptyList(),
            unknownReviewCount = 0,
            now = now,
        )

        assertEquals(listOf("天数好友", "重燃中好友", "无火花好友"), snapshot.topFriends.map { it.displayName })
        assertEquals("🔥 50天", snapshot.topFriends[0].badgeText)
        assertEquals("待续 · 重燃中 2/3", snapshot.topFriends[1].badgeText)
        assertEquals("待发送", snapshot.topFriends[2].badgeText)
    }
}

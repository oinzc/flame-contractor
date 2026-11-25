package io.github.oinzc.flamecontractor.ui.setup

import android.content.SharedPreferences
import io.github.oinzc.flamecontractor.core.setup.SetupOnboardingState
import io.github.oinzc.flamecontractor.core.setup.SetupOnboardingStore
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import io.github.oinzc.flamecontractor.ui.settings.OemVendorFamily
import io.github.oinzc.flamecontractor.ui.settings.RomSurvivalChecker
import io.github.oinzc.flamecontractor.ui.settings.SurvivalCheckId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupReadinessModelsTest {

    @Test
    fun detectVendorFamilyRecognizesAllMajorAndroidOems() {
        assertEquals(OemVendorFamily.XIAOMI, RomSurvivalChecker.detectVendorFamily("Xiaomi", "Xiaomi"))
        assertEquals(OemVendorFamily.XIAOMI, RomSurvivalChecker.detectVendorFamily("Xiaomi", "Redmi"))
        assertEquals(OemVendorFamily.XIAOMI, RomSurvivalChecker.detectVendorFamily("Xiaomi", "POCO"))
        assertEquals(OemVendorFamily.XIAOMI, RomSurvivalChecker.detectVendorFamily("blackshark", "blackshark"))

        assertEquals(OemVendorFamily.HUAWEI_HONOR, RomSurvivalChecker.detectVendorFamily("HUAWEI", "HUAWEI"))
        assertEquals(OemVendorFamily.HUAWEI_HONOR, RomSurvivalChecker.detectVendorFamily("HONOR", "HONOR"))

        assertEquals(OemVendorFamily.OPPO_ONEPLUS_REALME, RomSurvivalChecker.detectVendorFamily("OPPO", "OPPO"))
        assertEquals(OemVendorFamily.OPPO_ONEPLUS_REALME, RomSurvivalChecker.detectVendorFamily("OnePlus", "OnePlus"))
        assertEquals(OemVendorFamily.OPPO_ONEPLUS_REALME, RomSurvivalChecker.detectVendorFamily("realme", "realme"))

        assertEquals(OemVendorFamily.VIVO_IQOO, RomSurvivalChecker.detectVendorFamily("vivo", "vivo"))
        assertEquals(OemVendorFamily.VIVO_IQOO, RomSurvivalChecker.detectVendorFamily("vivo", "iQOO"))

        assertEquals(OemVendorFamily.SAMSUNG, RomSurvivalChecker.detectVendorFamily("samsung", "samsung"))
        assertEquals(OemVendorFamily.MEIZU, RomSurvivalChecker.detectVendorFamily("Meizu", "meizu"))

        assertEquals(OemVendorFamily.GENERIC_ANDROID, RomSurvivalChecker.detectVendorFamily("Google", "google"))
        assertEquals(OemVendorFamily.GENERIC_ANDROID, RomSurvivalChecker.detectVendorFamily("Nothing", "Nothing"))
    }

    @Test
    fun romSurvivalSnapshotIncludesOverlayAutoReturnAndVendorSpecificSecondaryActions() {
        val xiaomiSnapshot = RomSurvivalChecker.buildSnapshot(
            checkedAtEpochMs = 1_000L,
            isXiaomiOrHyperOs = true,
            manufacturer = "Xiaomi",
            notificationsGranted = true,
            batteryOptimizationIgnored = true,
            exactAlarmAllowed = true,
            autostartGuidanceConfirmed = false,
            overlayAutoReturnAllowed = false,
            vendorFamily = OemVendorFamily.XIAOMI,
        )
        assertEquals(5, xiaomiSnapshot.totalCount)
        assertEquals(3, xiaomiSnapshot.passedCount)
        assertTrue(xiaomiSnapshot.coreAllPassed)

        val xiaomiOverlay = xiaomiSnapshot.items.first { it.id == SurvivalCheckId.OVERLAY_AUTO_RETURN }
        assertFalse(xiaomiOverlay.passed)
        assertEquals("后台弹出界面", xiaomiOverlay.secondaryActionLabel)

        val samsungSnapshot = RomSurvivalChecker.buildSnapshot(
            checkedAtEpochMs = 2_000L,
            isXiaomiOrHyperOs = false,
            manufacturer = "samsung",
            notificationsGranted = true,
            batteryOptimizationIgnored = true,
            exactAlarmAllowed = true,
            autostartGuidanceConfirmed = true,
            overlayAutoReturnAllowed = true,
            vendorFamily = OemVendorFamily.SAMSUNG,
        )
        assertEquals(5, samsungSnapshot.passedCount)
        val samsungOverlay = samsungSnapshot.items.first { it.id == SurvivalCheckId.OVERLAY_AUTO_RETURN }
        assertTrue(samsungOverlay.passed)
        assertNull(samsungOverlay.secondaryActionLabel)
    }

    @Test
    fun setupReadinessBuilderAggregatesAllSevenItemsAndPrioritizesRequiredNextPending() {
        val partialSurvival = RomSurvivalChecker.buildSnapshot(
            checkedAtEpochMs = 5_000L,
            isXiaomiOrHyperOs = false,
            manufacturer = "OPPO",
            notificationsGranted = true,
            batteryOptimizationIgnored = false,
            exactAlarmAllowed = true,
            autostartGuidanceConfirmed = false,
            overlayAutoReturnAllowed = false,
            vendorFamily = OemVendorFamily.OPPO_ONEPLUS_REALME,
        )
        val partial = SetupReadinessBuilder.build(
            survivalSnapshot = partialSurvival,
            accountStatus = AccountStatus.PENDING_LOGIN,
            enabledTargetCount = 0,
        )

        assertEquals(7, partial.totalCount)
        assertEquals(2, partial.completedCount)
        assertEquals(6, partial.requiredTotalCount)
        assertEquals(2, partial.requiredCompletedCount)
        assertFalse(partial.allCompleted)
        assertFalse(partial.allRequiredCompleted)
        // AUTO_RETURN_OVERLAY 是 RECOMMENDED，因此优先提示第一个未完成的 REQUIRED 项（BATTERY_OPTIMIZATION）
        assertEquals(SetupItemId.BATTERY_OPTIMIZATION, partial.nextPendingItem?.id)
        assertTrue(partial.itemById(SetupItemId.OEM_BACKGROUND_AUTOSTART).needsManualConfirmation)

        // 当全部必选项通过但推荐悬浮窗未开时，allRequiredCompleted=true，nextPendingItem=AUTO_RETURN_OVERLAY
        val requiredDoneSurvival = RomSurvivalChecker.buildSnapshot(
            checkedAtEpochMs = 6_000L,
            isXiaomiOrHyperOs = false,
            manufacturer = "Google",
            notificationsGranted = true,
            batteryOptimizationIgnored = true,
            exactAlarmAllowed = true,
            autostartGuidanceConfirmed = true,
            overlayAutoReturnAllowed = false,
            vendorFamily = OemVendorFamily.GENERIC_ANDROID,
        )
        val requiredDone = SetupReadinessBuilder.build(
            survivalSnapshot = requiredDoneSurvival,
            accountStatus = AccountStatus.HEALTHY,
            enabledTargetCount = 3,
        )
        assertEquals(6, requiredDone.completedCount)
        assertTrue(requiredDone.allRequiredCompleted)
        assertFalse(requiredDone.allCompleted)
        assertEquals(SetupItemId.AUTO_RETURN_OVERLAY, requiredDone.nextPendingItem?.id)
        assertFalse(requiredDone.itemById(SetupItemId.OEM_BACKGROUND_AUTOSTART).needsManualConfirmation)

        // 全 7 项完成
        val allDone = SetupReadinessBuilder.build(
            survivalSnapshot = requiredDoneSurvival.copy(overlayAutoReturnAllowed = true),
            accountStatus = AccountStatus.HEALTHY,
            enabledTargetCount = 2,
        )
        assertEquals(7, allDone.completedCount)
        assertTrue(allDone.allCompleted)
        assertNull(allDone.nextPendingItem)
    }

    @Test
    fun setupOnboardingStoreAutoLaunchPolicyAndSharedPreferencesRoundTrip() {
        // 1. 全新用户自动弹出
        assertTrue(
            SetupOnboardingStore.shouldAutoLaunchOnboarding(
                onboardingCompleted = false,
                accountStatus = AccountStatus.PENDING_LOGIN,
                enabledTargetCount = 0,
                allCoreSystemReady = false,
            ),
        )
        // 2. 老用户已全部配置就绪，升级后不强弹打扰
        assertFalse(
            SetupOnboardingStore.shouldAutoLaunchOnboarding(
                onboardingCompleted = false,
                accountStatus = AccountStatus.HEALTHY,
                enabledTargetCount = 4,
                allCoreSystemReady = true,
            ),
        )
        // 3. 已经完成或跳过引导，不再自动弹出
        assertFalse(
            SetupOnboardingStore.shouldAutoLaunchOnboarding(
                onboardingCompleted = true,
                accountStatus = AccountStatus.PENDING_LOGIN,
                enabledTargetCount = 0,
                allCoreSystemReady = false,
            ),
        )

        // 4. SharedPreferences 持久化读写验证
        val prefs = InMemorySharedPreferences()
        val store1 = SetupOnboardingStore.fromSharedPreferences(prefs)
        assertEquals(SetupOnboardingState(onboardingCompleted = false, lastStepIndex = 0), store1.state.value)

        store1.setLastStepIndex(3)
        store1.markCompleted()

        val store2 = SetupOnboardingStore.fromSharedPreferences(prefs)
        assertTrue(store2.state.value.onboardingCompleted)
        assertEquals(3, store2.state.value.lastStepIndex)

        store2.resetForReplay()
        val store3 = SetupOnboardingStore.fromSharedPreferences(prefs)
        assertFalse(store3.state.value.onboardingCompleted)
        assertEquals(0, store3.state.value.lastStepIndex)
    }

    @Test
    fun onboardingGatePolicyLocksStepsUntilPriorCorePermissionsCompleted() {
        val noneReady = SetupReadinessBuilder.build(
            survivalSnapshot = RomSurvivalChecker.buildSnapshot(
                checkedAtEpochMs = 1_000L,
                isXiaomiOrHyperOs = true,
                manufacturer = "Xiaomi",
                notificationsGranted = false,
                batteryOptimizationIgnored = false,
                exactAlarmAllowed = false,
                autostartGuidanceConfirmed = false,
                overlayAutoReturnAllowed = false,
                vendorFamily = OemVendorFamily.XIAOMI,
            ),
            accountStatus = AccountStatus.PENDING_LOGIN,
            enabledTargetCount = 0,
        )
        // 通知未开时只解锁第 0 步，不能跳到第 1..5 步
        assertEquals(0, OnboardingGatePolicy.maxUnlockedStepIndex(noneReady))
        assertTrue(OnboardingGatePolicy.canSelectStep(0, noneReady))
        assertFalse(OnboardingGatePolicy.canSelectStep(1, noneReady))
        assertEquals(0, OnboardingGatePolicy.clampInitialStepIndex(4, noneReady))
        assertEquals(0, OnboardingGatePolicy.completedCoreStepCount(noneReady))
        assertTrue(OnboardingGatePolicy.missingCorePermissionTitles(noneReady).contains("系统通知权限"))

        // 通知与悬浮窗完成后解锁到第 2 步（电池+闹钟），可回看第 0/1 步，不能跳到第 3 步
        val firstTwoReady = SetupReadinessBuilder.build(
            survivalSnapshot = RomSurvivalChecker.buildSnapshot(
                checkedAtEpochMs = 2_000L,
                isXiaomiOrHyperOs = true,
                manufacturer = "Xiaomi",
                notificationsGranted = true,
                batteryOptimizationIgnored = true,
                exactAlarmAllowed = false,
                autostartGuidanceConfirmed = false,
                overlayAutoReturnAllowed = true,
                vendorFamily = OemVendorFamily.XIAOMI,
            ),
            accountStatus = AccountStatus.PENDING_LOGIN,
            enabledTargetCount = 0,
        )
        assertEquals(2, OnboardingGatePolicy.maxUnlockedStepIndex(firstTwoReady))
        assertTrue(OnboardingGatePolicy.canSelectStep(0, firstTwoReady))
        assertTrue(OnboardingGatePolicy.canSelectStep(1, firstTwoReady))
        assertTrue(OnboardingGatePolicy.canSelectStep(2, firstTwoReady))
        assertFalse(OnboardingGatePolicy.canSelectStep(3, firstTwoReady))
        assertEquals(2, OnboardingGatePolicy.completedCoreStepCount(firstTwoReady))

        // 前 4 步核心权限全部就绪后，解锁登录与选择好友步（索引 5）
        val allCoreReady = SetupReadinessBuilder.build(
            survivalSnapshot = RomSurvivalChecker.buildSnapshot(
                checkedAtEpochMs = 3_000L,
                isXiaomiOrHyperOs = true,
                manufacturer = "Xiaomi",
                notificationsGranted = true,
                batteryOptimizationIgnored = true,
                exactAlarmAllowed = true,
                autostartGuidanceConfirmed = true,
                overlayAutoReturnAllowed = true,
                vendorFamily = OemVendorFamily.XIAOMI,
            ),
            accountStatus = AccountStatus.PENDING_LOGIN,
            enabledTargetCount = 0,
        )
        assertEquals(OnboardingStep.entries.lastIndex, OnboardingGatePolicy.maxUnlockedStepIndex(allCoreReady))
        assertTrue(OnboardingGatePolicy.canSelectStep(4, allCoreReady))
        assertTrue(OnboardingGatePolicy.canSelectStep(5, allCoreReady))
        assertEquals(4, OnboardingGatePolicy.completedCoreStepCount(allCoreReady))
        assertTrue(OnboardingGatePolicy.missingCorePermissionTitles(allCoreReady).isEmpty())
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = linkedMapOf<String, Any?>()
            private val removals = linkedSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) staged[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) removals += key
            }

            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                staged.forEach { (k, v) -> data[k] = v }
            }
        }
    }
}

package io.github.oinzc.flamecontractor.core.setup

import android.content.Context
import android.content.SharedPreferences
import io.github.oinzc.flamecontractor.data.db.AccountStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SetupOnboardingState(
    val onboardingCompleted: Boolean = false,
    val lastStepIndex: Int = 0,
)

/**
 * 持久化新手引导完成态与最后停靠步骤。
 *
 * - 首次启动时仅对未完成引导且账号或核心权限未就绪的用户自动弹出，不打扰已配置完毕的老用户；
 * - 用户可随时在「初始化与权限清单」或今日页提醒条中重新进入分步引导。
 */
class SetupOnboardingStore(
    initialState: SetupOnboardingState = SetupOnboardingState(),
    private val onPersist: (SetupOnboardingState) -> Unit = {},
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<SetupOnboardingState> = _state.asStateFlow()

    fun setLastStepIndex(stepIndex: Int) {
        val clamped = stepIndex.coerceAtLeast(0)
        val next = _state.value.copy(lastStepIndex = clamped)
        if (_state.value != next) {
            _state.value = next
        }
        onPersist(next)
    }

    fun markCompleted() {
        val next = _state.value.copy(onboardingCompleted = true)
        if (_state.value != next) {
            _state.value = next
        }
        onPersist(next)
    }

    fun resetForReplay() {
        val next = SetupOnboardingState(onboardingCompleted = false, lastStepIndex = 0)
        if (_state.value != next) {
            _state.value = next
        }
        onPersist(next)
    }

    companion object {
        private const val PREFS_NAME = "flame_setup_onboarding_prefs"
        private const val KEY_COMPLETED = "onboarding_completed"
        private const val KEY_LAST_STEP = "last_step_index"

        fun fromContext(context: Context): SetupOnboardingStore {
            val prefs = (context.applicationContext ?: context)
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return fromSharedPreferences(prefs)
        }

        fun fromSharedPreferences(prefs: SharedPreferences): SetupOnboardingStore {
            val completed = prefs.getBoolean(KEY_COMPLETED, false)
            val lastStep = prefs.getInt(KEY_LAST_STEP, 0).coerceAtLeast(0)
            return SetupOnboardingStore(
                initialState = SetupOnboardingState(
                    onboardingCompleted = completed,
                    lastStepIndex = lastStep,
                ),
                onPersist = { state ->
                    prefs.edit()
                        .putBoolean(KEY_COMPLETED, state.onboardingCompleted)
                        .putInt(KEY_LAST_STEP, state.lastStepIndex)
                        .apply()
                },
            )
        }

        /**
         * 判定冷启动后是否应自动弹出新手引导。
         *
         * 老用户若已完成登录、至少启用 1 位好友且核心系统保活项全部通过，则升级后不强弹打扰，
         * 仍可在今日页进度条与设置置顶清单中查看新增项（如自动跳回悬浮窗）。
         */
        fun shouldAutoLaunchOnboarding(
            onboardingCompleted: Boolean,
            accountStatus: AccountStatus,
            enabledTargetCount: Int,
            allCoreSystemReady: Boolean,
        ): Boolean {
            if (onboardingCompleted) return false
            return accountStatus == AccountStatus.PENDING_LOGIN ||
                enabledTargetCount <= 0 ||
                !allCoreSystemReady
        }
    }
}

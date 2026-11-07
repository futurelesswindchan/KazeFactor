package io.github.futurelesswindchan.kazefactor.engine.loop

/**
 * 刷新状态机的对外状态（架构方案第 4 节）。
 *
 * 通过 `StateFlow<EngineState>` 推给悬浮窗，UI 按此渲染小球/面板/运行中/命中。
 */
sealed class EngineState {

    /** 空闲，还没开始。 */
    object Idle : EngineState() {
        override fun toString() = "Idle"
    }

    /** 正在校准（自动校准或手动引导后的吸附验证）。 */
    object Calibrating : EngineState() {
        override fun toString() = "Calibrating"
    }

    /**
     * 正在刷新。
     *
     * @property refreshCount 已刷新次数（点击了几次）
     * @property failureCount 识别失败次数（包含 UNCERTAIN 和 Unknown）
     */
    data class Running(val refreshCount: Int = 0, val failureCount: Int = 0) : EngineState()

    /**
     * 命中目标配方。
     *
     * @property factorIds 4 个槽位的因子 id
     * @property refreshCount 达成时的总刷新次数
     */
    data class Hit(val factorIds: List<Int>, val refreshCount: Int) : EngineState()

    /**
     * 暂停。
     *
     * @property reason 暂停原因
     * @property refreshCount 暂停时的总刷新次数
     */
    data class Paused(val reason: PauseReason, val refreshCount: Int = 0) : EngineState()

    /** 用户主动停止，或从 [Hit] / [Paused] 确认结束。 */
    object Stopped : EngineState() {
        override fun toString() = "Stopped"
    }
}

/** 暂停原因枚举，用于 UI 展示不同的提示文案。 */
enum class PauseReason {
    /** 校准失败：帮助图标找不到或面板边框残差超标。 */
    CALIBRATION_FAILED,

    /** 开关关闭，4 个槽位都是问号。 */
    TOGGLE_OFF,

    /** 不在因子界面（切出游戏、弹对话框、通知栏被拉下、开关正在切换动画）。 */
    NOT_FACTOR_SCREEN,

    /** 连续 N 次识别失败（UNCERTAIN / Unknown）。 */
    CONSECUTIVE_FAILURES,

    /** 点击后连续 3 次识别出完全相同的结果。 */
    TAP_INEFFECTIVE,

    /** 达到总次数上限。 */
    MAX_REFRESH_COUNT,

    /** 快速核验失败（位置档案与当前画面不一致）。 */
    VERIFY_FAILED,

    /** 用户主动暂停。 */
    USER_PAUSED
}

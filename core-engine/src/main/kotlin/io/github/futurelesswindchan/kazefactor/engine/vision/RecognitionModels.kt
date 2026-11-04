package io.github.futurelesswindchan.kazefactor.engine.vision

/**
 * 单个槽位的识别结果。
 */
sealed class SlotResult {
    /** 槽位显示问号，即本局尚未刷新过。 */
    object Question : SlotResult() {
        override fun toString() = "Question"
    }

    /** 唯一确定为 [factorId] 对应的因子。 */
    data class Factor(val factorId: Int) : SlotResult()

    /**
     * 能匹配到因子，但几个候选距离太近、分不开。
     *
     * 后续由 `Decision` 展开所有可能组合再判断，不一定要停（架构方案第 3.2 节）。
     *
     * @property candidateIds 距离在「最优 + 差距阈值」以内的因子 id，至少 2 个
     */
    data class Ambiguous(val candidateIds: Set<Int>) : SlotResult()

    /** 既不像问号，也不像任何候选因子。 */
    object Unknown : SlotResult() {
        override fun toString() = "Unknown"
    }
}

/**
 * 画面状态（架构方案第 3.3 节）。
 *
 * 由校准结果 + 开关检测 + 槽位识别综合判定，供刷新状态机决定下一步动作。
 */
enum class ScreenState {
    /**
     * 因子界面，开关为开。
     *
     * 判定条件：面板边框残差和槽位验证通过，开关检测为开。正常识别 4 个槽位。
     */
    FACTOR_SCREEN_ON,

    /**
     * 因子界面，开关为关，4 个槽位都是问号。
     *
     * 不点击刷新按钮，提示用户打开开关。开关和问号两者必须一致，
     * 不一致时按 [NOT_FACTOR_SCREEN] 处理（架构方案第 3.3 节）。
     */
    FACTOR_SCREEN_OFF,

    /**
     * 不在因子界面（校准失败、开关状态不确定、或开关/问号不一致）。
     *
     * 例如切出游戏、弹出对话框、通知栏被拉下、开关正在切换的动画。
     */
    NOT_FACTOR_SCREEN;

    companion object {
        /**
         * 校准已通过时，按开关检测和槽位识别结果判定画面状态（架构方案第 3.3 节）。
         *
         * 校准失败（面板边框找不到或残差超标、槽位验证不通过）时调用方直接取 [NOT_FACTOR_SCREEN]，不必调用本函数。
         *
         * | 开关 | 槽位 | 结果 |
         * | --- | --- | --- |
         * | [ToggleState.ON] | 任意 | [FACTOR_SCREEN_ON] |
         * | [ToggleState.OFF] | 4 个都是问号 | [FACTOR_SCREEN_OFF] |
         * | [ToggleState.OFF] | 不全是问号 | [NOT_FACTOR_SCREEN]（两种证据不一致） |
         * | [ToggleState.UNCERTAIN] | 任意 | [NOT_FACTOR_SCREEN]（可能是切换动画或遮挡） |
         *
         * NOTE: 开关为开、却有槽位是问号的情况仍返回 [FACTOR_SCREEN_ON]。这是过渡帧之类的异常画面，
         * 由第 4 步的 `Decision` 按 UNCERTAIN 处理（不点击、计入识别失败），不在这里改判。
         *
         * @param toggle [ToggleDetector.detect] 的结果
         * @param frame 按同一个校准识别出的 4 个槽位
         */
        fun classify(toggle: ToggleState, frame: FrameResult): ScreenState = when (toggle) {
            ToggleState.ON -> FACTOR_SCREEN_ON
            ToggleState.OFF -> if (frame.isAllQuestion) FACTOR_SCREEN_OFF else NOT_FACTOR_SCREEN
            ToggleState.UNCERTAIN -> NOT_FACTOR_SCREEN
        }
    }
}

/** 挑战因子开关的检测结果，由 [ToggleDetector] 给出。 */
enum class ToggleState {
    /** 开关打开：采样区内绿色像素占比高于开阈值。 */
    ON,

    /** 开关关闭：绿色像素占比低于关阈值。 */
    OFF,

    /** 占比介于两个阈值之间，或采样区超出画面。可能是开关切换动画或画面被遮挡。 */
    UNCERTAIN
}

/**
 * 一帧画面的识别结果。
 *
 * @property slots 4 个槽位的结果，下标 0~3 对应第 1~4 槽
 */
data class FrameResult(val slots: List<SlotResult>) {
    init {
        require(slots.size == 4) { "必须正好有 4 个槽位结果，实际 ${slots.size} 个" }
    }

    /** 4 个槽位都是问号。 */
    val isAllQuestion: Boolean get() = slots.all { it == SlotResult.Question }

    /** 4 个槽位都已唯一确定，返回按槽位排列的因子 id；否则返回 null。 */
    fun factorIdsOrNull(): List<Int>? =
        slots.map { (it as? SlotResult.Factor)?.factorId ?: return null }
}

/**
 * 屏幕上的整数像素矩形。
 *
 * @property x 左上角横坐标
 * @property y 左上角纵坐标
 * @property width 宽度
 * @property height 高度
 */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    /** 四边各向外扩 [margin] 像素。 */
    fun expand(margin: Int): PixelRect =
        PixelRect(x - margin, y - margin, width + 2 * margin, height + 2 * margin)
}

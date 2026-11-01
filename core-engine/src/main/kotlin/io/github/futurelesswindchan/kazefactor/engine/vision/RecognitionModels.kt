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
 * 一帧画面的识别结果。
 *
 * TODO(第 9 节第 3 步): 加入基于骰子锚点的 `ScreenState`（因子界面 / 未刷新 / 非因子界面）。
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

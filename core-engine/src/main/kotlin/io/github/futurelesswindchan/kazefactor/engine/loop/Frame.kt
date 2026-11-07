package io.github.futurelesswindchan.kazefactor.engine.loop

import io.github.futurelesswindchan.kazefactor.engine.image.ArgbImage

/**
 * 一帧截图的抽象（架构方案第 2.1 节）。
 *
 * 正式实现（第 5 步的 `A11yScreenCapturer`）按需从 `HardwareBuffer` 读取像素，
 * 只裁取用得到的区域，不必把整屏 260 万像素转成 IntArray。测试中直接包一张 [ArgbImage]。
 *
 * NOTE: 本步的 [RefreshEngine] 仍按 `crop(0, 0, width, height)` 取整帧交给识别层，
 * 因为 `Calibrator`、`FrameAnalyzer` 目前都接收整帧。第 5 步真机测速后再决定是否改成逐区域读取。
 */
interface Frame {
    /** 截图宽度。 */
    val width: Int

    /** 截图高度。 */
    val height: Int

    /**
     * 裁取屏幕矩形 ([x], [y], [w], [h]) 内的像素。
     *
     * @throws IllegalArgumentException 区域超出画面或尺寸非正时
     */
    fun crop(x: Int, y: Int, w: Int, h: Int): ArgbImage

    /** 释放底层资源（如 HardwareBuffer）。状态机每帧处理完都会调用，重复调用应无副作用。 */
    fun close()
}

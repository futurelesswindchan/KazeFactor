package io.github.futurelesswindchan.kazefactor.engine.vision

import io.github.futurelesswindchan.kazefactor.engine.image.blueOf
import io.github.futurelesswindchan.kazefactor.engine.image.greenOf
import io.github.futurelesswindchan.kazefactor.engine.image.redOf
import io.github.futurelesswindchan.kazefactor.engine.testutil.TestFixtures
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * 锚点测量（第 9 节第 3 步的准备工作）。只出报告不断言。
 *
 * 架构方案第 0 节只给了 10 px 精度的粗定位，校准要求误差 ≤ 2 px，所以要先量出骰子和循环箭头的精确矩形。
 * 做法：对 47 张截图逐像素求均值和标准差。估计每个区域的背景色，把「与背景差异大且在所有截图里都稳定」
 * 的像素当作前景，求连通块和外框。
 *
 * 输出到 `core-engine/build/reports/recognition/`：
 * - `anchor-survey.txt`：字符图和前景块外框列表
 * - `anchor-<区域>-mean.png` / `-std.png`：均值图和标准差图（放大 3 倍），给人核对用
 */
class AnchorSurveyTest {

    /** 待测区域，坐标来自架构方案第 0 节的粗定位，四周多留了余量。 */
    private class Region(val name: String, val x: Int, val y: Int, val width: Int, val height: Int, val cell: Int)

    private val regions = listOf(
        Region("title", 1230, 115, 350, 85, cell = 2),
        Region("refresh", 1060, 375, 650, 115, cell = 4)
    )

    @Test
    fun `测量锚点区域`() {
        val labels = TestFixtures.labels
        val stats = regions.associateWith { RegionStats(it.width, it.height) }
        val groupStats = regions.associateWith { r ->
            linkedMapOf(GROUP_QUESTION to RegionStats(r.width, r.height), GROUP_REFRESHED to RegionStats(r.width, r.height))
        }
        for (label in labels) {
            val frame = TestFixtures.loadScreenshot(label.file)
            val group = if (label.expected.all { it == SlotResult.Question }) GROUP_QUESTION else GROUP_REFRESHED
            for ((r, s) in stats) {
                s.add(frame.pixels, frame.width, r.x, r.y)
                groupStats.getValue(r).getValue(group).add(frame.pixels, frame.width, r.x, r.y)
            }
        }

        val outDir = File("build/reports/recognition").apply { mkdirs() }
        val report = StringBuilder("# 锚点测量报告（${labels.size} 张截图）\n")
        report.appendLine("图例：'#' 稳定前景（std ≤ $STABLE_STD 且与背景差 > $FG_DIFF），'+' 不稳定前景，'.' 背景但不稳定，' ' 稳定背景")
        report.appendLine("字符图每格代表 cell × cell 像素，格内只要有一个像素满足条件就显示对应符号（优先级 # > + > .）")

        for ((r, s) in stats) {
            s.finish()
            val bg = s.estimateBackground()
            report.appendLine()
            report.appendLine("## 区域 ${r.name}：x ${r.x}..${r.x + r.width - 1}，y ${r.y}..${r.y + r.height - 1}，cell = ${r.cell}")
            report.appendLine("背景色估计：#%06X".format(bg and 0xFFFFFF))
            report.appendLine("std 分布：中位数 ${fmt(s.stdPercentile(0.5))}，90% ${fmt(s.stdPercentile(0.9))}，最大 ${fmt(s.stdPercentile(1.0))}")
            report.append(asciiMap(r, s, bg))

            report.appendLine("稳定前景连通块（全部截图；相距 ≤ $MERGE_GAP px 合并，按 x 排序，面积 ≥ $MIN_AREA）：")
            appendComponents(report, r, s, bg)

            // 分组统计：区分「问号截图内部不稳定」和「两组之间外观不同」
            for ((groupName, gs) in groupStats.getValue(r)) {
                gs.finish()
                report.appendLine(
                    "分组 $groupName（${gs.count} 张）：std 中位数 ${fmt(gs.stdPercentile(0.5))}，" +
                        "90% ${fmt(gs.stdPercentile(0.9))}，最大 ${fmt(gs.stdPercentile(1.0))}"
                )
                appendComponents(report, r, gs, bg)
            }
            report.append(groupDiffSummary(r, groupStats.getValue(r)))

            writePng(File(outDir, "anchor-${r.name}-mean.png"), r.width, r.height) { i -> s.mean[i] }
            writePng(File(outDir, "anchor-${r.name}-std.png"), r.width, r.height) { i ->
                val v = min(255, (s.std[i] * 6).toInt())
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }

        File(outDir, "anchor-survey.txt").writeText(report.toString(), Charsets.UTF_8)
        println(report)
    }

    /** 区域内逐像素的均值与标准差累加器。 */
    private class RegionStats(val width: Int, val height: Int) {
        private val n = width * height
        private val sum = Array(3) { DoubleArray(n) }
        private val sumSq = Array(3) { DoubleArray(n) }

        /** 已累加的截图数。 */
        var count = 0
            private set

        /** 均值色（0xFFRRGGBB）。 */
        val mean = IntArray(n)

        /** 三通道标准差的平均值。 */
        val std = DoubleArray(n)

        fun add(pixels: IntArray, frameWidth: Int, left: Int, top: Int) {
            for (y in 0 until height) {
                val row = (top + y) * frameWidth + left
                for (x in 0 until width) {
                    val p = pixels[row + x]
                    val i = y * width + x
                    val c = intArrayOf(redOf(p), greenOf(p), blueOf(p))
                    for (k in 0..2) {
                        sum[k][i] += c[k].toDouble()
                        sumSq[k][i] += c[k].toDouble() * c[k]
                    }
                }
            }
            count++
        }

        fun finish() {
            for (i in 0 until n) {
                var s = 0.0
                val m = IntArray(3)
                for (k in 0..2) {
                    val mu = sum[k][i] / count
                    m[k] = mu.toInt().coerceIn(0, 255)
                    s += sqrt(max(0.0, sumSq[k][i] / count - mu * mu))
                }
                mean[i] = (0xFF shl 24) or (m[0] shl 16) or (m[1] shl 8) or m[2]
                std[i] = s / 3
            }
        }

        /** 把均值色按每通道 16 级量化，取像素最多的一档的平均色作为背景。 */
        fun estimateBackground(): Int {
            val bins = HashMap<Int, MutableList<Int>>()
            for (c in mean) {
                val key = ((redOf(c) shr 4) shl 8) or ((greenOf(c) shr 4) shl 4) or (blueOf(c) shr 4)
                bins.getOrPut(key) { mutableListOf() }.add(c)
            }
            val top = bins.values.maxByOrNull { it.size }!!
            return averageColor(top)
        }

        fun stdPercentile(q: Double): Double {
            val sorted = std.sortedArray()
            return sorted[((sorted.size - 1) * q).toInt()]
        }
    }

    private class Component(var minX: Int, var minY: Int, var maxX: Int, var maxY: Int, var area: Int, var meanColor: Int)

    private fun isForeground(s: RegionStats, bg: Int, i: Int) = colorDiff(s.mean[i], bg) > FG_DIFF

    private fun asciiMap(r: Region, s: RegionStats, bg: Int): String {
        val sb = StringBuilder()
        val cols = (r.width + r.cell - 1) / r.cell
        // 列号标尺：每 10 格标一次绝对 x 坐标的后三位
        val ruler = CharArray(cols) { ' ' }
        for (c in 0 until cols step 10) {
            val label = ((r.x + c * r.cell) % 1000).toString().padStart(3, '0')
            for (k in label.indices) if (c + k < cols) ruler[c + k] = label[k]
        }
        sb.append("      ").appendLine(String(ruler))
        for (cy in 0 until (r.height + r.cell - 1) / r.cell) {
            sb.append((r.y + cy * r.cell).toString().padStart(5)).append(' ')
            for (cx in 0 until cols) {
                var level = 0
                for (dy in 0 until r.cell) for (dx in 0 until r.cell) {
                    val x = cx * r.cell + dx
                    val y = cy * r.cell + dy
                    if (x >= r.width || y >= r.height) continue
                    val i = y * r.width + x
                    val fg = isForeground(s, bg, i)
                    val stable = s.std[i] <= STABLE_STD
                    level = max(level, if (fg && stable) 3 else if (fg) 2 else if (!stable) 1 else 0)
                }
                sb.append(" .+#"[level])
            }
            sb.appendLine()
        }
        return sb.toString()
    }

    /** 稳定前景像素的连通块，先按 [MERGE_GAP] 膨胀再求 8 连通，外框只算原始前景像素。 */
    private fun components(s: RegionStats, bg: Int): List<Component> {
        val w = s.width
        val h = s.height
        val fg = BooleanArray(w * h) { isForeground(s, bg, it) && s.std[it] <= STABLE_STD }
        val dilated = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!fg[y * w + x]) continue
            for (dy in -MERGE_GAP..MERGE_GAP) for (dx in -MERGE_GAP..MERGE_GAP) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until w && ny in 0 until h) dilated[ny * w + nx] = true
            }
        }
        val label = IntArray(w * h) { -1 }
        val result = mutableListOf<Component>()
        val stack = ArrayDeque<Int>()
        for (start in 0 until w * h) {
            if (!dilated[start] || label[start] >= 0) continue
            val id = result.size
            val members = mutableListOf<Int>()
            label[start] = id
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                if (fg[i]) members += i
                val x = i % w
                val y = i / w
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val j = ny * w + nx
                    if (dilated[j] && label[j] < 0) {
                        label[j] = id
                        stack.addLast(j)
                    }
                }
            }
            result += Component(
                minX = members.minOf { it % w }, minY = members.minOf { it / w },
                maxX = members.maxOf { it % w }, maxY = members.maxOf { it / w },
                area = members.size, meanColor = averageColor(members.map { s.mean[it] })
            )
        }
        return result.filter { it.area >= MIN_AREA }
    }

    private fun appendComponents(report: StringBuilder, r: Region, s: RegionStats, bg: Int) {
        val list = components(s, bg).sortedBy { it.minX }
        if (list.isEmpty()) report.appendLine("  （无）")
        list.forEach { c ->
            report.appendLine(
                "  x ${r.x + c.minX}..${r.x + c.maxX}（宽 ${c.maxX - c.minX + 1}），" +
                    "y ${r.y + c.minY}..${r.y + c.maxY}（高 ${c.maxY - c.minY + 1}），" +
                    "像素 ${c.area}，平均色 #%06X".format(c.meanColor and 0xFFFFFF)
            )
        }
    }

    /**
     * 两组均值色差 > [FG_DIFF] 的像素统计：数量、外框，以及这些像素在两组里的平均色。
     * 用来判断某个元素是不是「问号状态和已刷新状态外观不同」。
     */
    private fun groupDiffSummary(r: Region, groups: Map<String, RegionStats>): String {
        val q = groups.getValue(GROUP_QUESTION)
        val e = groups.getValue(GROUP_REFRESHED)
        val idx = (0 until r.width * r.height).filter { colorDiff(q.mean[it], e.mean[it]) > FG_DIFF }
        if (idx.isEmpty()) return "两组均值色差 > $FG_DIFF 的像素：0 个\n"
        val xs = idx.map { it % r.width }
        val ys = idx.map { it / r.width }
        return "两组均值色差 > $FG_DIFF 的像素：${idx.size} 个，" +
            "外框 x ${r.x + xs.min()}..${r.x + xs.max()}，y ${r.y + ys.min()}..${r.y + ys.max()}，" +
            "$GROUP_QUESTION 平均色 #%06X，$GROUP_REFRESHED 平均色 #%06X\n".format(
                averageColor(idx.map { q.mean[it] }) and 0xFFFFFF,
                averageColor(idx.map { e.mean[it] }) and 0xFFFFFF
            )
    }

    private fun writePng(file: File, width: Int, height: Int, pixel: (Int) -> Int) {
        val k = PNG_ZOOM
        val img = BufferedImage(width * k, height * k, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height * k) for (x in 0 until width * k) {
            img.setRGB(x, y, pixel((y / k) * width + x / k))
        }
        ImageIO.write(img, "png", file)
    }

    private fun fmt(v: Double) = "%.2f".format(v)

    private companion object {
        const val STABLE_STD = 12.0
        const val FG_DIFF = 40
        const val MERGE_GAP = 2
        const val MIN_AREA = 20
        const val PNG_ZOOM = 3
        const val GROUP_QUESTION = "问号截图"
        const val GROUP_REFRESHED = "已刷新截图"

        fun colorDiff(a: Int, b: Int) =
            (abs(redOf(a) - redOf(b)) + abs(greenOf(a) - greenOf(b)) + abs(blueOf(a) - blueOf(b))) / 3

        fun averageColor(colors: List<Int>): Int {
            if (colors.isEmpty()) return 0
            val r = colors.sumOf { redOf(it) } / colors.size
            val g = colors.sumOf { greenOf(it) } / colors.size
            val b = colors.sumOf { blueOf(it) } / colors.size
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

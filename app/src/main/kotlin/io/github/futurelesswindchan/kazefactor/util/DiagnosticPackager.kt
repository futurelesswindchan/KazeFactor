package io.github.futurelesswindchan.kazefactor.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.FileProvider
import io.github.futurelesswindchan.kazefactor.data.datastore.CalibrationProfileDataStore
import io.github.futurelesswindchan.kazefactor.domain.model.FactorDatabase
import io.github.futurelesswindchan.kazefactor.domain.model.Recipe
import io.github.futurelesswindchan.kazefactor.engine.loop.PauseReason
import io.github.futurelesswindchan.kazefactor.engine.vision.Calibration
import io.github.futurelesswindchan.kazefactor.engine.vision.CalibrationProfile
import io.github.futurelesswindchan.kazefactor.engine.vision.PixelRect
import io.github.futurelesswindchan.kazefactor.engine.vision.ReferenceLayout
import io.github.futurelesswindchan.kazefactor.engine.vision.ScreenState
import io.github.futurelesswindchan.kazefactor.engine.vision.SlotAnalysis
import io.github.futurelesswindchan.kazefactor.engine.vision.SlotResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 单帧诊断现场快照。
 *
 * 仅在内存中保存最近若干帧的完整位图与槽位识别详情，直到触发导出时才编码为 PNG 与 JSON 写入压缩包。
 *
 * @property fullBitmap 完整屏幕截图位图
 * @property slotAnalyses 4 个槽位的详细匹配结果（含候选排行与距离）
 * @property screenState 画面状态判定结果
 * @property toggleDistance 与位置档案开关参照块的平均像素差；未使用位置档案时为 null
 * @property calibration 该帧使用的坐标换算参数，用于裁取 4 个槽位区域图像
 * @property capturedAtMs 截取时间戳（Unix 毫秒）
 */
data class FrameSnapshot(
    val fullBitmap: Bitmap,
    val slotAnalyses: List<SlotAnalysis>,
    val screenState: ScreenState = ScreenState.FACTOR_SCREEN_ON,
    val toggleDistance: Double? = null,
    val calibration: Calibration = Calibration.IDENTITY,
    val capturedAtMs: Long = System.currentTimeMillis()
)

/**
 * 识别诊断包生成器与系统分享工具。
 *
 * 负责将设备环境信息、当前生效的 [CalibrationProfile]、最近 1~3 帧完整截图、
 * 4 个槽位裁剪图以及各槽位的 [SlotAnalysis] 匹配排行榜打包为 `.zip` 文件，
 * 并通过 [FileProvider] 拉起系统原生分享面板。
 */
object DiagnosticPackager {

    private const val DIAGNOSTICS_DIR = "diagnostics"
    private const val MAX_RETAINED_ZIPS = 5

    private val prettyJson = Json {
        prettyPrint = true
    }

    /**
     * 生成诊断压缩包并通过系统原生分享面板发出。
     *
     * @param context 上下文（支持 Activity 或 AccessibilityService）
     * @param factorDatabase 因子数据库，用于将候选因子 ID 映射为可读的 key 与名称
     * @param profile 当前分辨率下生效的位置档案（无档案时传 null）
     * @param fallbackCalibration 无位置档案时使用的坐标换算参数
     * @param snapshots 最近 1~3 帧现场快照
     * @param pauseReason 触发导出时的暂停原因（手动在面板导出时可为 null）
     * @param selectedRecipe 当前选中的目标配方
     * @return 生成的 `.zip` 文件
     */
    fun exportAndShare(
        context: Context,
        factorDatabase: FactorDatabase,
        profile: CalibrationProfile?,
        fallbackCalibration: Calibration?,
        snapshots: List<FrameSnapshot>,
        pauseReason: PauseReason? = null,
        selectedRecipe: Recipe? = null
    ): File {
        val zipFile = createDiagnosticZip(
            context = context,
            factorDatabase = factorDatabase,
            profile = profile,
            fallbackCalibration = fallbackCalibration,
            snapshots = snapshots,
            pauseReason = pauseReason,
            selectedRecipe = selectedRecipe
        )
        shareZipFile(context, zipFile)
        return zipFile
    }

    /**
     * 在 `context.cacheDir/diagnostics/` 下创建 `diagnostics_YYYYMMDD_HHmmss.zip`。
     */
    fun createDiagnosticZip(
        context: Context,
        factorDatabase: FactorDatabase,
        profile: CalibrationProfile?,
        fallbackCalibration: Calibration?,
        snapshots: List<FrameSnapshot>,
        pauseReason: PauseReason? = null,
        selectedRecipe: Recipe? = null,
        now: Date = Date()
    ): File {
        val dir = File(context.cacheDir, DIAGNOSTICS_DIR).apply { mkdirs() }
        cleanupOldZips(dir)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        val zipFile = File(dir, "diagnostics_$stamp.zip")

        val metrics = context.resources.displayMetrics
        val deviceJson = buildDeviceInfoJson(
            manufacturer = Build.MANUFACTURER ?: "unknown",
            brand = Build.BRAND ?: "unknown",
            model = Build.MODEL ?: "unknown",
            device = Build.DEVICE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            screenWidthPx = metrics.widthPixels,
            screenHeightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            density = metrics.density,
            exportedAtMs = now.time,
            pauseReason = pauseReason,
            selectedRecipe = selectedRecipe,
            frameCount = snapshots.size
        )

        val calibrationJson = buildCalibrationJson(profile, fallbackCalibration)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            writeJsonEntry(zos, "device_info.json", deviceJson)
            writeJsonEntry(zos, "calibration_profile.json", calibrationJson)

            if (profile != null) {
                val patchBytes = CalibrationProfileDataStore.encodePngWithBitmap(profile.toggleOnPatch)
                writeBytesEntry(zos, "toggle_on_patch.png", patchBytes)
            }

            snapshots.take(3).forEachIndexed { idx, snapshot ->
                val frameNum = idx + 1
                val activeCalib = if (snapshot.calibration != Calibration.IDENTITY) {
                    snapshot.calibration
                } else {
                    profile?.toCalibration() ?: fallbackCalibration ?: Calibration.IDENTITY
                }

                // 1. 写入完整截图 PNG
                if (!snapshot.fullBitmap.isRecycled) {
                    writeBitmapEntry(zos, "frame_${frameNum}_full.png", snapshot.fullBitmap)

                    // 2. 写入 4 个槽位搜索区域裁剪图 PNG
                    for (slotIndex in 1..ReferenceLayout.SLOT_COUNT) {
                        val searchRect = computeSearchRect(activeCalib, slotIndex)
                        val cropped = cropBitmapSafely(snapshot.fullBitmap, searchRect)
                        if (cropped != null) {
                            try {
                                writeBitmapEntry(zos, "frame_${frameNum}_slot_${slotIndex}.png", cropped)
                            } finally {
                                if (cropped !== snapshot.fullBitmap) {
                                    cropped.recycle()
                                }
                            }
                        }
                    }
                }

                // 3. 写入该帧 4 槽位的详细打分排行 JSON
                val frameJson = buildFrameAnalysisJson(
                    frameIndex = frameNum,
                    snapshot = snapshot,
                    effectiveCalibration = activeCalib,
                    factorDatabase = factorDatabase
                )
                writeJsonEntry(zos, "frame_${frameNum}_analysis.json", frameJson)
            }
        }

        return zipFile
    }

    /**
     * 通过 [FileProvider] 授予临时读取权限并拉起系统分享面板。
     */
    fun shareZipFile(context: Context, zipFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, zipFile)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "KazeFactor 识别诊断包 (${zipFile.name})")
            clipData = ClipData.newRawUri("diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooserIntent = Intent.createChooser(sendIntent, "分享诊断包").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooserIntent)
    }

    /** 构建设备与环境信息 JSON 对象。 */
    internal fun buildDeviceInfoJson(
        manufacturer: String,
        brand: String,
        model: String,
        device: String,
        sdkInt: Int,
        androidRelease: String,
        screenWidthPx: Int,
        screenHeightPx: Int,
        densityDpi: Int,
        density: Float,
        exportedAtMs: Long,
        pauseReason: PauseReason?,
        selectedRecipe: Recipe?,
        frameCount: Int
    ): JsonObject = buildJsonObject {
        put("manufacturer", manufacturer)
        put("brand", brand)
        put("model", model)
        put("device", device)
        put("sdkInt", sdkInt)
        put("androidRelease", androidRelease)
        put("screenWidthPx", screenWidthPx)
        put("screenHeightPx", screenHeightPx)
        put("densityDpi", densityDpi)
        put("density", density.toDouble().finiteOrNull())
        put("exportedAtMs", exportedAtMs)
        put("exportedAtFormatted", SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(exportedAtMs)))
        put("pauseReason", pauseReason?.name)
        put("capturedFrameCount", frameCount)
        if (selectedRecipe != null) {
            putJsonObject("selectedRecipe") {
                put("id", selectedRecipe.id)
                put("name", selectedRecipe.name)
                putJsonArray("wantedFactorIds") {
                    selectedRecipe.wantedFactorIds.sorted().forEach { add(it) }
                }
                putJsonArray("unwantedFactorIds") {
                    selectedRecipe.unwantedFactorIds.sorted().forEach { add(it) }
                }
                putJsonArray("optionalFactorIds") {
                    selectedRecipe.optionalFactorIds.sorted().forEach { add(it) }
                }
                put("minOptional", selectedRecipe.minOptional)
            }
        }
    }

    /** 构建当前校准档案 JSON 对象。 */
    internal fun buildCalibrationJson(
        profile: CalibrationProfile?,
        fallbackCalibration: Calibration?
    ): JsonObject = buildJsonObject {
        put("hasSavedProfile", profile != null)
        if (profile != null) {
            put("screenWidth", profile.screenWidth)
            put("screenHeight", profile.screenHeight)
            put("scaleX", profile.scaleX.finiteOrNull())
            put("scaleY", profile.scaleY.finiteOrNull())
            put("originX", profile.originX.finiteOrNull())
            put("originY", profile.originY.finiteOrNull())
            put("source", profile.source.name)
            put("tapX", profile.tapX.finiteOrNull())
            put("tapY", profile.tapY.finiteOrNull())
            put("tapScreenX", profile.tapScreenX().finiteOrNull())
            put("tapScreenY", profile.tapScreenY().finiteOrNull())
            put("togglePatchWidth", profile.toggleOnPatch.width)
            put("togglePatchHeight", profile.toggleOnPatch.height)
            put("createdAtMs", profile.createdAt)
        } else if (fallbackCalibration != null) {
            putJsonObject("fallbackCalibration") {
                put("scaleX", fallbackCalibration.scaleX.finiteOrNull())
                put("scaleY", fallbackCalibration.scaleY.finiteOrNull())
                put("originX", fallbackCalibration.originX.finiteOrNull())
                put("originY", fallbackCalibration.originY.finiteOrNull())
            }
        }
    }

    /** 构建单帧 4 槽位详细分析排行 JSON 对象。 */
    internal fun buildFrameAnalysisJson(
        frameIndex: Int,
        snapshot: FrameSnapshot,
        effectiveCalibration: Calibration,
        factorDatabase: FactorDatabase
    ): JsonObject = buildFrameAnalysisJson(
        frameIndex = frameIndex,
        capturedAtMs = snapshot.capturedAtMs,
        screenState = snapshot.screenState,
        toggleDistance = snapshot.toggleDistance,
        slotAnalyses = snapshot.slotAnalyses,
        effectiveCalibration = effectiveCalibration,
        factorDatabase = factorDatabase
    )

    /** 构建单帧 4 槽位详细分析排行 JSON 对象（无平台 Bitmap 依赖，便于单元测试）。 */
    internal fun buildFrameAnalysisJson(
        frameIndex: Int,
        capturedAtMs: Long,
        screenState: ScreenState,
        toggleDistance: Double?,
        slotAnalyses: List<SlotAnalysis>,
        effectiveCalibration: Calibration,
        factorDatabase: FactorDatabase
    ): JsonObject = buildJsonObject {
        put("frameIndex", frameIndex)
        put("capturedAtMs", capturedAtMs)
        put("screenState", screenState.name)
        put("toggleDistance", toggleDistance?.finiteOrNull())
        putJsonObject("calibration") {
            put("scaleX", effectiveCalibration.scaleX.finiteOrNull())
            put("scaleY", effectiveCalibration.scaleY.finiteOrNull())
            put("originX", effectiveCalibration.originX.finiteOrNull())
            put("originY", effectiveCalibration.originY.finiteOrNull())
        }
        putJsonArray("slots") {
            for ((slotIdx, result, questionDistance, ranking, secondaryResolved, secondaryMargin) in slotAnalyses) {
                val slotRect = effectiveCalibration.toScreenRect(ReferenceLayout.slotRect(slotIdx))
                val searchRect = computeSearchRect(effectiveCalibration, slotIdx)
                add(
                    buildJsonObject {
                        put("slotIndex", slotIdx)
                        putJsonObject("slotScreenRect") {
                            put("x", slotRect.x)
                            put("y", slotRect.y)
                            put("width", slotRect.width)
                            put("height", slotRect.height)
                        }
                        putJsonObject("searchRect") {
                            put("x", searchRect.x)
                            put("y", searchRect.y)
                            put("width", searchRect.width)
                            put("height", searchRect.height)
                        }
                        put("resultType", slotResultTypeName(result))
                        put("resultSummary", formatSlotResult(result, factorDatabase))
                        put("questionDistance", questionDistance.finiteOrNull())
                        put("secondaryResolved", secondaryResolved)
                        put("secondaryMargin", secondaryMargin?.finiteOrNull())
                        putJsonArray("ranking") {
                            ranking.forEachIndexed { rankIdx, score ->
                                val factor = factorDatabase.getFactorById(score.factorId)
                                add(
                                    buildJsonObject {
                                        put("rank", rankIdx + 1)
                                        put("factorId", score.factorId)
                                        put("factorKey", factor?.key)
                                        put("factorName", factor?.name)
                                        put("distance", score.distance.finiteOrNull())
                                        put("offsetX", score.offsetX)
                                        put("offsetY", score.offsetY)
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    /** 计算第 [slotIndex] 槽在屏幕上的搜索区域矩形。 */
    internal fun computeSearchRect(calibration: Calibration, slotIndex: Int): PixelRect {
        val refSlot = ReferenceLayout.slotRect(slotIndex)
        val screenSlot = calibration.toScreenRect(refSlot)
        val margin = ReferenceLayout.SEARCH_MARGIN
        return PixelRect(
            x = screenSlot.x - margin,
            y = screenSlot.y - margin,
            width = screenSlot.width + 2 * margin,
            height = screenSlot.height + 2 * margin
        )
    }

    /** 安全裁取位图区域，对越界坐标执行边界截断，完全在画面外时返回 null。 */
    private fun cropBitmapSafely(source: Bitmap, rect: PixelRect): Bitmap? {
        if (source.isRecycled) return null
        val left = rect.x.coerceIn(0, source.width)
        val top = rect.y.coerceIn(0, source.height)
        val right = (rect.x + rect.width).coerceIn(0, source.width)
        val bottom = (rect.y + rect.height).coerceIn(0, source.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(source, left, top, w, h)
    }

    private fun slotResultTypeName(result: SlotResult): String = when (result) {
        is SlotResult.Factor -> "Factor"
        is SlotResult.Ambiguous -> "Ambiguous"
        is SlotResult.Question -> "Question"
        is SlotResult.Unknown -> "Unknown"
    }

    private fun formatSlotResult(result: SlotResult, db: FactorDatabase): String = when (result) {
        is SlotResult.Factor -> {
            val f = db.getFactorById(result.factorId)
            "Factor(id=${result.factorId}, key=${f?.key}, name=${f?.name})"
        }
        is SlotResult.Ambiguous -> {
            val names = result.candidateIds.map { id ->
                val f = db.getFactorById(id)
                "${id}:${f?.name ?: "?"}"
            }
            "Ambiguous(${names.joinToString(", ")})"
        }
        is SlotResult.Question -> "Question"
        is SlotResult.Unknown -> "Unknown"
    }

    private fun writeJsonEntry(zos: ZipOutputStream, entryName: String, jsonObject: JsonObject) {
        val bytes = prettyJson.encodeToString(JsonObject.serializer(), jsonObject).toByteArray(Charsets.UTF_8)
        writeBytesEntry(zos, entryName, bytes)
    }

    private fun writeBitmapEntry(zos: ZipOutputStream, entryName: String, bitmap: Bitmap) {
        val pngBytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        writeBytesEntry(zos, entryName, pngBytes)
    }

    private fun writeBytesEntry(zos: ZipOutputStream, entryName: String, bytes: ByteArray) {
        val entry = ZipEntry(entryName)
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    /** 清理缓存目录中较早的诊断压缩包，仅保留最近的若干份。 */
    private fun cleanupOldZips(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("zip", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.size >= MAX_RETAINED_ZIPS) {
            files.drop(MAX_RETAINED_ZIPS - 1).forEach { it.delete() }
        }
    }

    private fun Double.finiteOrNull(): Double? = if (isFinite()) this else null
}

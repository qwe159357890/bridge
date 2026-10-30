package my.bridge.com.scanner

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 快递扫码识别器（照调研文档 §6：采集→识别→确认→救援 四段）。
 *
 * - **多帧一致性投票**（官方建议）：同一值连续命中 ≥2 帧才确认，防逐帧误识；
 * - **高清单帧救援**：流式连续 12 帧无结果（≈1 秒）→ 回调页面拍一张高清静态图，
 *   用单图模式精识别（对褶皱/反光/远距小码显著更强），全程静默；
 * - 确认后进入 800ms 冷却（照参考项目 resultDisplayDuration），避免同码瞬间重触发；
 * - [paused] 由页面在「单次模式」确认后置位，恢复扫描时清零。
 */
class ScanAnalyzer(
    /** 码制白名单（ScanFormats.ALL 子集），映射为 ML Kit 格式常量。 */
    formatNames: Set<String>,
    /** 多帧确认阈值（帧）。 */
    private val voteThreshold: Int = 2,
    /** 连续无结果多少帧后触发高清单帧救援。 */
    private val rescueAfterMisses: Int = 12,
    /** 确认回调（主线程）。 */
    private val onConfirmed: (value: String, format: String) -> Unit,
    /**
     * 救援取图回调：页面用 ImageCapture 拍一张高清静态图并回传（可为 null 表示失败）。
     * 由页面保证回调在任意线程执行均可。
     */
    private val captureStill: (onResult: (Bitmap?) -> Unit) -> Unit,
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "快递扫码"
        private const val COOLDOWN_MS = 800L

        /** 码制名 → ML Kit 格式常量。 */
        private val FORMAT_MAP: Map<String, Int> = mapOf(
            ScanFormats.CODE_128 to Barcode.FORMAT_CODE_128,
            ScanFormats.CODE_39 to Barcode.FORMAT_CODE_39,
            ScanFormats.ITF to Barcode.FORMAT_ITF,
            ScanFormats.QR_CODE to Barcode.FORMAT_QR_CODE,
            ScanFormats.DATA_MATRIX to Barcode.FORMAT_DATA_MATRIX,
            ScanFormats.PDF417 to Barcode.FORMAT_PDF417,
        )

        /** ML Kit 格式常量 → 码制名（结果展示用）。 */
        val FORMAT_NAME_MAP: Map<Int, String> =
            FORMAT_MAP.entries.associate { (k, v) -> v to k }
    }

    /** ML Kit 流式识别器。 */
    private val streamScanner: BarcodeScanner

    /** 单图救援识别器（同一 options，独立实例避免互扰）。 */
    private val stillScanner: BarcodeScanner

    init {
        val mlkitFormats = formatNames.mapNotNull { FORMAT_MAP[it] }.toIntArray()
        val options = if (mlkitFormats.isEmpty()) {
            BarcodeScannerOptions.Builder().build()
        } else {
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(mlkitFormats[0], *mlkitFormats.drop(1).toIntArray())
                .build()
        }
        streamScanner = BarcodeScanning.getClient(options)
        stillScanner = BarcodeScanning.getClient(options)
    }

    /** 单次模式确认后暂停（页面置位/清零）。 */
    @Volatile
    var paused: Boolean = false

    private val rescueBusy = AtomicBoolean(false)

    // 投票状态（analyze 与结果回调可能在不同线程，统一 synchronize）
    private var candidateValue: String? = null
    private var candidateCount = 0
    private var missStreak = 0
    private var coolingUntil = 0L

    @Synchronized
    fun reset() {
        candidateValue = null
        candidateCount = 0
        missStreak = 0
        coolingUntil = 0L
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (paused) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        streamScanner.process(input)
            .addOnSuccessListener { barcodes ->
                handleStreamResult(barcodes)
            }
            .addOnFailureListener { e ->
                Timber.tag(TAG).w(e, "流式识别失败")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /** 流式结果处理（主线程回调，投票状态经 synchronize 保护）。 */
    private fun handleStreamResult(barcodes: List<Barcode>) {
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (now < coolingUntil) return

            val hit = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }
            if (hit == null) {
                missStreak++
                if (missStreak >= rescueAfterMisses) {
                    missStreak = 0
                    triggerRescue()
                }
                return
            }
            missStreak = 0
            val value = hit.rawValue!!
            if (value == candidateValue) {
                candidateCount++
            } else {
                candidateValue = value
                candidateCount = 1
            }
            if (candidateCount >= voteThreshold) {
                val format = FORMAT_NAME_MAP[hit.format] ?: ScanFormats.CODE_128
                coolingUntil = now + COOLDOWN_MS
                candidateValue = null
                candidateCount = 0
                Timber.tag(TAG).i("识别确认（连续%d帧一致） %s", voteThreshold, value)
                onConfirmed(value, format)
            }
        }
    }

    /**
     * 触发高清单帧救援（静默）：页面拍一张高清静态图 → 单图模式识别。
     * 救援期间不再重复触发；识别成功同样走确认回调（投票/冷却状态重置）。
     */
    private fun triggerRescue() {
        if (!rescueBusy.compareAndSet(false, true)) return
        Timber.tag(TAG).i("流式连续无结果，触发高清单帧救援")
        captureStill { bitmap ->
            if (bitmap == null) {
                Timber.tag(TAG).w("救援取图失败")
                rescueBusy.set(false)
                return@captureStill
            }
            val input = InputImage.fromBitmap(bitmap, 0)
            stillScanner.process(input)
                .addOnSuccessListener { barcodes ->
                    val hit = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }
                    if (hit != null) {
                        val format = FORMAT_NAME_MAP[hit.format] ?: ScanFormats.CODE_128
                        synchronized(this) {
                            coolingUntil = System.currentTimeMillis() + COOLDOWN_MS
                            candidateValue = null
                            candidateCount = 0
                        }
                        Timber.tag(TAG).i("救援识别成功 %s", hit.rawValue)
                        onConfirmed(hit.rawValue!!, format)
                    } else {
                        Timber.tag(TAG).i("救援识别仍无结果")
                    }
                }
                .addOnFailureListener { e -> Timber.tag(TAG).w(e, "救援识别失败") }
                .addOnCompleteListener { rescueBusy.set(false) }
        }
    }

    fun release() {
        runCatching { streamScanner.close() }
        runCatching { stillScanner.close() }
    }
}

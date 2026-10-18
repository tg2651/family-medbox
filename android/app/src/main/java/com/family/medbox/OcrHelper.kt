package com.family.medbox

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** 药品标签 OCR 识别结果 */
data class MedOcrResult(
    val name: String = "",
    val spec: String = "",
    val expiry: String = "",
    val rawText: String = ""
)

/** 处方 OCR 识别结果 */
data class RxOcrResult(
    val medName: String = "",      // 识别到的药品名（用于匹配已有药品）
    val timesPerDay: Int = 0,      // 每日次数
    val dosePerTime: String = "",  // 每次剂量
    val doseUnit: String = "",     // 剂量单位
    val rawText: String = ""
)

object OcrHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(ctx: Context, uri: Uri): String = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromFilePath(ctx, uri)
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resumeWithException(it) }
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    /** 扫描药品外包装商品条码（EAN-13/EAN-8/UPC），返回纯数字码 */
    suspend fun scanBarcode(ctx: Context, uri: Uri): String = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromFilePath(ctx, uri)
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E
                )
                .build()
            BarcodeScanning.getClient(options).process(image)
                .addOnSuccessListener { barcodes ->
                    val code = barcodes.firstOrNull()?.rawValue ?: ""
                    cont.resume(code.replace(Regex("\\D"), ""))
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    /** 解析药品包装/说明书文字，提取药品信息 */
    fun parseMedLabel(text: String): MedOcrResult {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val r = MedOcrResult(rawText = text)

        // 规格：如 0.25g×24片 / 10mg*7片 / 100ml
        val specRegex = Regex("""(\d+(?:\.\d+)?\s*(?:mg|g|ml|μg|ug|IU|单位)\s*[×xX\*]\s*\d+\s*[片粒胶囊袋支瓶丸滴]?)""")
        // 有效期至：2026-12-31 / 2026.12 / 2026年12月31日
        val expiryRegex = Regex("""(?:有效期至|失效期|有效期[:：]?|EXP[:：.]?\s*)(\d{4})[-/年.](\d{1,2})(?:[-/月.](\d{1,2}))?""")
        // 药品名特征行：含 片/胶囊/颗粒/口服液/注射液/丸/膏/散/糖浆/滴丸 且不含说明书常用词
        val nameSuffix = Regex("""[一-龥A-Za-z（）()·]+(片|胶囊|颗粒|口服液|注射液|注射用[一-龥]+|丸|膏|散|糖浆|滴丸|缓释片|肠溶片|分散片|泡腾片|气雾剂|喷雾剂|滴眼液|滴鼻液|洗剂|搽剂|酊剂|乳膏|软膏|凝胶|贴膏|栓剂)""")
        val nameBlacklist = Regex("""(说明书|请仔细阅读|禁忌|不良反应|注意事项|用法用量|成份|性状|贮藏|包装|批准文号|生产企业|执行标准|功能主治|适应症|规格|有效期|生产日期|产品批号|药师|处方|本品|药物)""")

        var spec = ""
        var expiry = ""
        var name = ""

        for (line in lines) {
            if (spec.isEmpty()) {
                specRegex.find(line)?.let { spec = it.value.replace(" ", "") }
            }
            if (expiry.isEmpty()) {
                expiryRegex.find(line)?.let { m ->
                    val y = m.groupValues[1]
                    val mo = m.groupValues[2].padStart(2, '0')
                    val d = m.groupValues.getOrNull(3)?.takeIf { s -> s.isNotEmpty() }?.padStart(2, '0') ?: "01"
                    expiry = "$y-$mo-$d"
                }
            }
            if (name.isEmpty() && line.length in 2..30 && !nameBlacklist.containsMatchIn(line)) {
                nameSuffix.find(line)?.let { name = it.value }
            }
        }
        // 兜底：取第一条不含黑名单词且较短的行
        if (name.isEmpty()) {
            name = lines.firstOrNull { it.length in 4..24 && !nameBlacklist.containsMatchIn(it) && !it.any { c -> c.isDigit() } } ?: ""
        }
        return r.copy(name = name, spec = spec, expiry = expiry)
    }

    /** 解析处方文字，提取药名与用法用量 */
    fun parsePrescription(text: String): RxOcrResult {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // 每日次数：一日3次 / 每日2次 / 3次/日 / tid(=3) bid(=2) qd(=1) qid(=4)
        val timesRegex = Regex("""(?:一日|每日|每天)\s*(\d)\s*次|(\d)\s*次\s*/\s*(?:日|天)""")
        val latinTimes = mapOf("qd" to 1, "bid" to 2, "tid" to 3, "qid" to 4)
        // 每次剂量：一次2片 / 每次1粒 / 1片/次
        val doseRegex = Regex("""(?:一次|每次)\s*(\d+(?:\.\d+)?)\s*([片粒胶囊袋支瓶丸滴毫升ml克g毫克mg]+)|(\d+(?:\.\d+)?)\s*([片粒胶囊袋支瓶丸滴])\s*/\s*次""")
        // 药名特征
        val nameSuffix = Regex("""[一-龥A-Za-z（）()·]+(片|胶囊|颗粒|口服液|注射液|丸|膏|散|糖浆|缓释片|肠溶片|分散片|滴眼液|乳膏|软膏|凝胶|栓剂)""")

        var medName = ""
        var timesPerDay = 0
        var dosePerTime = ""
        var doseUnit = ""

        for (line in lines) {
            if (timesPerDay == 0) {
                timesRegex.find(line)?.let { m ->
                    timesPerDay = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() ?: 0
                }
                if (timesPerDay == 0) {
                    val low = line.lowercase()
                    for ((k, v) in latinTimes) {
                        if (Regex("""\b$k\b""").containsMatchIn(low)) { timesPerDay = v; break }
                    }
                }
            }
            if (dosePerTime.isEmpty()) {
                doseRegex.find(line)?.let { m ->
                    if (m.groupValues[1].isNotEmpty()) {
                        dosePerTime = m.groupValues[1]; doseUnit = m.groupValues[2]
                    } else {
                        dosePerTime = m.groupValues[3]; doseUnit = m.groupValues[4]
                    }
                    // 单位归一
                    doseUnit = doseUnit.replace("毫升", "ml").replace("毫克", "mg").replace(Regex("^(克|g)$"), "g")
                        .ifEmpty { "片" }.take(2)
                }
            }
            if (medName.isEmpty()) {
                nameSuffix.find(line)?.let {
                    // 排除"用法用量"等说明行
                    if (!line.contains("用法") && !line.contains("说明")) medName = it.value
                }
            }
        }
        return RxOcrResult(medName, timesPerDay, dosePerTime, doseUnit, text)
    }

    /** 按每日次数生成默认服药时间点 */
    fun defaultTimes(n: Int): String = when (n) {
        1 -> "08:00"
        2 -> "08:00,20:00"
        3 -> "08:00,12:00,20:00"
        4 -> "08:00,12:00,16:00,20:00"
        else -> ""
    }
}

@file:OptIn(ExperimentalMaterial3Api::class)

package com.family.medbox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.Calendar

// ---------- 主题色 ----------
private val Blue = Color(0xFF2563EB)
private val Orange = Color(0xFFD97706)
private val Red = Color(0xFFDC2626)
private val Green = Color(0xFF16A34A)
private val Gray = Color(0xFF64748B)

// ---------- 工具 ----------
private fun todayStr(): String {
    val c = Calendar.getInstance()
    return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

private fun JSONArray.objs(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

private fun fmtNum(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

// ---------- 图片工具 ----------
private fun newPhotoUri(ctx: Context): Uri {
    val file = File(ctx.cacheDir, "med_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
}

// 缩放并压缩为 base64（最大边 1024，JPEG 80 质量）
private fun imageToBase64(ctx: Context, uri: Uri): String {
    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return ""
    val maxDim = 1024
    val scale = maxOf(1, maxOf(bmp.width, bmp.height) / maxDim)
    val scaled = if (scale > 1) Bitmap.createScaledBitmap(bmp, bmp.width / scale, bmp.height / scale, true) else bmp
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
    return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
}

/** 远程图片（从服务器加载） */
@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val fullUrl = if (url.startsWith("http")) url else ApiClient.baseUrl + url
    var bmp by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bmp = withContext(Dispatchers.IO) {
            try {
                val bytes = URL(fullUrl).readBytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: Exception) { null }
        }
    }
    val b = bmp
    if (b != null) Image(b, contentDescription = null, modifier = modifier, contentScale = contentScale)
    else Box(modifier, Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
}

/** 药品拍照录入按钮：拍照→上传→返回图片 url */
@Composable
private fun MedPhotoPicker(imageUrl: String?, onResult: (imageUrl: String, ocr: MedOcrResult?, source: String) -> Unit, onClear: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf("处理中…") }
    var err by remember { mutableStateOf("") }

    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && photoUri != null) {
            scope.launch {
                busy = true; err = ""
                try {
                    val b64 = imageToBase64(ctx, photoUri!!)
                    var ocr: MedOcrResult? = null
                    var source = ""
                    // 优先服务端 AI 识别（本地大模型较慢，用 120 秒超时）
                    busyText = "AI识别中…"
                    try {
                        val r = ApiClient.post("/api/ai/recognize",
                            JSONObject().put("image", b64).put("type", "medicine"), 120000) as JSONObject
                        val aiName = r.optString("name")
                        val aiSpec = r.optString("spec")
                        val aiExpiry = r.optString("expiry_date")
                        val aiRaw = r.optString("raw")
                        if (aiName.isNotEmpty() || aiSpec.isNotEmpty() || aiExpiry.isNotEmpty()) {
                            ocr = MedOcrResult(name = aiName, spec = aiSpec, expiry = aiExpiry, rawText = aiRaw)
                            source = "AI"
                        } else if (aiRaw.isNotBlank()) {
                            // AI 返回了文本但未结构化 → 用本地解析器兜底
                            ocr = OcrHelper.parseMedLabel(aiRaw)
                            source = "AI+本地解析"
                        }
                    } catch (_: Exception) {
                        // AI 不可用 → 降级本地 OCR
                        try {
                            val text = OcrHelper.recognize(ctx, photoUri!!)
                            if (text.isNotBlank()) { ocr = OcrHelper.parseMedLabel(text); source = "本地OCR" }
                        } catch (_: Exception) { }
                    }
                    // 上传图片
                    busyText = "上传中…"
                    val up = ApiClient.post("/api/upload", JSONObject().put("image", b64)) as JSONObject
                    onResult(up.getString("url"), ocr, source)
                } catch (e: Exception) { err = e.message ?: "上传失败" }
                busy = false
            }
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) { val u = newPhotoUri(ctx); photoUri = u; camLauncher.launch(u) }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            if (busy) return@OutlinedButton
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                val u = newPhotoUri(ctx); photoUri = u; camLauncher.launch(u)
            } else permLauncher.launch(Manifest.permission.CAMERA)
        }) {
            Icon(Icons.Filled.CameraAlt, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (busy) busyText else "拍照录入")
        }
        if (!imageUrl.isNullOrEmpty()) {
            Spacer(Modifier.width(8.dp))
            Box {
                RemoteImage(imageUrl, Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)))
                IconButton(onClick = onClear, Modifier.align(Alignment.TopEnd).size(20.dp)) {
                    Icon(Icons.Filled.Close, "移除", Modifier.size(16.dp))
                }
            }
        }
        if (err.isNotEmpty()) Text(err, color = Red, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 拉取最新计划并重新注册本地服药提醒 */
private suspend fun refreshReminders(ctx: Context) {
    try {
        val r = ApiClient.get("/api/schedules")
        if (r is JSONArray) {
            Store.saveSchedulesCache(ctx, r)
            ReminderScheduler.scheduleAll(ctx, r)
        }
    } catch (_: Exception) {
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.load(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue)) {
                App()
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var user by remember { mutableStateOf(Store.user(ctx)) }
    // 安卓 13+ 请求通知权限（服药提醒）
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (user == null) LoginScreen { user = Store.user(ctx) }
    else MainScreen(user!!) { user = null }
}

// ---------- 登录 / 注册 ----------
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf(Store.baseUrl(ctx)) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var reg by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(if (reg) "注册家庭成员" else "家庭药箱", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Blue)
        Text("全家人的用药提醒助手", fontSize = 15.sp, color = Gray)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(base, { base = it }, label = { Text("服务器地址") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(username, { username = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(password, { password = it }, label = { Text("密码") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        if (reg) {
            OutlinedTextField(name, { name = it }, label = { Text("姓名 / 称呼") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(age, { age = it }, label = { Text("年龄（选填）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(invite, { invite = it }, label = { Text("邀请码（加入已有家庭，留空=新建）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        if (err.isNotEmpty()) Text(err, color = Red, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
        Button(
            onClick = {
                busy = true; err = ""
                scope.launch {
                    try {
                        ApiClient.baseUrl = base.trim().removeSuffix("/")
                        if (reg) {
                            val body = JSONObject()
                                .put("username", username.trim()).put("password", password)
                                .put("display_name", name.trim())
                            if (age.isNotBlank()) body.put("age", age.toInt())
                            if (invite.isNotBlank()) body.put("invite_code", invite.trim())
                            val r = ApiClient.post("/api/auth/register", body) as JSONObject
                            Store.saveLogin(ctx, ApiClient.baseUrl, r.getString("token"), r.getJSONObject("user"))
                        } else {
                            val r = ApiClient.post("/api/auth/login",
                                JSONObject().put("username", username.trim()).put("password", password)) as JSONObject
                            Store.saveLogin(ctx, ApiClient.baseUrl, r.getString("token"), r.getJSONObject("user"))
                        }
                        refreshReminders(ctx)
                        onDone()
                    } catch (e: Exception) {
                        err = e.message ?: "操作失败"
                    }
                    busy = false
                }
            },
            enabled = !busy && username.isNotBlank() && password.isNotBlank() && (!reg || name.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (busy) "请稍候…" else if (reg) "注 册" else "登 录", fontSize = 18.sp) }
        TextButton(onClick = { reg = !reg; err = "" }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (reg) "已有账号？去登录" else "没有账号？注册新成员")
        }
        Text(
            "首次注册将自动创建家庭并成为管理员；\n加入已有家庭需要管理员提供的邀请码",
            fontSize = 12.sp, color = Gray
        )
    }
}

// ---------- 主框架：底部四页签 ----------
@Composable
fun MainScreen(user: JSONObject, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var showAdmin by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { refreshReminders(ctx) }

    if (showAdmin) {
        AdminScreen(user) { showAdmin = false }
        return
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                val tabs = listOf(
                    "今日" to Icons.Filled.Today,
                    "药品" to Icons.Filled.Medication,
                    "计划" to Icons.Filled.Schedule,
                    "我的" to Icons.Filled.Person
                )
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, label) }, label = { Text(label, fontSize = 13.sp) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TodayTab(user)
                1 -> MedsTab(user)
                2 -> PlansTab(user)
                else -> ProfileTab(user, onLogout) { showAdmin = true }
            }
        }
    }
}

// ---------- 今日服药 ----------
@Composable
fun TodayTab(user: JSONObject, userId: Int = -1) {
    // userId >= 0 时为管理员查看指定成员
    val path = if (userId >= 0) "/api/today?user_id=$userId" else "/api/today"
    var items by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            items = (ApiClient.get(path) as? JSONArray)?.objs() ?: emptyList()
            err = ""
        } catch (e: Exception) { err = e.message ?: "" }
        loaded = true
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("今日服药 · ${user.optString("display_name")}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(todayStr(), fontSize = 14.sp, color = Gray)
        Spacer(Modifier.height(12.dp))
        if (!loaded) Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
        else if (err.isNotEmpty()) Text(err, color = Red)
        else if (items.isEmpty()) Text("今天没有用药安排", color = Gray, fontSize = 16.sp, modifier = Modifier.padding(16.dp))
        else LazyColumn {
            items(items) { item ->
                TodayCard(item) { status ->
                    scope.launch {
                        try {
                            ApiClient.post("/api/intake", JSONObject()
                                .put("schedule_id", item.getInt("schedule_id"))
                                .put("plan_time", item.getString("plan_time"))
                                .put("status", status))
                            load()
                        } catch (e: Exception) { err = e.message ?: "" }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayCard(item: JSONObject, onMark: (String) -> Unit) {
    val status = item.optString("status", "pending")
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(item.optString("plan_time"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Blue)
                    Spacer(Modifier.width(10.dp))
                    Text(item.optString("medicine_name"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "每次 ${fmtNum(item.optDouble("dose", 1.0))} ${item.optString("dose_unit", "片")}",
                    fontSize = 14.sp, color = Gray
                )
            }
            if (status == "pending") {
                TextButton(onClick = { onMark("taken") }) { Text("服了", color = Green, fontSize = 16.sp) }
                TextButton(onClick = { onMark("skipped") }) { Text("跳过", color = Gray, fontSize = 16.sp) }
            } else {
                Text(
                    if (status == "taken") "已服 ✓" else "已跳过",
                    color = if (status == "taken") Green else Gray, fontSize = 15.sp
                )
            }
        }
    }
}

// ---------- 我的药品 ----------
@Composable
fun MedsTab(user: JSONObject, userId: Int = -1, readOnly: Boolean = false) {
    val path = if (userId >= 0) "/api/medicines?user_id=$userId" else "/api/medicines"
    var meds by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var err by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var editMed by remember { mutableStateOf<JSONObject?>(null) }
    var delMed by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            meds = (ApiClient.get(path) as? JSONArray)?.objs() ?: emptyList()
            err = ""
        } catch (e: Exception) { err = e.message ?: "" }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("我的药品（${meds.size}）", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!readOnly) Button(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, null); Text("添加")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (err.isNotEmpty()) Text(err, color = Red)
        if (meds.isEmpty() && err.isEmpty()) Text("暂无药品，点右上角「添加」录入", color = Gray, modifier = Modifier.padding(16.dp))
        LazyColumn {
            items(meds) { m ->
                MedCard(m, readOnly, onEdit = { editMed = m }, onDelete = { delMed = m })
            }
        }
    }

    if (showAdd) AddMedDialog(userId) { showAdd = false; scope.launch { load() } }
    editMed?.let { m ->
        EditStockDialog(m) {
            editMed = null
            scope.launch { load() }
        }
    }
    delMed?.let { m ->
        AlertDialog(
            onDismissRequest = { delMed = null },
            title = { Text("删除药品") },
            text = { Text("确定删除「${m.optString("name")}」及其用药计划吗？") },
            confirmButton = {
                TextButton(onClick = {
                    delMed = null
                    scope.launch {
                        try { ApiClient.delete("/api/medicines/${m.getInt("id")}"); load() } catch (e: Exception) { err = e.message ?: "" }
                    }
                }) { Text("删除", color = Red) }
            },
            dismissButton = { TextButton(onClick = { delMed = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun MedCard(m: JSONObject, readOnly: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val refill = m.optJSONObject("refill")
    val expiry = m.optJSONObject("expiry")
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            if (m.optString("image_url").isNotEmpty()) {
                RemoteImage(
                    m.optString("image_url"),
                    Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.optString("name"), fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (expiry?.optBoolean("expired") == true)
                    Text("已过期", color = Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                else if (refill?.optBoolean("needRefill") == true)
                    Text("该开药了", color = Orange, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            if (m.optString("spec").isNotEmpty()) Text(m.optString("spec"), fontSize = 14.sp, color = Gray)
            Text(
                "余 ${fmtNum(m.optDouble("stock_qty"))} ${m.optString("unit", "盒")} · 日用量 ${fmtNum(m.optDouble("daily_dose"))} ${m.optString("dose_unit", "片")}",
                fontSize = 15.sp
            )
            if (m.optString("expiry_date").isNotEmpty())
                Text(
                    "有效期至 ${m.optString("expiry_date")}" +
                        (if (expiry != null && expiry.optBoolean("nearExpiry") && !expiry.optBoolean("expired"))
                            "（${expiry.optInt("days_left")} 天后到期）" else ""),
                    fontSize = 14.sp,
                    color = if (expiry?.optBoolean("expired") == true) Red
                        else if (expiry?.optBoolean("nearExpiry") == true) Orange else Gray
                )
            if (m.optString("location").isNotEmpty()) Text("存放：${m.optString("location")}", fontSize = 14.sp, color = Gray)
            if (refill?.optBoolean("needRefill") == true)
                Text("⚠ 库存仅够服 ${refill.optInt("daysLeft")} 天，请及时开药", color = Orange, fontSize = 15.sp)
            if (!readOnly) Row {
                TextButton(onClick = onEdit) { Text("改库存", fontSize = 14.sp) }
                TextButton(onClick = onDelete) { Text("删除", color = Red, fontSize = 14.sp) }
            }
            }
        }
    }
}

@Composable
private fun AddMedDialog(userId: Int, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var spec by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var stock by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("盒") }
    var dailyDose by remember { mutableStateOf("") }
    var doseUnit by remember { mutableStateOf("片") }
    var location by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }
    var manufacturer by remember { mutableStateOf("") }
    var approvalNo by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf("") }
    var barcodeHint by remember { mutableStateOf("") }
    var bcBusy by remember { mutableStateOf(false) }
    var bcPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var ocrHint by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // 扫条码（药盒 EAN-13 商品条码 / 药品本位码 86 开头 14 位）→ 本地库/公开库查询 → 自动填充
    val bcCamLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && bcPhotoUri != null) {
            scope.launch {
                bcBusy = true; barcodeHint = ""
                try {
                    val code = OcrHelper.scanBarcode(ctx, bcPhotoUri!!)
                    if (code.length in 8..14) {
                        barcode = code
                        val codeType = if (code.length == 14 && code.startsWith("86")) "本位码" else "商品条码"
                        barcodeHint = "$codeType：$code，查询中…"
                        val r = ApiClient.get("/api/medicine/barcode/$code") as JSONObject
                        val filled = mutableListOf<String>()
                        when (r.optString("found")) {
                            "local" -> {
                                val m = r.optJSONObject("medicine")
                                if (m != null) {
                                    if (name.isBlank() && m.optString("name").isNotEmpty()) { name = m.optString("name"); filled.add("名称") }
                                    if (spec.isBlank() && m.optString("spec").isNotEmpty()) { spec = m.optString("spec"); filled.add("规格") }
                                    if (expiry.isBlank() && m.optString("expiry_date").isNotEmpty()) { expiry = m.optString("expiry_date"); filled.add("有效期") }
                                    if (manufacturer.isBlank() && m.optString("manufacturer").isNotEmpty()) { manufacturer = m.optString("manufacturer"); filled.add("厂家") }
                                    if (approvalNo.isBlank() && m.optString("approval_no").isNotEmpty()) { approvalNo = m.optString("approval_no"); filled.add("批准文号") }
                                    if (dailyDose.isBlank() && m.optDouble("daily_dose") > 0) { dailyDose = fmtNum(m.optDouble("daily_dose")); filled.add("日用量") }
                                    if (doseUnit.isBlank() || doseUnit == "片") { val du = m.optString("dose_unit"); if (du.isNotEmpty()) { doseUnit = du; filled.add("剂量单位") } }
                                    if (m.optString("image_url").isNotEmpty() && imageUrl.isEmpty()) imageUrl = m.optString("image_url")
                                    barcodeHint = "已匹配家庭药箱记录：${filled.joinToString("、")}（请核对）"
                                }
                            }
                            "online" -> {
                                val d = r.optJSONObject("data")
                                if (d != null) {
                                    if (name.isBlank() && d.optString("name").isNotEmpty()) { name = d.optString("name"); filled.add("名称") }
                                    if (spec.isBlank() && d.optString("spec").isNotEmpty()) { spec = d.optString("spec"); filled.add("规格") }
                                    if (manufacturer.isBlank() && d.optString("manufacturer").isNotEmpty()) { manufacturer = d.optString("manufacturer"); filled.add("厂家") }
                                    if (approvalNo.isBlank() && d.optString("approval_no").isNotEmpty()) { approvalNo = d.optString("approval_no"); filled.add("批准文号") }
                                    barcodeHint = if (filled.isNotEmpty()) "本地药品库匹配：${filled.joinToString("、")}（请核对，未识别的字段可拍照识别补充）"
                                                  else "本地药品库返回：${d.optString("raw_title")}（请核对）"
                                }
                            }
                            else -> barcodeHint = "条码已记录保存。${r.optString("message")}；建议用「拍照录入」AI 识别药盒补充信息，保存后全家扫码即可秒出全部信息"
                        }
                    } else {
                        barcodeHint = "未识别到有效条码，请对准药盒侧面的商品条码（EAN-13）或本位码条码重新扫描"
                    }
                } catch (e: Exception) { barcodeHint = "扫码失败：${e.message}" }
                bcBusy = false
            }
        }
    }
    val bcPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) { val u = newPhotoUri(ctx); bcPhotoUri = u; bcCamLauncher.launch(u) }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("添加药品") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 拍照录入（自动识别药品信息）
                MedPhotoPicker(
                    imageUrl.ifEmpty { null },
                    onResult = { url, ocr, source ->
                        imageUrl = url
                        val p = ocr
                        if (p != null) {
                            val filled = mutableListOf<String>()
                            if (name.isBlank() && p.name.isNotEmpty()) { name = p.name; filled.add("名称") }
                            if (spec.isBlank() && p.spec.isNotEmpty()) { spec = p.spec; filled.add("规格") }
                            if (expiry.isBlank() && p.expiry.isNotEmpty()) { expiry = p.expiry; filled.add("有效期") }
                            ocrHint = if (filled.isNotEmpty())
                                "已识别（$source）：${filled.joinToString("、")}（请核对）"
                            else
                                "已识别文字（$source）但未提取到药品字段，请手动填写"
                        } else {
                            ocrHint = if (source.isNotEmpty()) "$source 未识别到信息，请手动填写" else "未识别到药品信息，请手动填写"
                        }
                    },
                    onClear = { imageUrl = "" }
                )
                if (ocrHint.isNotEmpty()) Text(ocrHint, fontSize = 12.sp, color = Gray, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                // 扫条码（商品条码 EAN-13 / 本位码）自动录入
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            val u = newPhotoUri(ctx); bcPhotoUri = u; bcCamLauncher.launch(u)
                        } else bcPermLauncher.launch(android.Manifest.permission.CAMERA)
                    }, enabled = !bcBusy) {
                        Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (bcBusy) "扫码查询中…" else "扫条码录入")
                    }
                    if (barcode.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(if (barcode.length == 14 && barcode.startsWith("86")) "本位码 $barcode" else barcode, fontSize = 12.sp, color = Blue)
                    }
                }
                if (barcodeHint.isNotEmpty()) Text(barcodeHint, fontSize = 12.sp, color = Gray, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(name, { name = it }, label = { Text("药品名称 *") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(spec, { spec = it }, label = { Text("规格（如 0.25g×24片）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(manufacturer, { manufacturer = it }, label = { Text("生产厂家（选填）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(approvalNo, { approvalNo = it }, label = { Text("批准文号 / 本位码（选填）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(expiry, { expiry = it }, label = { Text("有效期至（2026-12-31）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(stock, { stock = it }, label = { Text("库存数量") },
                        modifier = Modifier.weight(1f), singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(unit, { unit = it }, label = { Text("单位") },
                        modifier = Modifier.weight(1f), singleLine = true)
                }
                Spacer(Modifier.height(8.dp))
                Text("日用量（用于推算何时该开药）", fontSize = 13.sp, color = Gray)
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(dailyDose, { dailyDose = it }, label = { Text("每日用量") },
                        modifier = Modifier.weight(1f), singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(doseUnit, { doseUnit = it }, label = { Text("单位") },
                        modifier = Modifier.weight(1f), singleLine = true)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(location, { location = it }, label = { Text("存放位置（如 客厅药箱）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(notes, { notes = it }, label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (err.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(err, color = Red, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) { err = "请填写药品名称"; return@TextButton }
                scope.launch {
                    try {
                        val body = JSONObject()
                            .put("name", name.trim()).put("spec", spec.trim())
                            .put("expiry_date", expiry.trim())
                            .put("stock_qty", stock.toDoubleOrNull() ?: 0.0)
                            .put("unit", unit.trim()).put("daily_dose", dailyDose.toDoubleOrNull() ?: 0.0)
                            .put("dose_unit", doseUnit.trim()).put("location", location.trim())
                            .put("notes", notes.trim()).put("image_url", imageUrl)
                            .put("barcode", barcode).put("manufacturer", manufacturer.trim())
                            .put("approval_no", approvalNo.trim())
                        if (userId >= 0) body.put("user_id", userId)
                        ApiClient.post("/api/medicines", body)
                        onClose()
                    } catch (e: Exception) { err = e.message ?: "" }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } }
    )
}

@Composable
private fun EditStockDialog(m: JSONObject, onClose: () -> Unit) {
    var stock by remember { mutableStateOf(fmtNum(m.optDouble("stock_qty"))) }
    var err by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("修改库存 · ${m.optString("name")}") },
        text = {
            Column {
                Text("当前单位：${m.optString("unit", "盒")}", fontSize = 14.sp, color = Gray)
                OutlinedTextField(stock, { stock = it }, label = { Text("剩余数量") }, singleLine = true)
                if (err.isNotEmpty()) Text(err, color = Red, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    try {
                        ApiClient.patch("/api/medicines/${m.getInt("id")}",
                            JSONObject().put("stock_qty", stock.toDoubleOrNull() ?: 0.0))
                        onClose()
                    } catch (e: Exception) { err = e.message ?: "" }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } }
    )
}

// ---------- 用药计划 ----------
@Composable
fun PlansTab(user: JSONObject, userId: Int = -1, readOnly: Boolean = false) {
    val ctx = LocalContext.current
    val path = if (userId >= 0) "/api/schedules?user_id=$userId" else "/api/schedules"
    var plans by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var err by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var delPlan by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            plans = (ApiClient.get(path) as? JSONArray)?.objs() ?: emptyList()
            err = ""
        } catch (e: Exception) { err = e.message ?: "" }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("用药计划（${plans.size}）", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!readOnly) Button(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, null); Text("新建")
            }
        }
        Text("到点会自动弹出服药提醒", fontSize = 13.sp, color = Gray)
        Spacer(Modifier.height(8.dp))
        if (err.isNotEmpty()) Text(err, color = Red)
        if (plans.isEmpty() && err.isEmpty()) Text("暂无计划，点右上角「新建」", color = Gray, modifier = Modifier.padding(16.dp))
        LazyColumn {
            items(plans) { p -> PlanCard(p, readOnly, onToggle = { en -> scope.launch {
                try {
                    ApiClient.patch("/api/schedules/${p.getInt("id")}", JSONObject().put("enabled", en))
                    load(); refreshReminders(ctx)
                } catch (e: Exception) { err = e.message ?: "" }
            } }, onDelete = { delPlan = p }) }
        }
    }

    if (showAdd) AddPlanDialog(userId) { showAdd = false; scope.launch { load(); refreshReminders(ctx) } }
    delPlan?.let { p ->
        AlertDialog(
            onDismissRequest = { delPlan = null },
            title = { Text("删除计划") },
            text = { Text("确定删除「${p.optString("medicine_name")}」的用药计划吗？") },
            confirmButton = {
                TextButton(onClick = {
                    delPlan = null
                    scope.launch {
                        try { ApiClient.delete("/api/schedules/${p.getInt("id")}"); load(); refreshReminders(ctx) }
                        catch (e: Exception) { err = e.message ?: "" }
                    }
                }) { Text("删除", color = Red) }
            },
            dismissButton = { TextButton(onClick = { delPlan = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun PlanCard(p: JSONObject, readOnly: Boolean, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    val times = p.optJSONArray("times")?.let { (0 until it.length()).joinToString("  ") { i -> it.optString(i) } } ?: ""
    val freq = if (p.optString("frequency") == "interval") "隔 ${p.optInt("interval_days", 1)} 天一次" else "每天"
    val enabled = p.optInt("enabled", 1) == 1
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.optString("medicine_name"), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("$times · 每次 ${fmtNum(p.optDouble("dose_per_time", 1.0))} ${p.optString("dose_unit", "片")}", fontSize = 15.sp)
                Text("$freq · ${p.optString("start_date")} 起" +
                    (if (p.optString("end_date").isNotEmpty()) " 至 ${p.optString("end_date")}" else ""), fontSize = 13.sp, color = Gray)
            }
            if (readOnly) {
                Text(if (enabled) "启用中" else "已停用", color = if (enabled) Green else Gray, fontSize = 14.sp)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Switch(checked = enabled, onCheckedChange = onToggle)
                    TextButton(onClick = onDelete) { Text("删除", color = Red, fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun AddPlanDialog(userId: Int, onClose: () -> Unit) {
    var meds by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var medIdx by remember { mutableIntStateOf(-1) }
    var timesText by remember { mutableStateOf("08:00") }
    var dose by remember { mutableStateOf("1") }
    var doseUnit by remember { mutableStateOf("片") }
    var interval by remember { mutableStateOf(false) }
    var intervalDays by remember { mutableStateOf("2") }
    var start by remember { mutableStateOf(todayStr()) }
    var end by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var rxBusy by remember { mutableStateOf(false) }
    var rxHint by remember { mutableStateOf("") }
    var rxPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        try { meds = (ApiClient.get("/api/medicines") as? JSONArray)?.objs() ?: emptyList() }
        catch (e: Exception) { err = e.message ?: "" }
    }

    // 处方拍照识别
    val rxCamLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && rxPhotoUri != null) {
            scope.launch {
                rxBusy = true; rxHint = ""
                try {
                    val b64 = imageToBase64(ctx, rxPhotoUri!!)
                    // 优先服务端 AI 识别处方
                    var medName = ""; var timesPerDay = 0; var dosePerTime = ""; var unit = ""
                    var aiUsed = false
                    try {
                        val r = ApiClient.post("/api/ai/recognize",
                            JSONObject().put("image", b64).put("type", "prescription"), 120000) as JSONObject
                        val arr = r.optJSONArray("meds")
                        if (arr != null && arr.length() > 0) {
                            val m = arr.getJSONObject(0)
                            medName = m.optString("name")
                            timesPerDay = m.optInt("times_per_day")
                            dosePerTime = m.optString("dose_per_time")
                            unit = m.optString("dose_unit", "片")
                            aiUsed = true
                        }
                    } catch (_: Exception) {
                        // 降级本地 OCR
                        try {
                            val text = OcrHelper.recognize(ctx, rxPhotoUri!!)
                            val p = OcrHelper.parsePrescription(text)
                            medName = p.medName; timesPerDay = p.timesPerDay
                            dosePerTime = p.dosePerTime; unit = p.doseUnit
                        } catch (_: Exception) { }
                    }
                    val filled = mutableListOf<String>()
                    if (medName.isNotEmpty()) {
                        val idx = meds.indexOfFirst {
                            val n = it.optString("name")
                            n.contains(medName) || medName.contains(n)
                        }
                        if (idx >= 0) { medIdx = idx; filled.add("药品：${meds[idx].optString("name")}") }
                        else rxHint = "识别到「$medName」但未在药品库中匹配，请先添加该药品"
                    }
                    if (timesPerDay > 0) { timesText = OcrHelper.defaultTimes(timesPerDay); filled.add("每日${timesPerDay}次") }
                    if (dosePerTime.isNotEmpty()) { dose = dosePerTime; filled.add("每次$dosePerTime$unit") }
                    if (unit.isNotEmpty()) doseUnit = unit
                    if (rxHint.isEmpty()) rxHint = if (filled.isNotEmpty()) "已识别${if (aiUsed) "(AI)" else ""}：${filled.joinToString("，")}（请核对）" else "未识别到处方信息，请手动填写"
                } catch (e: Exception) { rxHint = "识别失败：${e.message}" }
                rxBusy = false
            }
        }
    }
    val rxPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) { val u = newPhotoUri(ctx); rxPhotoUri = u; rxCamLauncher.launch(u) }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("新建用药计划") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // 扫描处方自动识别
                OutlinedButton(onClick = {
                    if (rxBusy) return@OutlinedButton
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        val u = newPhotoUri(ctx); rxPhotoUri = u; rxCamLauncher.launch(u)
                    } else rxPermLauncher.launch(Manifest.permission.CAMERA)
                }) {
                    Icon(Icons.Filled.CameraAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (rxBusy) "识别中…" else "扫描处方自动识别")
                }
                if (rxHint.isNotEmpty()) Text(rxHint, fontSize = 12.sp, color = Gray, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(10.dp))
                // 药品下拉选择
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = if (medIdx in meds.indices) meds[medIdx].optString("name") else "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("选择药品 *") },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        placeholder = { if (meds.isEmpty()) Text("请先添加药品") else Text("点击选择") }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        meds.forEachIndexed { i, m ->
                            DropdownMenuItem(text = { Text(m.optString("name")) }, onClick = { medIdx = i; expanded = false })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("服药时间（逗号分隔，如 08:00,12:00,20:00）", fontSize = 13.sp, color = Gray)
                OutlinedTextField(timesText, { timesText = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    OutlinedTextField(dose, { dose = it }, label = { Text("每次剂量") },
                        modifier = Modifier.weight(1f), singleLine = true)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(doseUnit, { doseUnit = it }, label = { Text("单位") },
                        modifier = Modifier.weight(1f), singleLine = true)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !interval, onClick = { interval = false }, label = { Text("每天") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = interval, onClick = { interval = true }, label = { Text("隔 N 天") })
                    if (interval) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(intervalDays, { intervalDays = it },
                            label = { Text("N 天") }, modifier = Modifier.width(90.dp), singleLine = true)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(start, { start = it }, label = { Text("开始日期（如 2026-10-17）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(end, { end = it }, label = { Text("结束日期（选填）") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (err.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(err, color = Red, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                err = ""
                if (medIdx !in meds.indices) { err = "请选择药品"; return@TextButton }
                val times = timesText.split(",", "，", " ").map { it.trim() }
                    .filter { Regex("\\d{1,2}:\\d{2}").matches(it) }
                if (times.isEmpty()) { err = "请填写正确的服药时间（HH:mm）"; return@TextButton }
                if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(start)) { err = "开始日期格式：2026-10-17"; return@TextButton }
                scope.launch {
                    try {
                        val body = JSONObject()
                            .put("medicine_id", meds[medIdx].getInt("id"))
                            .put("times", JSONArray(times))
                            .put("dose_per_time", dose.toDoubleOrNull() ?: 1.0)
                            .put("dose_unit", doseUnit.trim())
                            .put("frequency", if (interval) "interval" else "daily")
                            .put("interval_days", intervalDays.toIntOrNull() ?: 2)
                            .put("start_date", start)
                        if (end.isNotBlank()) body.put("end_date", end.trim())
                        ApiClient.post("/api/schedules", body)
                        onClose()
                    } catch (e: Exception) { err = e.message ?: "" }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } }
    )
}

// ---------- 我的 ----------
@Composable
fun ProfileTab(user: JSONObject, onLogout: () -> Unit, onAdmin: () -> Unit) {
    val ctx = LocalContext.current
    var family by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { family = ApiClient.get("/api/me") as? JSONObject } catch (e: Exception) { err = e.message ?: "" }
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("我的", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(user.optString("display_name"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    if (user.optString("role") == "admin")
                        Text("管理员", color = Blue, fontSize = 13.sp,
                            modifier = Modifier.padding(4.dp))
                }
                Text("账号：${user.optString("username")}", fontSize = 14.sp, color = Gray)
                if (user.optInt("age", -1) >= 0) Text("年龄：${user.optInt("age")} 岁", fontSize = 14.sp, color = Gray)
                family?.optJSONObject("family")?.let { f ->
                    Spacer(Modifier.height(8.dp))
                    Text("家庭：${f.optString("name")}", fontSize = 16.sp)
                    Text("邀请码：${f.optString("invite_code")}（新成员注册时填此码加入家庭）", fontSize = 14.sp, color = Gray)
                }
                if (err.isNotEmpty()) Text(err, color = Red, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (user.optString("role") == "admin") {
            Button(onClick = onAdmin, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("管理员模式 · 查看全家成员", fontSize = 17.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
        OutlinedButton(
            onClick = { Store.logout(ctx); onLogout() },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("退出登录", fontSize = 17.sp) }
        Spacer(Modifier.height(24.dp))
        Text("提示：服药提醒由手机本地闹钟触发，请保持 APP 后台运行并允许通知与精确闹钟权限",
            fontSize = 12.sp, color = Gray)
    }
}

// ---------- 管理员模式 ----------
@Composable
fun AdminScreen(admin: JSONObject, onBack: () -> Unit) {
    var overview by remember { mutableStateOf<JSONObject?>(null) }
    var members by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selId by remember { mutableIntStateOf(-1) }
    var subTab by remember { mutableIntStateOf(0) }
    var err by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            overview = ApiClient.get("/api/admin/overview") as? JSONObject
            members = overview?.optJSONArray("members")?.objs() ?: emptyList()
            if (members.isNotEmpty() && selId < 0) selId = members[0].optInt("id")
        } catch (e: Exception) { err = e.message ?: "" }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("管理员模式 · ${admin.optString("display_name")}", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        if (err.isNotEmpty()) Text(err, color = Red, modifier = Modifier.padding(16.dp))

        // 成员选择
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            members.forEach { m ->
                FilterChip(
                    selected = selId == m.optInt("id"),
                    onClick = { selId = m.optInt("id") },
                    label = { Text(m.optString("display_name") + if (m.optInt("today_pending", 0) > 0) " (${m.optInt("today_pending")})" else "") },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        // 子页签
        TabRow(selectedTabIndex = subTab) {
            listOf("今日", "药品", "计划", "全家预警").forEachIndexed { i, label ->
                Tab(selected = subTab == i, onClick = { subTab = i }, text = { Text(label) })
            }
        }
        val member = members.find { it.optInt("id") == selId }
        Box(Modifier.weight(1f)) {
            when {
                overview == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                subTab == 3 -> AlertsPanel(overview!!)
                member != null -> when (subTab) {
                    0 -> TodayTab(member, member.optInt("id"))
                    1 -> MedsTab(member, member.optInt("id"), readOnly = true)
                    else -> PlansTab(member, member.optInt("id"), readOnly = true)
                }
            }
        }
    }
}

/** 全家开药/效期预警 */
@Composable
private fun AlertsPanel(overview: JSONObject) {
    val refill = overview.optJSONObject("alerts")?.optJSONArray("refill")?.objs() ?: emptyList()
    val expiry = overview.optJSONObject("alerts")?.optJSONArray("expiry")?.objs() ?: emptyList()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("该开药了（按日用量估算 ≤7 天）", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Orange)
            if (refill.isEmpty()) Text("暂无预警 ✓", color = Gray, modifier = Modifier.padding(vertical = 8.dp))
        }
        items(refill) { r ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text(r.optString("name"), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("还能服 ${r.optInt("days_left")} 天", color = if (r.optInt("days_left") <= 3) Red else Orange,
                            fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("${r.optString("owner")} · 剩 ${fmtNum(r.optDouble("stock_qty"))} ${r.optString("unit")} · 日用 ${fmtNum(r.optDouble("daily_dose"))}",
                        fontSize = 13.sp, color = Gray)
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Text("效期预警（≤30 天或已过期）", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Orange)
            if (expiry.isEmpty()) Text("暂无预警 ✓", color = Gray, modifier = Modifier.padding(vertical = 8.dp))
        }
        items(expiry) { e ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text(e.optString("name"), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (e.optBoolean("expired")) Text("已过期", color = Red, fontWeight = FontWeight.Bold)
                        else Text("${e.optInt("days_left")} 天后到期", color = Orange)
                    }
                    Text("${e.optString("owner")} · 有效期至 ${e.optString("expiry_date")}", fontSize = 13.sp, color = Gray)
                }
            }
        }
    }
}

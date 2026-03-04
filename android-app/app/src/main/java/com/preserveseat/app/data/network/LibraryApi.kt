package com.preserveseat.app.data.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.preserveseat.app.data.model.OccupyBlock
import com.preserveseat.app.data.model.RoomOption
import com.preserveseat.app.data.model.SeatOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import kotlin.coroutines.resume

data class SessionInfo(
    val token: String,
    val cookieHeader: String
)

data class UserInfo(
    val accNo: Int?
)

class LibraryApi(
    context: Context,
    private val logger: ((String, String) -> Unit)? = null
) {
    private val appContext: Context = context.applicationContext
    private val client = OkHttpClient()
    private val jsonType = "application/json;charset=UTF-8".toMediaType()

    suspend fun refreshSessionByAccountPassword(account: String, password: String): SessionInfo? {
        if (account.isBlank() || password.isBlank()) {
            writeLog("ERROR", "自动登录失败：账号或密码为空")
            return null
        }

        writeLog("INFO", "开始自动登录获取会话")

        val strategies = listOf(true, false, true)
        for ((idx, aggressiveReturnHome) in strategies.withIndex()) {
            writeLog("INFO", "自动登录尝试 ${idx + 1}/${strategies.size}")
            val session = runCatching { loginWithWebView(account, password, aggressiveReturnHome) }.getOrNull()
            if (session == null) {
                writeLog("ERROR", "第${idx + 1}次自动登录未拿到会话")
                continue
            }
            if (validateSession(session.token, session.cookieHeader)) {
                writeLog("SUCCESS", "自动登录并校验会话成功")
                return session
            }
            writeLog("ERROR", "第${idx + 1}次自动登录后会话校验失败")
        }
        return null
    }

    suspend fun validateSession(token: String, cookieHeader: String): Boolean = withContext(Dispatchers.IO) {
        if (token.isBlank() || cookieHeader.isBlank()) return@withContext false
        val request = Request.Builder()
            .url("https://libbooking.gzhu.edu.cn/ic-web/auth/userInfo")
            .get()
            .header("accept", "application/json, text/plain, */*")
            .header("lan", "1")
            .header("token", token)
            .header("cookie", cookieHeader)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext false
            val body = resp.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext false
            return@withContext json.optInt("code", -1) == 0
        }
    }

    suspend fun getUserInfo(token: String, cookieHeader: String): UserInfo? = withContext(Dispatchers.IO) {
        if (token.isBlank() || cookieHeader.isBlank()) return@withContext null
        val request = Request.Builder()
            .url("https://libbooking.gzhu.edu.cn/ic-web/auth/userInfo")
            .get()
            .header("accept", "application/json, text/plain, */*")
            .header("lan", "1")
            .header("token", token)
            .header("cookie", cookieHeader)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body?.string().orEmpty()
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
            if (root.optInt("code", -1) != 0) return@withContext null
            val data = root.optJSONObject("data") ?: return@withContext null
            val accNoText = data.optString("accNo")
            return@withContext UserInfo(accNo = accNoText.toIntOrNull())
        }
    }

    private suspend fun loginWithWebView(account: String, password: String, aggressiveReturnHome: Boolean): SessionInfo? {
        return suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val cookieManager = CookieManager.getInstance()
            var submitted = false
            var finished = false
            var pollCount = 0
            var tokenFromRequest = ""
            var homeReloaded = false

            fun finish(result: SessionInfo?) {
                if (finished) return
                finished = true
                if (cont.isActive) cont.resume(result)
            }

            handler.post {
                cookieManager.setAcceptCookie(true)
                cookieManager.removeAllCookies(null)
                cookieManager.flush()

                val webView = WebView(appContext)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                fun extractTokenAndCookie() {
                    fun buildSession(token: String) {
                        if (token.isBlank()) return
                        val cookieHeader = cookieManager.getCookie("https://libbooking.gzhu.edu.cn").orEmpty()
                        if (cookieHeader.isBlank()) return
                        writeLog("INFO", "已提取到 token 与 cookie")
                        runCatching { webView.destroy() }
                        finish(SessionInfo(token = token, cookieHeader = cookieHeader))
                    }

                    if (tokenFromRequest.isNotBlank()) {
                        buildSession(tokenFromRequest)
                        return
                    }

                    webView.evaluateJavascript(
                        "(function(){try{return sessionStorage.getItem('token') || localStorage.getItem('token') || '';}catch(e){return '';}})();"
                    ) { tokenRaw ->
                        val token = tokenRaw.orEmpty().trim().trim('"')
                        buildSession(token)
                    }
                }

                fun trySubmitLoginForm() {
                    val js = """
                        (function() {
                          const user = document.querySelector('#un')
                            || document.querySelector('input[name="un"]')
                            || document.querySelector('#username')
                            || document.querySelector('input[name="username"]');
                          const pwd = document.querySelector('#pd')
                            || document.querySelector('input[name="pd"]')
                            || document.querySelector('#password')
                            || document.querySelector('input[name="password"]');
                          const btn = document.querySelector('#index_login_btn')
                            || document.querySelector('button[type="submit"]')
                            || document.querySelector('input[type="submit"]');
                          if (!user || !pwd || !btn) return 'no_form';
                          user.focus(); user.value = '${jsEscape(account)}'; user.setAttribute('value','${jsEscape(account)}');
                          user.dispatchEvent(new Event('input', { bubbles: true }));
                          user.dispatchEvent(new Event('change', { bubbles: true }));
                          pwd.focus(); pwd.value = '${jsEscape(password)}'; pwd.setAttribute('value','${jsEscape(password)}');
                          pwd.dispatchEvent(new Event('input', { bubbles: true }));
                          pwd.dispatchEvent(new Event('change', { bubbles: true }));
                          btn.click();
                          return 'submitted';
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js) { ret ->
                        if (ret?.contains("submitted") == true) {
                            submitted = true
                            writeLog("INFO", "已自动填写并提交登录表单")
                            handler.postDelayed({
                                if (!finished && !homeReloaded) {
                                    homeReloaded = true
                                    writeLog("INFO", "提交后主动回主页触发会话同步")
                                    webView.loadUrl("https://libbooking.gzhu.edu.cn/#/ic/home")
                                }
                            }, 7000)
                            if (aggressiveReturnHome) {
                                handler.postDelayed({
                                    if (!finished) {
                                        writeLog("INFO", "二次回主页以提升会话同步成功率")
                                        webView.loadUrl("https://libbooking.gzhu.edu.cn/#/ic/home")
                                    }
                                }, 10500)
                            }
                        } else if (ret?.contains("no_form") == true) {
                            writeLog("INFO", "当前页面未找到登录表单，继续等待")
                        }
                    }
                }

                val pollTask = object : Runnable {
                    override fun run() {
                        if (finished) return
                        pollCount += 1
                        if (!submitted) {
                            trySubmitLoginForm()
                        }
                        extractTokenAndCookie()
                        if (!finished && pollCount < 45) {
                            handler.postDelayed(this, 1000)
                        }
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                        val headers = request?.requestHeaders.orEmpty()
                        val token = headers["token"].orEmpty()
                        if (token.isNotBlank() && tokenFromRequest.isBlank()) {
                            tokenFromRequest = token
                            writeLog("INFO", "已从请求头捕获 token")
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url?.toString().orEmpty()
                        if (target.startsWith("http://libbooking.gzhu.edu.cn")) {
                            val httpsUrl = target.replaceFirst("http://", "https://")
                            writeLog("INFO", "检测到HTTP跳转，强制升级HTTPS: $httpsUrl")
                            view?.loadUrl(httpsUrl)
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        writeLog("INFO", "页面加载完成: ${url.orEmpty()}")
                        if (url.orEmpty().startsWith("chrome-error://")) {
                            writeLog("ERROR", "WebView进入错误页，通常是登录重定向到HTTP被阻断")
                        }
                        handler.post { if (!finished) extractTokenAndCookie() }
                    }
                }

                writeLog("INFO", "加载图书馆主页并准备自动登录")
                webView.loadUrl("https://libbooking.gzhu.edu.cn/#/ic/home")
                handler.postDelayed(pollTask, 1200)

                handler.postDelayed({
                    if (!finished) {
                        writeLog("ERROR", "WebView 自动登录超时")
                        runCatching { webView.destroy() }
                        finish(null)
                    }
                }, 45000)
            }
        }
    }

    private fun writeLog(level: String, message: String) {
        logger?.invoke(level, message)
        when (level) {
            "ERROR" -> Log.e("LibraryApi", message)
            else -> Log.i("LibraryApi", message)
        }
    }

    private fun jsEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    suspend fun reserve(
        token: String,
        cookieHeader: String,
        appAccNo: Int,
        devId: Int,
        beginDateTime: String,
        endDateTime: String,
        captcha: String
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("sysKind", 8)
            put("appAccNo", appAccNo)
            put("memberKind", 1)
            put("resvMember", JSONArray().put(appAccNo))
            put("resvBeginTime", beginDateTime)
            put("resvEndTime", endDateTime)
            put("testName", "")
            put("captcha", captcha)
            put("resvProperty", 0)
            put("resvDev", JSONArray().put(devId))
            put("memo", "")
        }
        val request = Request.Builder()
            .url("https://libbooking.gzhu.edu.cn/ic-web/reserve")
            .post(payload.toString().toRequestBody(jsonType))
            .header("accept", "application/json, text/plain, */*")
            .header("content-type", "application/json;charset=UTF-8")
            .header("origin", "https://libbooking.gzhu.edu.cn")
            .header("lan", "1")
            .header("token", token)
            .header("cookie", cookieHeader)
            .build()
        client.newCall(request).execute().use { resp ->
            return@withContext resp.code to (resp.body?.string().orEmpty())
        }
    }

    suspend fun queryDayBlocks(
        token: String,
        cookieHeader: String,
        day: LocalDate,
        roomId: Int,
        devId: Int,
        seatCode: String
    ): List<OccupyBlock> = withContext(Dispatchers.IO) {
        val seats = queryRoomSeats(token, cookieHeader, roomId, day)
        val target = seats.firstOrNull { it.devId == devId }
            ?: seats.firstOrNull { it.seatCode == seatCode }
        val blocks = target?.blocks.orEmpty().distinctBy { "${it.start}-${it.end}" }.sortedBy { it.start }
        writeLog("INFO", "占用接口返回: room=$roomId seat=$seatCode devId=$devId matched=${blocks.size}")
        return@withContext blocks
    }

    suspend fun queryRoomTree(token: String, cookieHeader: String): List<RoomOption> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://libbooking.gzhu.edu.cn/ic-web/seatMenu")
            .get()
            .header("accept", "application/json, text/plain, */*")
            .header("lan", "1")
            .header("token", token)
            .header("cookie", cookieHeader)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                writeLog("ERROR", "seatMenu接口失败: http=${resp.code}")
                return@withContext emptyList()
            }
            val root = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull() ?: return@withContext emptyList()
            val code = root.optInt("code", -1)
            if (code != 0) {
                writeLog("ERROR", "seatMenu业务失败: code=$code message=${root.optString("message")}")
                return@withContext emptyList()
            }
            val data = root.optJSONArray("data") ?: JSONArray()
            val rooms = mutableListOf<RoomOption>()
            parseRoomTree(data, parentPath = "", out = rooms)
            return@withContext rooms.sortedBy { it.pathName }
        }
    }

    suspend fun queryRoomSeats(
        token: String,
        cookieHeader: String,
        roomId: Int,
        day: LocalDate
    ): List<SeatOption> = withContext(Dispatchers.IO) {
        val dateText = day.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val url = "https://libbooking.gzhu.edu.cn/ic-web/reserve?roomIds=$roomId&resvDates=$dateText&sysKind=8"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("accept", "application/json, text/plain, */*")
            .header("lan", "1")
            .header("token", token)
            .header("cookie", cookieHeader)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                writeLog("ERROR", "room座位接口失败: http=${resp.code} url=$url")
                return@withContext emptyList()
            }
            val text = resp.body?.string().orEmpty()
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext emptyList()
            val code = root.optInt("code", -1)
            val message = root.optString("message")
            if (isFrequencyBlocked(message)) {
                writeLog("ERROR", "room座位接口触发限频: room=$roomId message=$message")
                delay(1200)
            }
            if (code != 0) {
                writeLog("ERROR", "room座位业务失败: code=$code message=$message")
                return@withContext emptyList()
            }
            val data = root.optJSONArray("data") ?: JSONArray()
            val seats = mutableListOf<SeatOption>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val devId = item.optInt("devId", -1)
                val devName = item.optString("devName")
                if (devId <= 0 || devName.isBlank()) continue
                val roomIdItem = item.optInt("roomId", roomId)
                val roomName = item.optString("roomName")
                val openStart = item.optString("openStart", "08:30")
                val openEnd = item.optString("openEnd", "22:15")
                val rule = item.optJSONObject("resvRule")
                val intervalMinutes = rule?.optInt("timeInterval", 5)?.takeIf { it > 0 } ?: 5
                val minResvMinutes = rule?.optInt("minResvTime", 60)?.takeIf { it > 0 } ?: 60
                val maxResvMinutes = rule?.optInt("maxResvTime", 240)?.takeIf { it > 0 } ?: 240
                val resvInfo = item.optJSONArray("resvInfo") ?: JSONArray()
                seats += SeatOption(
                    devId = devId,
                    seatCode = devName,
                    roomId = roomIdItem,
                    roomName = roomName,
                    openStart = openStart,
                    openEnd = openEnd,
                    intervalMinutes = intervalMinutes,
                    minResvMinutes = minResvMinutes,
                    maxResvMinutes = maxResvMinutes,
                    blocks = parseSeatBlocks(resvInfo)
                )
            }
            writeLog("INFO", "room座位接口返回: room=$roomId date=$dateText seats=${seats.size}")
            return@withContext seats.sortedBy { it.seatCode }
        }
    }

    private fun extractResvArray(root: JSONObject): JSONArray {
        val dataAny = root.opt("data")
        if (dataAny is JSONArray) return dataAny
        if (dataAny is JSONObject) {
            val candidates = listOf("records", "list", "rows", "items", "resvInfo", "data")
            for (key in candidates) {
                val arr = dataAny.optJSONArray(key)
                if (arr != null) return arr
            }
        }
        return JSONArray()
    }

    private fun parseRoomTree(nodes: JSONArray, parentPath: String, out: MutableList<RoomOption>) {
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            val id = node.optInt("id", -1)
            val name = node.optString("name")
            val children = node.optJSONArray("children")
            val path = if (parentPath.isBlank()) name else "$parentPath/$name"
            if (children != null && children.length() > 0) {
                parseRoomTree(children, path, out)
                continue
            }
            if (id <= 0 || name.isBlank()) continue
            out += RoomOption(
                roomId = id,
                roomName = name,
                pathName = path
            )
        }
    }

    private fun parseSeatBlocks(resvInfo: JSONArray): List<OccupyBlock> {
        val blocks = mutableListOf<OccupyBlock>()
        for (index in 0 until resvInfo.length()) {
            val row = resvInfo.optJSONObject(index) ?: continue
            val begin = firstPresentValue(row, listOf("startTime", "resvBeginTime", "beginTime"))
            val end = firstPresentValue(row, listOf("endTime", "resvEndTime", "finishTime"))
            val start = extractLocalTime(begin) ?: continue
            val finish = extractLocalTime(end) ?: continue
            blocks += OccupyBlock(start, finish)
        }
        return blocks
    }

    private suspend fun retryPageAfterBackoff(
        token: String,
        cookieHeader: String,
        dateText: String,
        page: Int,
        pageSize: Int,
        devId: Int,
        seatCode: String
    ): List<OccupyBlock> {
        repeat(3) { idx ->
            delay((idx + 1) * 1200L)
            val url = "https://libbooking.gzhu.edu.cn/ic-web/reserve/resvInfo?beginDate=$dateText&endDate=$dateText&page=$page&pageSize=$pageSize"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("accept", "application/json, text/plain, */*")
                .header("lan", "1")
                .header("token", token)
                .header("cookie", cookieHeader)
                .build()
            val parsed = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<OccupyBlock>()
                val text = resp.body?.string().orEmpty()
                val root = runCatching { JSONObject(text) }.getOrNull() ?: return@use emptyList<OccupyBlock>()
                val message = root.optString("message")
                if (isFrequencyBlocked(message)) return@use emptyList<OccupyBlock>()
                val dataArray = extractResvArray(root)
                parseResvInfo(dataArray, devId, seatCode)
            }
            if (parsed.isNotEmpty()) {
                writeLog("INFO", "占用接口限频重试成功: page=$page retry=${idx + 1} matched=${parsed.size}")
                return parsed
            }
        }
        writeLog("ERROR", "占用接口限频重试仍失败: page=$page")
        return emptyList()
    }

    private fun isFrequencyBlocked(message: String): Boolean {
        val lower = message.lowercase()
        return listOf("请求频繁", "操作频繁", "请稍后", "too frequent", "too many", "rate").any { lower.contains(it.lowercase()) }
    }

    private data class PageResult(
        val rawItems: Int,
        val matched: List<OccupyBlock>
    )

    private fun parseResvInfo(array: JSONArray, devId: Int, seatCode: String): List<OccupyBlock> {
        val result = mutableListOf<OccupyBlock>()
        val seatSuffix = seatCode.substringAfter('-', seatCode)
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val matched = isMatchedSeat(item, devId, seatCode, seatSuffix)
            if (!matched) continue

            val begin = firstPresentValue(item, listOf("resvBeginTime", "beginTime", "startTime", "resvStartTime"))
            val end = firstPresentValue(item, listOf("resvEndTime", "endTime", "finishTime", "resvFinishTime"))

            val startTime = extractLocalTime(begin) ?: continue
            val endTime = extractLocalTime(end) ?: continue
            result += OccupyBlock(startTime, endTime)
        }
        return result
    }

    private fun isMatchedSeat(item: JSONObject, devId: Int, seatCode: String, seatSuffix: String): Boolean {
        val topMatch = matchSeatFields(
            item.optInt("devId", item.optInt("devID", -1)),
            item.optString("devName"),
            item.optString("devNo"),
            item.optString("seatCode"),
            item.optString("seatNo"),
            devId,
            seatCode,
            seatSuffix
        )
        if (topMatch) return true

        val devList = item.optJSONArray("resvDevInfoList") ?: return false
        for (j in 0 until devList.length()) {
            val devObj = devList.optJSONObject(j) ?: continue
            val matched = matchSeatFields(
                devObj.optInt("devId", devObj.optInt("devID", -1)),
                devObj.optString("devName"),
                devObj.optString("devNo"),
                devObj.optString("seatCode"),
                devObj.optString("seatNo"),
                devId,
                seatCode,
                seatSuffix
            )
            if (matched) return true
        }
        return false
    }

    private fun matchSeatFields(
        candidateDevId: Int,
        devName: String,
        devNo: String,
        seatCodeField: String,
        seatNo: String,
        devId: Int,
        seatCode: String,
        seatSuffix: String
    ): Boolean {
        return candidateDevId == devId ||
            devName.contains(seatCode) ||
            devNo.contains(seatCode) ||
            seatCodeField.contains(seatCode) ||
            seatNo.contains(seatCode) ||
            devName.contains(seatSuffix) ||
            devNo.contains(seatSuffix) ||
            seatCodeField.contains(seatSuffix) ||
            seatNo.contains(seatSuffix)
    }

    private fun firstPresentValue(item: JSONObject, keys: List<String>): Any? {
        for (key in keys) {
            val value = item.opt(key)
            if (value != null && value != JSONObject.NULL) return value
        }
        return null
    }

    private fun extractLocalTime(raw: Any?): LocalTime? {
        if (raw == null) return null
        if (raw is Number) {
            val millis = raw.toLong()
            return runCatching {
                Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)
            }.getOrNull()
        }
        val text = raw.toString().trim()
        if (text.isBlank()) return null
        if (text.all { it.isDigit() }) {
            val numeric = text.toLongOrNull() ?: return null
            val millis = if (text.length >= 13) numeric else numeric * 1000
            return runCatching {
                Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)
            }.getOrNull()
        }
        val hhmmss = Regex("(\\d{2}:\\d{2}:\\d{2})").find(text)?.groupValues?.get(1)
        if (hhmmss != null) return runCatching { LocalTime.parse(hhmmss.take(5)) }.getOrNull()
        val hhmm = Regex("(\\d{2}:\\d{2})").find(text)?.groupValues?.get(1)
        if (hhmm != null) return runCatching { LocalTime.parse(hhmm) }.getOrNull()
        return null
    }
}

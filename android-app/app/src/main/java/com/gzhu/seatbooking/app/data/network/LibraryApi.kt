package com.gzhu.seatbooking.app.data.network

import android.content.Context
import android.util.Log
import com.gzhu.seatbooking.app.data.model.OccupyBlock
import com.gzhu.seatbooking.app.data.model.RoomOption
import com.gzhu.seatbooking.app.data.model.SeatOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.Function
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId

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
    private val client = OkHttpClient()
    private val jsonType = "application/json;charset=UTF-8".toMediaType()
    private val defaultServiceUrl = "http://libbooking.gzhu.edu.cn/authcenter/doAuth/4edbd40b8d1b4ef8970355950765d41f"
    private val loginRetryWaitMs = listOf(0L, 700L, 1300L)

    init {
        // Keep constructor signature stable while login implementation no longer needs WebView context.
        context.applicationContext
    }

    suspend fun refreshSessionByAccountPassword(account: String, password: String): SessionInfo? {
        if (account.isBlank() || password.isBlank()) {
            writeLog("ERROR", "自动登录失败：账号或密码为空")
            return null
        }

        writeLog("INFO", "开始纯HTTP自动登录获取会话")

        repeat(loginRetryWaitMs.size) { index ->
            val waitMs = loginRetryWaitMs[index]
            if (waitMs > 0) {
                writeLog("INFO", "自动登录第${index + 1}次尝试前等待 ${waitMs}ms")
                delay(waitMs)
            }
            writeLog("INFO", "自动登录尝试 ${index + 1}/${loginRetryWaitMs.size}")
            val session = runCatching { loginWithHttp(account, password) }.getOrNull()
            if (session == null) {
                writeLog("ERROR", "第${index + 1}次自动登录未拿到会话")
                return@repeat
            }
            if (validateSession(session.token, session.cookieHeader)) {
                writeLog("SUCCESS", "自动登录并校验会话成功")
                return session
            }
            writeLog("ERROR", "第${index + 1}次自动登录后会话校验失败")
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

    private suspend fun loginWithHttp(account: String, password: String): SessionInfo? = withContext(Dispatchers.IO) {
        writeLog("INFO", "[login-http] 建立独立cookie会话")
        val cookieManager = CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }
        val loginClient = OkHttpClient.Builder()
            .cookieJar(JavaNetCookieJar(cookieManager))
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val toLoginPage = discoverToLoginPage(loginClient)
        writeLog("INFO", "[login-http] auth入口: ${maskUrl(toLoginPage)}")
        val bootstrapResp = executeGet(loginClient, toLoginPage)
        val loginUrl = resolveLoginUrl(bootstrapResp)
        writeLog("INFO", "[login-http] CAS登录页: ${maskUrl(loginUrl)}")
        bootstrapResp.close()

        val loginPageResp = executeGet(loginClient, loginUrl)
        if (!loginPageResp.isSuccessful) {
            writeLog("ERROR", "登录页加载失败: status=${loginPageResp.code}")
            loginPageResp.close()
            return@withContext null
        }
        val loginHtml = loginPageResp.body?.string().orEmpty()
        loginPageResp.close()

        val form = parseHiddenInputs(loginHtml)
        val lt = form["lt"].orEmpty()
        val execution = form["execution"].orEmpty()
        val eventId = form["_eventId"].orEmpty().ifBlank { "submit" }
        writeLog("INFO", "[login-http] 登录字段: lt=${lt.length} execution=${execution.length} extra=${form.size}")
        if (lt.isBlank() || execution.isBlank()) {
            writeLog("ERROR", "登录页缺少 lt/execution 字段")
            return@withContext null
        }

        val desJsResp = executeGet(loginClient, "https://newcas.gzhu.edu.cn/cas/comm/js/des.js")
        val desJs = desJsResp.body?.string().orEmpty()
        desJsResp.close()
        if (desJs.isBlank()) {
            writeLog("ERROR", "获取 DES 脚本失败")
            return@withContext null
        }

        val rsa = runCatching { computeRsa(desJs, account + password + lt) }.getOrElse {
            writeLog("ERROR", "计算 rsa 失败: ${it.message.orEmpty()}")
            return@withContext null
        }
        writeLog("INFO", "[login-http] rsa计算完成: len=${rsa.length}")

        val bodyBuilder = FormBody.Builder(StandardCharsets.UTF_8)
            .add("rsa", rsa)
            .add("ul", account.length.toString())
            .add("pl", password.length.toString())
            .add("lt", lt)
            .add("execution", execution)
            .add("_eventId", eventId)

        form.forEach { (name, value) ->
            if (name !in setOf("rsa", "ul", "pl", "lt", "execution", "_eventId", "un", "pd", "username", "password") && value.isNotBlank()) {
                bodyBuilder.add(name, value)
            }
        }

        val postRequest = Request.Builder()
            .url(loginUrl)
            .post(bodyBuilder.build())
            .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("content-type", "application/x-www-form-urlencoded")
            .header("origin", "https://newcas.gzhu.edu.cn")
            .header("referer", loginUrl)
            .header("user-agent", defaultUserAgent())
            .build()

        val loginResp = loginClient.newCall(postRequest).execute()
        writeLog("INFO", "[login-http] CAS提交返回: status=${loginResp.code}")
        val finalResp = followRedirects(loginClient, loginResp, 12)
        writeLog("INFO", "[login-http] 重定向结束: final=${finalResp.code} ${maskUrl(finalResp.request.url.toString())}")
        finalResp.close()

        warmupAfterAuth(loginClient)

        val cookieHeader = buildCookieHeader(cookieManager)
        if (cookieHeader.isBlank()) {
            writeLog("ERROR", "登录后未获取到 libbooking cookie")
            return@withContext null
        }
        writeLog("INFO", "[login-http] cookie就绪: len=${cookieHeader.length}")

        val userInfoResp = Request.Builder()
            .url("https://libbooking.gzhu.edu.cn/ic-web/auth/userInfo")
            .get()
            .header("accept", "application/json, text/plain, */*")
            .header("lan", "1")
            .header("cookie", cookieHeader)
            .header("user-agent", defaultUserAgent())
            .build()

        client.newCall(userInfoResp).execute().use { resp ->
            if (!resp.isSuccessful) {
                writeLog("ERROR", "userInfo 校验失败: status=${resp.code}")
                return@withContext null
            }
            val root = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull() ?: return@withContext null
            if (root.optInt("code", -1) != 0) {
                writeLog("ERROR", "userInfo 返回非成功: code=${root.optInt("code", -1)}")
                return@withContext null
            }
            val token = root.optJSONObject("data")?.optString("token").orEmpty()
            if (token.isBlank()) {
                writeLog("ERROR", "userInfo 成功但未返回 token")
                return@withContext null
            }
            writeLog("INFO", "已通过纯HTTP链路拿到 token+cookie tokenLen=${token.length}")
            return@withContext SessionInfo(token = token, cookieHeader = cookieHeader)
        }
    }

    private fun discoverToLoginPage(loginClient: OkHttpClient): String {
        executeGet(loginClient, "https://libbooking.gzhu.edu.cn/ic-web/auth/userInfo").use { resp ->
            writeLog("INFO", "pre-userInfo status=${resp.code}")
        }
        executeGet(
            loginClient,
            "https://libbooking.gzhu.edu.cn/ic-web/auth/address?finalAddress=https:%2F%2Flibbooking.gzhu.edu.cn&errPageUrl=https:%2F%2Flibbooking.gzhu.edu.cn%2F%23%2Ferror&manager=false&consoleType=16"
        ).use { resp ->
            if (!resp.isSuccessful) return "http://libbooking.gzhu.edu.cn/authcenter/toLoginPage"
            val root = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull() ?: return "http://libbooking.gzhu.edu.cn/authcenter/toLoginPage"
            val signed = root.optString("data")
            if (signed.startsWith("http")) {
                writeLog("INFO", "[login-http] auth/address返回动态入口")
                return signed
            }
        }
        writeLog("INFO", "[login-http] auth/address不可用，回退默认入口")
        return "http://libbooking.gzhu.edu.cn/authcenter/toLoginPage"
    }

    private fun resolveLoginUrl(bootstrapResp: Response): String {
        val location = bootstrapResp.header("Location").orEmpty()
        if (location.isNotBlank()) {
            return bootstrapResp.request.url.resolve(location)?.toString() ?: location
        }
        val encodedService = URLEncoder.encode(defaultServiceUrl, StandardCharsets.UTF_8.toString())
        return "https://newcas.gzhu.edu.cn/cas/login?service=$encodedService"
    }

    private fun followRedirects(loginClient: OkHttpClient, start: Response, maxHops: Int): Response {
        var current = start
        repeat(maxHops) {
            val status = current.code
            val location = current.header("Location").orEmpty()
            writeLog(
                "INFO",
                "[login-http] redirect hop=${it + 1} status=$status from=${maskUrl(current.request.url.toString())} to=${maskUrl(location)}"
            )
            if (status !in setOf(301, 302, 303, 307, 308) || location.isBlank()) {
                return current
            }

            val nextUrl = current.request.url.resolve(location)
            if (nextUrl == null) {
                return current
            }
            val nextMethod = if (status in setOf(301, 302, 303)) "GET" else current.request.method
            val nextRequest = Request.Builder()
                .url(nextUrl)
                .method(nextMethod, if (nextMethod == "GET") null else current.request.body)
                .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("user-agent", defaultUserAgent())
                .build()
            current.close()
            current = loginClient.newCall(nextRequest).execute()
        }
        return current
    }

    private fun warmupAfterAuth(loginClient: OkHttpClient) {
        executeGet(loginClient, "https://libbooking.gzhu.edu.cn/").use { resp ->
            writeLog("INFO", "[login-http] warmup home status=${resp.code}")
        }
        executeGet(loginClient, "https://libbooking.gzhu.edu.cn/ic-web/Language/getLanList").use { resp ->
            writeLog("INFO", "[login-http] warmup lan status=${resp.code}")
        }
    }

    private fun buildCookieHeader(cookieManager: CookieManager): String {
        val uri = URI("https://libbooking.gzhu.edu.cn/")
        return cookieManager.cookieStore.get(uri)
            .asSequence()
            .filter { it.name.isNotBlank() && it.value.isNotBlank() }
            .map { "${it.name}=${it.value}" }
            .distinct()
            .joinToString("; ")
    }

    private fun parseHiddenInputs(html: String): Map<String, String> {
        val inputRegex = Regex("<input[^>]*>", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("name\\s*=\\s*['\"]?([^'\"\\s>]+)", RegexOption.IGNORE_CASE)
        val valueRegex = Regex("value\\s*=\\s*['\"]([^'\"]*)['\"]", RegexOption.IGNORE_CASE)
        val result = mutableMapOf<String, String>()
        inputRegex.findAll(html).forEach { match ->
            val snippet = match.value
            val name = nameRegex.find(snippet)?.groupValues?.getOrNull(1).orEmpty().trim()
            if (name.isBlank()) return@forEach
            val value = valueRegex.find(snippet)?.groupValues?.getOrNull(1).orEmpty()
            result[name] = value
        }
        return result
    }

    private fun computeRsa(desJs: String, plainText: String): String {
        val ctx = RhinoContext.enter()
        return try {
            ctx.optimizationLevel = -1
            val scope = ctx.initStandardObjects()
            ctx.evaluateString(scope, desJs, "des.js", 1, null)
            val fn = scope.get("strEnc", scope)
            if (fn !is Function) error("des.js 未暴露 strEnc")
            fn.call(ctx, scope, scope, arrayOf(plainText, "1", "2", "3")).toString().uppercase()
        } finally {
            RhinoContext.exit()
        }
    }

    private fun executeGet(loginClient: OkHttpClient, url: String): Response {
        val parsed = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("非法URL: $url")
        val request = Request.Builder()
            .url(parsed)
            .get()
            .header("accept", "application/json, text/plain, */*, text/html")
            .header("lan", "1")
            .header("user-agent", defaultUserAgent())
            .build()
        return loginClient.newCall(request).execute()
    }

    private fun defaultUserAgent(): String {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
    }

    private fun maskUrl(url: String): String {
        if (url.isBlank()) return ""
        return url
            .replace(Regex("(ticket=)[^&]+", RegexOption.IGNORE_CASE), "$1***")
            .replace(Regex("(uniToken=)[^&]+", RegexOption.IGNORE_CASE), "$1***")
            .replace(Regex("(token=)[^&]+", RegexOption.IGNORE_CASE), "$1***")
    }

    private fun writeLog(level: String, message: String) {
        logger?.invoke(level, message)
        when (level) {
            "ERROR" -> Log.e("LibraryApi", message)
            else -> Log.i("LibraryApi", message)
        }
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


package com.family.medbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 简单 HTTP 客户端：HttpURLConnection + org.json（Android 内置，无第三方依赖）
 * 认证方式：Authorization: Bearer <token>
 */
object ApiClient {
    var baseUrl: String = "http://192.168.1.100:3820"
    var token: String = ""

    class ApiError(msg: String) : Exception(msg)

    private fun request(method: String, path: String, body: JSONObject?): Any? {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection 不支持 PATCH，用 POST + X-HTTP-Method-Override 头转换（服务端已支持）
            val realMethod = if (method == "PATCH") "POST" else method
            conn.requestMethod = realMethod
            if (method == "PATCH") conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw ApiError("登录已过期，请重新登录")
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optString("error") } catch (e: Exception) { "" }
                throw ApiError(msg.ifEmpty { "请求失败($code)" })
            }
            if (text.isEmpty()) return null
            return try { JSONObject(text) } catch (e: Exception) { JSONArray(text) }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun get(path: String): Any? = withContext(Dispatchers.IO) { request("GET", path, null) }
    suspend fun post(path: String, body: JSONObject): Any? = withContext(Dispatchers.IO) { request("POST", path, body) }
    suspend fun patch(path: String, body: JSONObject): Any? = withContext(Dispatchers.IO) { request("PATCH", path, body) }
    suspend fun delete(path: String): Any? = withContext(Dispatchers.IO) { request("DELETE", path, null) }
}

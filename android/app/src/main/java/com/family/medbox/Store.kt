package com.family.medbox

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地存储：服务器地址、登录 token、用户信息、用药计划缓存（开机后重排提醒用）
 */
object Store {
    private const val PREFS = "medbox"
    private const val K_BASE = "base_url"
    private const val K_TOKEN = "token"
    private const val K_USER = "user"
    private const val K_SCHEDULES = "schedules_cache"
    const val DEFAULT_BASE = "http://192.168.1.100:3820"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 启动时加载到 ApiClient */
    fun load(ctx: Context) {
        ApiClient.baseUrl = sp(ctx).getString(K_BASE, DEFAULT_BASE)!!
        ApiClient.token = sp(ctx).getString(K_TOKEN, "")!!
    }

    fun baseUrl(ctx: Context): String = sp(ctx).getString(K_BASE, DEFAULT_BASE)!!

    /** 保存登录信息 */
    fun saveLogin(ctx: Context, base: String, token: String, user: JSONObject) {
        sp(ctx).edit()
            .putString(K_BASE, base)
            .putString(K_TOKEN, token)
            .putString(K_USER, user.toString())
            .apply()
        ApiClient.baseUrl = base
        ApiClient.token = token
    }

    fun user(ctx: Context): JSONObject? {
        val s = sp(ctx).getString(K_USER, null) ?: return null
        return try { JSONObject(s) } catch (e: Exception) { null }
    }

    fun logout(ctx: Context) {
        sp(ctx).edit().remove(K_TOKEN).remove(K_USER).apply()
        ApiClient.token = ""
    }

    /** 缓存计划列表，供开机广播接收器重新注册提醒 */
    fun saveSchedulesCache(ctx: Context, arr: JSONArray) {
        sp(ctx).edit().putString(K_SCHEDULES, arr.toString()).apply()
    }

    fun schedulesCache(ctx: Context): JSONArray? {
        val s = sp(ctx).getString(K_SCHEDULES, null) ?: return null
        return try { JSONArray(s) } catch (e: Exception) { null }
    }
}

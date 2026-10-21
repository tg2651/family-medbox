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
    private const val K_MEDS = "medicines_cache"
    private const val K_CODES = "scheduled_codes"
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

    /** 缓存药品列表（含开药推算），供开机/闹钟触发后重排开药提醒 */
    fun saveMedicinesCache(ctx: Context, arr: JSONArray) {
        sp(ctx).edit().putString(K_MEDS, arr.toString()).apply()
    }

    fun medicinesCache(ctx: Context): JSONArray? {
        val s = sp(ctx).getString(K_MEDS, null) ?: return null
        return try { JSONArray(s) } catch (e: Exception) { null }
    }

    /** 上次登记的闹钟 requestCode，重排前按此精确取消 */
    fun scheduledCodes(ctx: Context): List<Int> {
        val s = sp(ctx).getString(K_CODES, "") ?: return emptyList()
        if (s.isEmpty()) return emptyList()
        return s.split(',').mapNotNull { it.toIntOrNull() }
    }

    fun saveScheduledCodes(ctx: Context, codes: List<Int>) {
        sp(ctx).edit().putString(K_CODES, codes.joinToString(",")).apply()
    }

    /** 开药提醒当天只弹一次：记录上次提醒日期（yyyy-MM-dd） */
    fun lastRefillNotified(ctx: Context, medId: Int): String? =
        sp(ctx).getString("refill_notified_$medId", null)

    fun markRefillNotified(ctx: Context, medId: Int, date: String) {
        sp(ctx).edit().putString("refill_notified_$medId", date).apply()
    }
}

package com.family.medbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启：使用缓存的计划数据重新注册当日提醒
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Store.load(ctx)
        if (ApiClient.token.isEmpty()) return // 未登录不注册
        val cached = Store.schedulesCache(ctx) ?: return
        ReminderScheduler.scheduleAll(ctx, cached)
    }
}

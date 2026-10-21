package com.family.medbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启：使用本地缓存的计划与药品重新注册提醒（无需联网）
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action
        // 开机或 App 升级后系统会清空闹钟，需要重排
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Store.load(ctx)
        if (ApiClient.token.isEmpty()) return // 未登录不注册
        ReminderScheduler.rescheduleFromCache(ctx)
    }
}

package com.family.medbox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.LocalDate

/**
 * 提醒接收器：闹钟到达时发送本地通知
 * - intake：服药提醒
 * - refill：开药提醒（库存不足，该买药了）
 * 通知发出后按本地缓存重排提醒，把未来 3 天滚动续上
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val type = intent.getStringExtra("type") ?: "intake"
        if (type == "refill") notifyRefill(ctx, intent) else notifyIntake(ctx, intent)
        // 无论是否弹出通知都续排（当天已提醒过也要继续往后排）
        runCatching { ReminderScheduler.rescheduleFromCache(ctx) }
    }

    private fun notifyIntake(ctx: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: return
        val time = intent.getStringExtra("time") ?: ""
        val dose = intent.getDoubleExtra("dose", 1.0)
        val unit = intent.getStringExtra("unit") ?: "片"
        val doseStr = if (dose % 1.0 == 0.0) dose.toInt().toString() else dose.toString()
        show(ctx, "med_reminder", "服药提醒", "服药提醒 $time", "该吃药了：$name $doseStr$unit")
    }

    private fun notifyRefill(ctx: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: return
        val medId = intent.getIntExtra("med_id", 0)
        val daysLeft = intent.getIntExtra("days_left", 0)
        val next = intent.getStringExtra("next_refill_date") ?: ""
        val today = LocalDate.now().toString()
        // 同一种药当天只提醒一次，避免每次打开 App 都弹
        if (Store.lastRefillNotified(ctx, medId) == today) return
        Store.markRefillNotified(ctx, medId, today)
        val tail = when {
            daysLeft <= 0 -> "库存已用完，请尽快开药"
            next.isNotEmpty() -> "还能服 $daysLeft 天（$next 用完）"
            else -> "还能服 $daysLeft 天"
        }
        show(ctx, "refill_reminder", "开药提醒", "该开药了：$name", tail)
    }

    private fun show(ctx: Context, channelId: String, channelName: String, title: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), n)
    }
}

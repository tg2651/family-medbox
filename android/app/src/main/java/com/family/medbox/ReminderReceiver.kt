package com.family.medbox

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 服药提醒接收器：闹钟到达时发送本地通知
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: return
        val time = intent.getStringExtra("time") ?: ""
        val dose = intent.getDoubleExtra("dose", 1.0)
        val unit = intent.getStringExtra("unit") ?: "片"

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 高优先级通知渠道（安卓 8.0+ 必须建渠道）
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("med_reminder", "服药提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        // 点击通知打开 APP
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val doseStr = if (dose % 1.0 == 0.0) dose.toInt().toString() else dose.toString()
        val n = NotificationCompat.Builder(ctx, "med_reminder")
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("服药提醒 $time")
            .setContentText("该吃药了：$name $doseStr$unit")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), n)
    }
}

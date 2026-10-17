package com.family.medbox

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import java.util.Calendar

/**
 * 用药提醒调度：根据计划为「当日每个未来服药时间点」注册精确闹钟
 * 每次注册前先取消全部旧闹钟，避免重复提醒
 */
object ReminderScheduler {
    private const val MAX_ALARMS = 200 // 支持的最大提醒数

    fun scheduleAll(ctx: Context, schedules: JSONArray) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 1. 取消所有旧提醒（requestCode 复用 0..MAX_ALARMS）
        for (rc in 0 until MAX_ALARMS) am.cancel(pending(ctx, rc, null))

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        var rc = 0
        // 2. 逐计划、逐时间点注册今天的提醒
        for (i in 0 until schedules.length()) {
            val sc = schedules.optJSONObject(i) ?: continue
            if (sc.optInt("enabled", 1) != 1) continue
            val times = sc.optJSONArray("times") ?: continue
            for (j in 0 until times.length()) {
                val t = times.optString(j, "") // 格式 HH:mm
                val h = t.substringBefore(':').toIntOrNull() ?: continue
                val m = t.substringAfter(':').toIntOrNull() ?: continue
                cal.timeInMillis = now
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, m)
                cal.set(Calendar.SECOND, 0)
                if (cal.timeInMillis <= now) continue // 只排今天尚未到达的时间
                if (rc >= MAX_ALARMS) return
                val extras = Intent().putExtra("name", sc.optString("medicine_name"))
                    .putExtra("time", t)
                    .putExtra("dose", sc.optDouble("dose_per_time", 1.0))
                    .putExtra("unit", sc.optString("dose_unit", "片"))
                val pi = pending(ctx, rc, extras)
                val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
                if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                rc++
            }
        }
    }

    private fun pending(ctx: Context, rc: Int, src: Intent?): PendingIntent {
        val intent = src ?: Intent(ctx, ReminderReceiver::class.java)
        if (src != null) intent.setClass(ctx, ReminderReceiver::class.java)
        return PendingIntent.getBroadcast(ctx, rc, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

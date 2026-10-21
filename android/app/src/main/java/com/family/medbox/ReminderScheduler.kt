package com.family.medbox

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 提醒调度
 * - 服药提醒：从今天起滚动排未来 3 天，闹钟触发后会重新排程，自动向后延续
 * - 开药提醒：库存可服天数用完前 7 天的 09:00 提醒一次
 * 每次重排先取消上次登记的闹钟，避免重复提醒；requestCode 复用固定规则，总数控制在 500 以内
 */
object ReminderScheduler {
    private const val DAYS_AHEAD = 3          // 服药提醒滚动天数
    private const val SLOT = 8                // 每个计划最多 8 个服药时间点
    private const val MAX_SCHEDULES = 15      // 服药提醒最多排 15 个计划（3 天 × 15 × 8 = 360）
    private const val REFILL_BASE = 1000      // 开药提醒 requestCode 起始
    private const val MAX_REFILL = 100        // 最多 100 个开药提醒
    private const val REFILL_AHEAD_DAYS = 7L  // 提前几天提醒开药

    fun scheduleAll(ctx: Context, schedules: JSONArray?, medicines: JSONArray?) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (rc in Store.scheduledCodes(ctx)) am.cancel(pending(ctx, rc, null))
        val codes = mutableListOf<Int>()
        val now = System.currentTimeMillis()
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()

        fun add(rc: Int, atMillis: Long, extras: Intent) {
            if (atMillis <= now) return
            val pi = pending(ctx, rc, extras)
            if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            codes += rc
        }

        // 1) 服药提醒：今天起 3 天内的每个服药时间点
        if (schedules != null) {
            val today = LocalDate.now()
            for (day in 0 until DAYS_AHEAD) {
                val date = today.plusDays(day.toLong())
                val dateStr = date.toString()
                for (i in 0 until minOf(schedules.length(), MAX_SCHEDULES)) {
                    val sc = schedules.optJSONObject(i) ?: continue
                    if (!occursOn(sc, dateStr)) continue
                    val times = sc.optJSONArray("times") ?: continue
                    for (j in 0 until minOf(times.length(), SLOT)) {
                        val t = times.optString(j, "")
                        val h = t.substringBefore(':').toIntOrNull() ?: continue
                        val mi = t.substringAfter(':').toIntOrNull() ?: continue
                        if (h !in 0..23 || mi !in 0..59) continue
                        val at = date.atTime(LocalTime.of(h, mi))
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val extras = Intent()
                            .putExtra("type", "intake")
                            .putExtra("name", sc.optString("medicine_name"))
                            .putExtra("time", t)
                            .putExtra("dose", sc.optDouble("dose_per_time", 1.0))
                            .putExtra("unit", sc.optString("dose_unit", "片"))
                        add(day * MAX_SCHEDULES * SLOT + i * SLOT + j, at, extras)
                    }
                }
            }
        }

        // 2) 开药提醒：库存用完前 7 天的 09:00
        if (medicines != null) {
            val today = LocalDate.now()
            for (k in 0 until minOf(medicines.length(), MAX_REFILL)) {
                val m = medicines.optJSONObject(k) ?: continue
                val rf = m.optJSONObject("refill") ?: continue
                val next = rf.optString("nextRefillDate", "")
                if (next.isEmpty()) continue
                val stockOut = runCatching { LocalDate.parse(next) }.getOrNull() ?: continue
                val medId = m.optInt("id")
                val daysLeft = rf.optInt("daysLeft", 999)
                var at = stockOut.minusDays(REFILL_AHEAD_DAYS).atTime(9, 0)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (at <= now) {
                    // 已进入提醒窗口：当天还没提醒过就立即提醒一次
                    if (Store.lastRefillNotified(ctx, medId) == today.toString()) continue
                    at = now + 60_000
                }
                val extras = Intent()
                    .putExtra("type", "refill")
                    .putExtra("med_id", medId)
                    .putExtra("name", m.optString("name"))
                    .putExtra("days_left", daysLeft)
                    .putExtra("stock", m.optDouble("stock_qty", 0.0))
                    .putExtra("unit", m.optString("unit", "片"))
                    .putExtra("next_refill_date", next)
                add(REFILL_BASE + k, at, extras)
            }
        }

        Store.saveScheduledCodes(ctx, codes)
    }

    /** 与后端 scheduleOccursOn 口径一致：起始日、结束日、每日/间隔天数 */
    private fun occursOn(sc: JSONObject, date: String): Boolean {
        if (sc.optInt("enabled", 1) != 1) return false
        val start = sc.optString("start_date", "")
        if (start.isNotEmpty() && date < start) return false
        val end = sc.optString("end_date", "")
        if (end.isNotEmpty() && date > end) return false
        if (sc.optString("frequency", "daily") == "daily") return true
        if (start.isEmpty()) return true
        val interval = maxOf(1, sc.optInt("interval_days", 1))
        val diff = runCatching {
            LocalDate.parse(date).toEpochDay() - LocalDate.parse(start).toEpochDay()
        }.getOrDefault(0L)
        return diff % interval == 0L
    }

    private fun pending(ctx: Context, rc: Int, src: Intent?): PendingIntent {
        val intent = src ?: Intent(ctx, ReminderReceiver::class.java)
        if (src != null) intent.setClass(ctx, ReminderReceiver::class.java)
        return PendingIntent.getBroadcast(ctx, rc, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 触发后按本地缓存续排（不联网也能把未来 3 天补上） */
    fun rescheduleFromCache(ctx: Context) {
        val schedules = Store.schedulesCache(ctx)
        val meds = Store.medicinesCache(ctx)
        if (schedules != null || meds != null) scheduleAll(ctx, schedules, meds)
    }
}

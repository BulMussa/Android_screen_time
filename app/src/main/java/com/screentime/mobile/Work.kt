package com.screentime.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Everything that runs in the background: read the phone, sync, remind. */
object Pipeline {
    /** Returns the status text, which is also kept for the screens. */
    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        runCatching { UsageCollector.scan(context) }
        if (Graph.prefs.sleepMethod == "band") runCatching { SleepCollector.scan(context) }
        val status = if (!Graph.prefs.connected) {
            "Not connected"
        } else {
            try {
                val rejected = SyncEngine.run()
                "Synced at ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}" +
                    if (rejected > 0) ", $rejected rows rejected (is the phone's clock right?)" else ""
            } catch (e: SyncException) {
                when {
                    e.status == 401 -> "The server doesn't accept this phone anymore. Disconnect and connect again."
                    e.status == null || e.status >= 500 -> "Offline, will try again. ${e.message}"
                    else -> "The server refused the data: ${e.message}"
                }
            }
        }
        Graph.prefs.status = status
        Reminders.check(context)
        status
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Pipeline.run(applicationContext)
        return Result.success()
    }
}

object Scheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun syncNow(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SyncWorker>().build())
    }
}

/** One notification a day, after 09:00, when no sleep was recorded for last night. */
object Reminders {
    private const val CHANNEL = "sleep"

    @SuppressLint("MissingPermission") // checked just below
    fun check(context: Context) {
        val method = Graph.prefs.sleepMethod ?: return
        val now = LocalDateTime.now()
        if (now.hour < 9) return
        val today = now.toLocalDate()
        if (Graph.prefs.reminderDay == today.toString()) return
        val from = startOfDay(today) - 2 * 3600 // 22:00 last night
        val to = startOfDay(today) + 10 * 3600
        if (Graph.db.overlapping(listOf("sleep"), from, to).isNotEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sleep reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (method == "band") {
            "No sleep data yet. Open your band's app to sync it, or add your sleep by hand."
        } else {
            "How did you sleep? Add your sleep times."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Log last night's sleep")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1, notification)
        Graph.prefs.reminderDay = LocalDate.now().toString()
    }
}

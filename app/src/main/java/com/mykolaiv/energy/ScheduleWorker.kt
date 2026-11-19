package com.mykolaiv.energy

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import androidx.core.content.edit
import java.time.LocalDateTime
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

private const val TAG = "ScheduleWorker"

class ScheduleWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
    private val repository = ScheduleRepository(appContext)

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: Worker started")
        val settingsManager = SettingsManager(applicationContext)
        // Отримуємо вибрані користувачем ID черг (наприклад, "1.1", "2.2")
        val selectedIds = settingsManager.selectedQueueIds.first()
        Log.d(TAG, "doWork: selectedIds = $selectedIds")
        val alertMinutes = settingsManager.alertTime.first()

        if (selectedIds.isEmpty()) return Result.success()

        try {
            val queueIds = selectedIds.mapNotNull { it.toIntOrNull() }.toSet()
            val scheduleState = repository.getScheduleState(queueIds)
            checkForScheduleChanges(queueIds, scheduleState.fingerprint)
            val upcomingOutages = scheduleState.outages

            upcomingOutages.forEach { outage ->
                val startTime = outage.time.start
                val outageDate = outage.date
                val queueId = outage.queueId
                val qName = outage.queueName

                val timeToNotify = isItTimeToSend(outageDate, startTime, alertMinutes)
                val alreadyDone = isAlreadyNotified(queueId.toString(), outageDate, startTime)

                if (timeToNotify && !alreadyDone) {
                    Log.d(TAG, "Planning notification qName = $qName outageDate = $outageDate startTime = $startTime")
                    scheduleOutageAlarm(
                        applicationContext, queueId, qName, outageDate, startTime, alertMinutes
                    )
                    markAsNotified(queueId.toString(), outageDate, startTime)
                }
            }

            cleanupOldNotifs()
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in doWork", e)
            return Result.retry()
        }
    }

    private suspend fun checkForScheduleChanges(queueIds: Set<Int>, fingerprint: String) {
        val prefs = applicationContext.getSharedPreferences("schedule_change_prefs", Context.MODE_PRIVATE)
        val queueKey = queueIds.sorted().joinToString(",")
        val previousQueueKey = prefs.getString("queue_ids", null)
        val todayKey = LocalDate.now().toString()
        val previousDay = prefs.getString("day", null)
        val previousFingerprint = prefs.getString("fingerprint", null)

        // При першому запуску або зміні вибраних черг формуємо нову базову версію.
        if (previousQueueKey == queueKey && previousDay == todayKey && previousFingerprint != null && previousFingerprint != fingerprint) {
            showScheduleChangedNotification()
        }
        prefs.edit {
            putString("queue_ids", queueKey)
            putString("day", todayKey)
            putString("fingerprint", fingerprint)
        }
    }

    private suspend fun showScheduleChangedNotification() {
        val context = applicationContext
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return

        val notificationId = System.currentTimeMillis().toInt()
        val notificationVolume = SettingsManager(context).notificationVolume.first()
        val builder = NotificationCompat.Builder(context, "OUTAGE_NOTIFICATIONS").apply {
            setSmallIcon(android.R.drawable.ic_dialog_info)
            setContentTitle(context.getString(R.string.notification_change_title))
            setContentText(context.getString(R.string.notification_change_text))
            setPriority(NotificationCompat.PRIORITY_MAX)
            setCategory(NotificationCompat.CATEGORY_ALARM)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setAutoCancel(true)
            setVibrate(longArrayOf(1000, 1000, 1000))
            setContentIntent(NotificationSoundReceiver.openIntent(context, notificationId))
            setDeleteIntent(NotificationSoundReceiver.deleteIntent(context, notificationId))
        }

        if (postNotification(context, notificationId, builder.build())) {
            NotificationSoundController.play(
                context,
                notificationId,
                R.raw.notification_change,
                notificationVolume
            )
        }
    }

    private fun isItTimeToSend(outageDate: String, startTime: String, alertMinutes: Int): Boolean {
        return try {
            val now = LocalDateTime.now()
            val start = LocalDateTime.parse("${outageDate}T$startTime")
            val diff = java.time.Duration.between(now, start).toMinutes()

            // Вікно планування має бути більшим за час самого попередження.
            // Ми беремо alertMinutes і додаємо запас (наприклад, 150 хвилин),
            // щоб Worker міг запланувати сповіщення заздалегідь.
            // Але мінімально залишаємо 180, як ми домовлялися для нічних графіків.
            val planningWindow = maxOf(180, alertMinutes + 30)
            Log.d(TAG, "isItTimeToSend: planningWindow = $planningWindow")
            diff in 0..planningWindow
        } catch (e: Exception) {
            Log.e(TAG, "Error in isItTimeToSend", e)
            false
        }
    }

    private fun isAlreadyNotified(outageId: String, date: String, time: String): Boolean {
        val prefs = applicationContext.getSharedPreferences("notif_prefs", Context.MODE_PRIVATE)
        val key = "notif_${outageId}_${date}_${time}"
        return prefs.getBoolean(key, false)
    }

    private fun markAsNotified(outageId: String, date: String, time: String) {
        val prefs = applicationContext.getSharedPreferences("notif_prefs", Context.MODE_PRIVATE)
        val key = "notif_${outageId}_${date}_${time}"
        prefs.edit {
            putBoolean(key, true)
            apply()
        }
    }

    private fun cleanupOldNotifs() {
        val prefs = applicationContext.getSharedPreferences("notif_prefs", Context.MODE_PRIVATE)
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        prefs.edit {
            prefs.all.forEach { (key, _) ->
                try {
                    val parts = key.split("_")
                    if (parts.size >= 3) {
                        val dateStr = parts[2]
                        val recordDate = LocalDate.parse(dateStr)

                        if (recordDate.isBefore(yesterday)) {
                            remove(key)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in cleanupOldNotifs", e)
                    // Ігноруємо ключі, що не відповідають формату дати
                }
            }
        }
    }
}
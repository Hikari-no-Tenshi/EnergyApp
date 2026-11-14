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
import android.app.PendingIntent
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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

        val sound = context.resources.openRawResourceFd(R.raw.notification_change)
        playNotificationSound(sound)

        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notificationTitle = context.getString(R.string.notification_change_title)
        val notificationText = context.getString(R.string.notification_change_text)

        val builder = NotificationCompat.Builder(context, "OUTAGE_NOTIFICATIONS").apply {
            setSmallIcon(android.R.drawable.ic_dialog_info)
            setContentTitle(notificationTitle)
            setContentText(notificationText)
            setPriority(NotificationCompat.PRIORITY_MAX)
            setCategory(NotificationCompat.CATEGORY_ALARM)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            //setSound(null)
            setAutoCancel(true)
            setVibrate(longArrayOf(1000, 1000, 1000))
            setContentIntent(pendingIntent)
        }

        with(NotificationManagerCompat.from(context)) {
            notify(System.currentTimeMillis().toInt(), builder.build())
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

    private suspend fun playNotificationSound(sound: AssetFileDescriptor) {
        // 1. Створюємо пустий об'єкт без виклику .create()
        val mediaPlayer = MediaPlayer()
        try {
            // 2. Налаштовуємо атрибути ПЕРЕД підготовкою
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            mediaPlayer.setAudioAttributes(audioAttributes)

            // 3. Вказуємо джерело звуку через FileDescriptor ресурсу
            mediaPlayer.setDataSource(sound.fileDescriptor, sound.startOffset, sound.length)
            sound.close()

            // 4. Встановлюємо гучність та ініціалізуємо
            val settingsManager = SettingsManager(applicationContext)
            val volume = settingsManager.notificationVolume.first()
            mediaPlayer.setVolume(volume, volume)
            mediaPlayer.isLooping = false
            mediaPlayer.prepare()

            mediaPlayer.start()

            mediaPlayer.setOnCompletionListener {
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in playPowerOffAlert", e)
            mediaPlayer.release() // Завжди звільняємо пам'ять, якщо щось пішло не так
        }
    }
}
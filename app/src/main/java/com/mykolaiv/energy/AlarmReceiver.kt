package com.mykolaiv.energy

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import android.app.AlarmManager
import kotlinx.coroutines.launch

private const val TAG = "AlarmReceiver"

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "ALARM FIRED!")
        val pendingResult = goAsync()
        val settingsManager = SettingsManager(context.applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val isTest = intent.getBooleanExtra("is_test", false)
                var qName = intent.getStringExtra("queue_name") ?: "Черга"
                var startTime = intent.getStringExtra("start_time") ?: ""

                // Тестове сповіщення не залежить від графіка та API.
                if (!isTest) {
                    val queueId = intent.getIntExtra("queue_id", -1)
                    val outageDate = intent.getStringExtra("outage_date")
                    if (queueId < 0 || outageDate.isNullOrBlank() || startTime.isBlank()) {
                        Log.w(TAG, "Skipping outdated alarm: missing queue_id/outage_date/start_time")
                        return@launch
                    }

                    val actualOutage = try {
                        ScheduleRepository(context.applicationContext).findNextOutage(queueId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not refresh schedule; retrying alarm", e)
                        scheduleAlarmRetry(context, intent)
                        return@launch
                    }

                    // У свіжому графіку для цієї черги більше немає майбутніх відключень.
                    if (actualOutage == null) {
                        Log.d(TAG, "No upcoming outage for queueId=$queueId; notification cancelled")
                        return@launch
                    }

                    val scheduledStart = LocalDateTime.parse("${outageDate}T$startTime")
                    if (actualOutage.startDateTime != scheduledStart) {
                        val alertMinutes = intent.getIntExtra(
                            "alert_minutes", settingsManager.alertTime.first()
                        )
                        Log.d(TAG, "Schedule changed: $scheduledStart -> ${actualOutage.startDateTime}")
                        scheduleOutageAlarm(
                            context.applicationContext,
                            actualOutage.queueId,
                            actualOutage.queueName,
                            actualOutage.date,
                            actualOutage.time.start,
                            alertMinutes
                        )
                        return@launch
                    }

                    // Час збігається: показуємо попередження за актуальним графіком.
                    qName = actualOutage.queueName
                    startTime = actualOutage.time.start
                }

                val notificationVolume = settingsManager.notificationVolume.first()
                Log.d(TAG, "onReceive: isTest = $isTest qName = $qName startTime = $startTime notificationVolume = $notificationVolume")
                sendNotification(context, isTest, qName, startTime, notificationVolume)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private fun scheduleAlarmRetry(context: Context, originalIntent: Intent) {
    val queueId = originalIntent.getIntExtra("queue_id", -1)
    val outageDate = originalIntent.getStringExtra("outage_date") ?: return
    val startTime = originalIntent.getStringExtra("start_time") ?: return
    if (queueId < 0 || startTime.isBlank()) return

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val retryIntent = Intent(context, AlarmReceiver::class.java).apply {
        putExtras(originalIntent)
    }
    val requestCode = ("${queueId}_${outageDate}_$startTime").hashCode()
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        retryIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    alarmManager.setAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        System.currentTimeMillis() + 2 * 60 * 1000L,
        pendingIntent
    )
}

private fun sendNotification(context: Context, isTest: Boolean, queueName: String, startTime: String, notificationVolume: Float) {
    playPowerOffAlert(context, notificationVolume)

    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }

    val pendingIntent: PendingIntent = PendingIntent.getActivity(
        context, 0, intent,
        PendingIntent.FLAG_IMMUTABLE // Важливо для Android 12+
    )

    val notificationTitle: String
    val notificationText: String
    if (isTest) {
        notificationTitle = context.getString(R.string.notification_test_title)
        notificationText = context.getString(R.string.notification_test_text, startTime, queueName)
    } else {
        notificationTitle = context.getString(R.string.notification_title)
        notificationText = context.getString(R.string.notification_text, startTime, queueName)
    }

    val builder = NotificationCompat.Builder(context, "OUTAGE_NOTIFICATIONS").apply {
        setSmallIcon(android.R.drawable.ic_dialog_alert)
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
        // Перевірка дозволу для Android 13+
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Тепер помилка зникне
            notify(System.currentTimeMillis().toInt(), builder.build())
        } else {
            // Якщо дозволу немає, воркер просто нічого не зробить.
            // Зазвичай дозвіл запитується в MainActivity при старті програми.
            println("Notification permission not granted")
        }
    }
}

private fun playPowerOffAlert(context: Context, volume: Float) {
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
        val afd = context.resources.openRawResourceFd(R.raw.notification)
        mediaPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        afd.close()

        // 4. Встановлюємо гучність та ініціалізуємо
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
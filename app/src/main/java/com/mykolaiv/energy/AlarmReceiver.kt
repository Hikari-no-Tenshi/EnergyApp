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
                val qName = intent.getStringExtra("queue_name") ?: "Черга"
                val startTime = intent.getStringExtra("start_time") ?: ""
                val notificationVolume = settingsManager.notificationVolume.first()
                Log.d(TAG, "onReceive: isTest = $isTest qName = $qName startTime = $startTime notificationVolume = $notificationVolume")
                sendNotification(context, isTest, qName, startTime, notificationVolume)
            } finally {
                pendingResult.finish()
            }
        }
    }
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
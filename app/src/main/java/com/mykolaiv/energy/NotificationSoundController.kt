package com.mykolaiv.energy

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

@SuppressLint("MissingPermission")
fun postNotification(
    context: Context,
    notificationId: Int,
    notification: Notification
): Boolean {
    if (ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }

    NotificationManagerCompat.from(context).notify(
        notificationId,
        notification
    )

    return true
}

/**
 * Єдиний контролер звуку для всіх сповіщень EnergyApp.
 * Звук прив'язаний до notificationId, а не до конкретного Worker/Receiver.
 */
object NotificationSoundController {
    private val players = mutableMapOf<Int, MediaPlayer>()

    @Synchronized
    fun stop(notificationId: Int) {
        val player = players.remove(notificationId) ?: return
        try {
            if (player.isPlaying) {
                player.stop()
            }
        } catch (e: Exception) {
            Log.e("NotificationSound", "Error stopping player", e)
        } finally {
            player.release()
        }
    }

    @Synchronized
    fun play(context: Context, notificationId: Int, soundResId: Int, volume: Float) {
        stop(notificationId)
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            context.resources.openRawResourceFd(soundResId).use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            val level = volume.coerceIn(0f, 1f)
            player.setVolume(level, level)
            player.isLooping = false
            player.setOnCompletionListener { completed ->
                synchronized(this) {
                    if (players[notificationId] === completed) {
                        players.remove(notificationId)
                        completed.release()
                    }
                }
            }
            player.setOnErrorListener { failed, _, _ ->
                synchronized(this) {
                    if (players[notificationId] === failed) {
                        players.remove(notificationId)
                        failed.release()
                    }
                }
                true
            }
            player.prepare()
            players[notificationId] = player
            player.start()
        } catch (e: Exception) {
            Log.e("NotificationSound", "Cannot play notification sound", e)
            if (players[notificationId] === player) players.remove(notificationId)
            try { player.release() } catch (_: Exception) {}
        }
    }
}

/** Отримує події змахування та відкриття будь-якого сповіщення. */
class NotificationSoundReceiver : BroadcastReceiver() {
    companion object {
        private const val ACTION_DISMISS = "com.mykolaiv.energy.NOTIFICATION_DISMISS"
        private const val ACTION_OPEN = "com.mykolaiv.energy.NOTIFICATION_OPEN"
        private const val EXTRA_ID = "notification_id"

        fun deleteIntent(context: Context, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationSoundReceiver::class.java).apply {
                action = ACTION_DISMISS
                data = "energyapp://notification/$notificationId/$action".toUri()
                putExtra(EXTRA_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun openIntent(context: Context, notificationId: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN
                data = "energyapp://notification/$notificationId/open".toUri()
                putExtra(EXTRA_ID, notificationId)

                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            return PendingIntent.getActivity(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!intent.hasExtra(EXTRA_ID)) return
        val id = intent.getIntExtra(EXTRA_ID, 0)
        NotificationSoundController.stop(id)
    }
}

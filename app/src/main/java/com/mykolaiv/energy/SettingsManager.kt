package com.mykolaiv.energy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")


object StartDestinations {
    const val ALL_QUEUES = "main"
    const val MY_QUEUES = "my_queues"
}

class SettingsManager(private val context: Context) {
    companion object {
        private val SELECTED_QUEUES_KEY = stringSetPreferencesKey("selected_queues")
        private val START_SCREEN_KEY = stringPreferencesKey("start_screen")
        private val ONLY_ACTUAL_KEY = booleanPreferencesKey("only_actual")
        private val NOTIFICATION_VOLUME_KEY = floatPreferencesKey("notification_volume")
        private val ALERT_TIME_KEY = intPreferencesKey("alert_time")
        private val SAVED_ADDRESSES_KEY = stringSetPreferencesKey("saved_addresses")
    }

    // Потік даних зі списком ID вибраних черг
    val selectedQueueIds: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            preferences[SELECTED_QUEUES_KEY] ?: emptySet()
        }

    val startScreen: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[START_SCREEN_KEY] ?: StartDestinations.ALL_QUEUES
        }

    val onlyActual: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[ONLY_ACTUAL_KEY] ?: false
        }

    val notificationVolume: Flow<Float> = context.dataStore.data
        .map { preferences ->
            preferences[NOTIFICATION_VOLUME_KEY] ?: 0.5f
        }

    val alertTime = context.dataStore.data
        .map { preferences ->
            preferences[ALERT_TIME_KEY] ?: 30
        }

    val savedAddresses: Flow<Set<String>> = context.dataStore.data
        .map { preferences ->
            preferences[SAVED_ADDRESSES_KEY] ?: emptySet()
        }

    // Функція для оновлення списку
    suspend fun saveSelectedQueues(ids: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[SELECTED_QUEUES_KEY] = ids
        }
    }

    suspend fun setStartScreen(screen: String) {
        context.dataStore.edit { preferences ->
            preferences[START_SCREEN_KEY] = screen
        }
    }

    suspend fun setOnlyActual(value: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ONLY_ACTUAL_KEY] = value
        }
    }

    suspend fun saveVolume(volume: Float) {
        context.dataStore.edit { preferences ->
            preferences[NOTIFICATION_VOLUME_KEY] = volume
        }
    }

    suspend fun saveAlertTime(minutes: Int) {
        context.dataStore.edit { preferences ->
            preferences[ALERT_TIME_KEY] = minutes
        }
        setupBackgroundWork(context)
    }

    suspend fun saveSavedAddresses(ids: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[SAVED_ADDRESSES_KEY] = ids
        }
    }

    fun scheduleTestNotification(context: Context, minutesFromNow: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("is_test", true)
            putExtra("queue_name", "Тестова черга")
            putExtra("start_time", "$minutesFromNow")
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            999, // Окремий ID для тесту, щоб не затерти реальні сповіщення
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + (minutesFromNow * 60 * 1000)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }
}
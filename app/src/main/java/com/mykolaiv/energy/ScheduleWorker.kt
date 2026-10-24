package com.mykolaiv.energy

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDateTime
import java.time.ZoneId

private const val TAG = "ScheduleWorker"

class ScheduleWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {
    val context = appContext
    private val apiService = ApiService.create()

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: Worker started")
        val settingsManager = SettingsManager(applicationContext)
        // Отримуємо вибрані користувачем ID черг (наприклад, "1.1", "2.2")
        val selectedIds = settingsManager.selectedQueueIds.first()
        Log.d(TAG, "doWork: selectedIds = $selectedIds")
        val alertMinutes = settingsManager.alertTime.first()

        if (selectedIds.isEmpty()) return Result.success()

        try {
            val activeOutageWrappers: List<ActiveOutage>
            val timesList: List<TimeSeries>
            val queuesList: List<Queue>

            if (!mock) {
                // 1. Отримуємо дані з API
                activeOutageWrappers = apiService.getActiveOutages()
                timesList = apiService.getTimeSeries()
                queuesList = apiService.getQueues()
            } else {
                val qJson = context.readAssetFile("3.json")
                val tJson = context.readAssetFile("time-series.json")
                val aJson = context.readAssetFile("active.json")

                activeOutageWrappers = Gson().fromJson(
                    aJson,
                    object : TypeToken<List<ActiveOutage>>() {}.type
                )
                timesList = Gson().fromJson(
                    tJson,
                    object : TypeToken<List<TimeSeries>>() {}.type
                )
                queuesList = Gson().fromJson(
                    qJson,
                    object : TypeToken<List<Queue>>() {}.type
                )
            }

            val timesMap = timesList.associateBy { it.id }
            val queuesMap = queuesList.associateBy { it.id }
            
            // 1. Отримуємо СЬОГОДНІ та ЗАВТРА для фільтрації
            val todayDate = LocalDate.now()
            val tomorrowDate = todayDate.plusDays(1)
            Log.d(TAG, "doWork: todayDate = $todayDate tomorrowDate = $tomorrowDate")

            // 2. Витягуємо всі OutageItem з усіх обгорток в один плаский список
            val allItems = activeOutageWrappers.flatMap { wrapper ->
                // Беремо дату "to" з обгортки (наприклад, "2026-04-09T20:59...")
                val wrapperDate = wrapper.to ?: ""
                wrapper.series.map { item ->
                    // Копіюємо дані в новий об'єкт, додаючи дату
                    item.copy(dateTo = wrapperDate)
                }
            }

            // 2. Фільтруємо на сьогодні та завтра
            val myOutages = allItems.filter { item ->
                val dateFromApiStr = item.dateTo?.take(10) ?: ""
                val isMyQueue = selectedIds.contains(item.outageQueueId.toString())
                
                if (dateFromApiStr.isNotEmpty() && isMyQueue) {
                    val dateFromApi = LocalDate.parse(dateFromApiStr)
                    dateFromApi == todayDate || dateFromApi == tomorrowDate
                } else false
            }

            // 3. Групуємо за чергами для об'єднання блоків (як ми робили раніше)
            val outagesByQueue = myOutages.groupBy { it.outageQueueId }

            outagesByQueue.forEach { (queueId, items) ->
                // Важливо сортувати за повною датою + часом, щоб не переплутати 23:00 сьогодні й завтра
                val sortedItems = items.sortedBy { item ->
                    val date = item.dateTo?.take(10) ?: ""
                    val time = timesMap[item.timeSeriesId]?.start ?: ""
                    "${date}T$time"
                }
                
                val continuousBlocks = findContinuousBlocks(sortedItems, timesMap)

                continuousBlocks.forEach { blockInfo ->
                    val startTime = blockInfo.time.start
                    val outageDate = blockInfo.date // Дата конкретного блоку
                    val qName = queuesMap[queueId]?.name ?: "Черга $queueId"
                    
                    // Перевіряємо час відносно повної дати
                    val timeToNotify = isItTimeToSend(outageDate, startTime, alertMinutes)
                    val alreadyDone = isAlreadyNotified(queueId.toString(), outageDate, startTime)

                    if (timeToNotify && !alreadyDone) {
                        Log.d(TAG, "Planning notification qName = $qName outageDate = $outageDate startTime = $startTime")
                        scheduleExactAlarm(qName, startTime, outageDate, alertMinutes)
                        markAsNotified(queueId.toString(), outageDate, startTime)
                    }
                }
            }

            cleanupOldNotifs()
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in doWork", e)
            return Result.retry()
        }
    }

    // Допоміжна структура для збереження дати блоку
    data class BlockInfo(val date: String, val time: TimeSeries)

    private fun findContinuousBlocks(
        items: List<OutageItem>,
        timesMap: Map<Int, TimeSeries>
    ): List<BlockInfo> {
        if (items.isEmpty()) return emptyList()

        val blockStarts = mutableListOf<BlockInfo>()
        var lastEnd: String? = null
        var lastDate: String? = null

        for (item in items) {
            val tInfo = timesMap[item.timeSeriesId] ?: continue
            val currentDate = item.dateTo?.take(10) ?: ""

            // Блок вважається безперервним, якщо час збігається І це та сама доба, 
            // АБО якщо час 00:00 і це наступна доба після 23:59
            val isContinuous = (lastDate == currentDate && tInfo.start == lastEnd)
            
            if (lastEnd == null || !isContinuous) {
                blockStarts.add(BlockInfo(currentDate, tInfo))
            }

            lastEnd = tInfo.end
            lastDate = currentDate
        }
        return blockStarts
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

    private fun scheduleExactAlarm(qName: String, startTime: String, outageDate: String, alertMinutes: Int) {
        val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val triggerTime = LocalDateTime.parse("${outageDate}T$startTime")
            .minusMinutes(alertMinutes.toLong())
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        // Якщо 30 хвилин тому вже минуло, вистрілить миттєво
        val finalTriggerTime = if (triggerTime < System.currentTimeMillis()) System.currentTimeMillis() else triggerTime

        val intent = Intent(applicationContext, AlarmReceiver::class.java).apply {
            putExtra("queue_name", qName)
            putExtra("start_time", startTime)
        }

        val operationId = (qName + outageDate + startTime).hashCode()

        val pendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            operationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        alarmManager.cancel(pendingIntent)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // ТУТ ВАЖЛИВО: Не викликаємо startActivity з фону, просто ставимо звичайне сповіщення
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, finalTriggerTime, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, finalTriggerTime, pendingIntent)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Error in scheduleExactAlarm", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, finalTriggerTime, pendingIntent)
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
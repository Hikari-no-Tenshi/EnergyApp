package com.mykolaiv.energy

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDate
import java.time.LocalDateTime

/** Дані про найближчий початок безперервного блоку відключення. */
data class UpcomingOutage(
    val queueId: Int,
    val queueName: String,
    val date: String,
    val time: TimeSeries
) {
    val startDateTime: LocalDateTime
        get() = LocalDateTime.parse("${date}T${time.start}")
}

/** Спільне отримання та обробка графіка для ScheduleWorker і AlarmReceiver. */
class ScheduleRepository(private val context: Context) {
    private val apiService = ApiService.create()

    suspend fun getUpcomingOutages(
        queueIds: Set<Int>,
        from: LocalDateTime = LocalDateTime.now()
    ): List<UpcomingOutage> {
        if (queueIds.isEmpty()) return emptyList()

        val activeOutageWrappers: List<ActiveOutage>
        val timesList: List<TimeSeries>
        val queuesList: List<Queue>

        if (!mock) {
            activeOutageWrappers = apiService.getActiveOutages()
            timesList = apiService.getTimeSeries()
            queuesList = apiService.getQueues()
        } else {
            activeOutageWrappers = Gson().fromJson(
                context.readAssetFile("active.json"),
                object : TypeToken<List<ActiveOutage>>() {}.type
            )
            timesList = Gson().fromJson(
                context.readAssetFile("time-series.json"),
                object : TypeToken<List<TimeSeries>>() {}.type
            )
            queuesList = Gson().fromJson(
                context.readAssetFile("3.json"),
                object : TypeToken<List<Queue>>() {}.type
            )
        }

        val timesMap = timesList.associateBy { it.id }
        val queuesMap = queuesList.associateBy { it.id }
        val today = from.toLocalDate()
        val tomorrow = today.plusDays(1)

        val allItems = activeOutageWrappers.flatMap { wrapper ->
            val wrapperDate = wrapper.to ?: ""
            wrapper.series.map { item -> item.copy(dateTo = wrapperDate) }
        }

        val myOutages = allItems.filter { item ->
            val dateString = item.dateTo?.take(10) ?: ""
            if (dateString.isEmpty() || item.outageQueueId !in queueIds) {
                false
            } else {
                val date = LocalDate.parse(dateString)
                date == today || date == tomorrow
            }
        }

        return myOutages
            .groupBy { it.outageQueueId }
            .flatMap { (queueId, items) ->
                val sortedItems = items.sortedBy { item ->
                    val date = item.dateTo?.take(10) ?: ""
                    val time = timesMap[item.timeSeriesId]?.start ?: ""
                    "${date}T$time"
                }

                findContinuousBlocks(sortedItems, timesMap).mapNotNull { block ->
                    val outage = UpcomingOutage(
                        queueId = queueId,
                        queueName = queuesMap[queueId]?.name ?: "Черга $queueId",
                        date = block.date,
                        time = block.time
                    )
                    outage.takeIf { it.startDateTime.isAfter(from) }
                }
            }
            .sortedBy { it.startDateTime }
    }

    suspend fun findNextOutage(
        queueId: Int,
        from: LocalDateTime = LocalDateTime.now()
    ): UpcomingOutage? = getUpcomingOutages(setOf(queueId), from).firstOrNull()

    private data class BlockInfo(val date: String, val time: TimeSeries)

    private fun findContinuousBlocks(
        items: List<OutageItem>,
        timesMap: Map<Int, TimeSeries>
    ): List<BlockInfo> {
        if (items.isEmpty()) return emptyList()

        val blockStarts = mutableListOf<BlockInfo>()
        var lastEnd: String? = null
        var lastDate: String? = null

        for ((_, timeSeriesId, _, dateTo) in items) {
            val timeInfo = timesMap[timeSeriesId] ?: continue
            val currentDate = dateTo?.take(10) ?: ""
            val isContinuous = lastDate == currentDate && timeInfo.start == lastEnd

            if (lastEnd == null || !isContinuous) {
                blockStarts.add(BlockInfo(currentDate, timeInfo))
            }

            lastEnd = timeInfo.end
            lastDate = currentDate
        }
        return blockStarts
    }
}

/** Єдина реалізація постановки Alarm для Worker та Receiver. */
fun scheduleOutageAlarm(
    context: Context,
    queueId: Int,
    queueName: String,
    outageDate: String,
    startTime: String,
    alertMinutes: Int
) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    val triggerTime = LocalDateTime.parse("${outageDate}T$startTime")
        .minusMinutes(alertMinutes.toLong())
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
    val finalTriggerTime = maxOf(triggerTime, System.currentTimeMillis())

    val alarmIntent = android.content.Intent(context, AlarmReceiver::class.java).apply {
        putExtra("queue_id", queueId)
        putExtra("queue_name", queueName)
        putExtra("outage_date", outageDate)
        putExtra("start_time", startTime)
        putExtra("alert_minutes", alertMinutes)
    }
    val requestCode = ("${queueId}_${outageDate}_$startTime").hashCode()
    val pendingIntent = android.app.PendingIntent.getBroadcast(
        context,
        requestCode,
        alarmIntent,
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
    )

    alarmManager.cancel(pendingIntent)
    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP, finalTriggerTime, pendingIntent
            )
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP, finalTriggerTime, pendingIntent
            )
        }
    } catch (e: SecurityException) {
        android.util.Log.e("ScheduleRepository", "Error in scheduleOutageAlarm", e)
        alarmManager.setAndAllowWhileIdle(
            android.app.AlarmManager.RTC_WAKEUP, finalTriggerTime, pendingIntent
        )
    }
}

package com.mykolaiv.energy

import android.content.Context
import android.util.Log
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private const val TAG = "OutageCard"

@Composable
fun OutageCard(
    context: Context,
    item: OutageItem,
    queueName: String,
    time: TimeSeries,
    isCurrent: Boolean
) {
    val isOff = item.type in listOf("OFF", "SURE_OFF")
    //val statusColor = if (isOff) Color(0xFFFF5252) else Color(0xFFFFD740)
    //val bgColor = if (isCurrent) Color(0xFF1A237E) else Color(0xFF263238)
    val bgColor = MaterialTheme.colorScheme.surfaceVariant
    val statusColor = if (isOff) getBestContrastColor(Color(0xFFFF5252), bgColor) else getBestContrastColor(Color(0xFFFFD740), bgColor)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 12.dp)
            .then(
                if (isCurrent) Modifier.border(2.dp, getBestContrastColor(Color(0xFF448AFF), bgColor), RoundedCornerShape(12.dp))
                else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.card_queue, queueName), color = getBestContrastColor(Color.White, bgColor), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    if (isOff) context.getString(R.string.off) else context.getString(R.string.probably_off),
                    color = statusColor,
                    fontWeight = FontWeight.W900,
                    fontSize = 14.sp
                )
            }
            Text(stringResource(R.string.card_time, time.start, time.end), color = getBestContrastColor(Color.White.copy(0.7f), bgColor), fontSize = 14.sp)
        }
    }
}

// Допоміжна функція часу
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

fun isTimeCurrent(start: String, end: String): Boolean {
    return try {
        val now = LocalTime.now()
        val s = LocalTime.parse(start, timeFormatter)

        // Трактуємо "00:00:00" як кінець доби для коректного діапазону
        val e = if (end == "00:00:00") LocalTime.MAX else LocalTime.parse(end, timeFormatter)

        if (s.isBefore(e)) {
            // Використовуємо Range Check: оператор 'in' та діапазон 's..e'
            now in s..e
        } else {
            // Випадок переходу через північ (наприклад, з 23:00 до 01:00)
            now >= s || now <= e
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error in isTimeCurrent", e)
        false
    }
}

fun getBestContrastColor(target: Color, background: Color): Color {
    val bg = background.toArgb()
    var current = target.toArgb()
    val isDark = ColorUtils.calculateLuminance(bg) < 0.5

    val step = 0.1f // крок зміни (10%)
    var amount = 0f

    // Поки контраст менше 4.5 і ми не змішали на 100%
    while (ColorUtils.calculateContrast(current, bg) < 4.5 && amount < 1f) {
        amount += step
        current = ColorUtils.blendARGB(
            target.toArgb(),
            if (isDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK,
            amount
        )
    }
    return Color(current)
}
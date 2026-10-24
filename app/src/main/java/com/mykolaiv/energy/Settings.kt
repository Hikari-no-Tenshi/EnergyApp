package com.mykolaiv.energy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope

private const val TAG = "SettingsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    @Suppress("unused") navController: NavController,
    allQueues: Map<Int, String>, // Передаємо сюди queues з MainScreen
    settingsManager: SettingsManager,
    snackbarHostState: SnackbarHostState,
    onBackAction: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedIds by settingsManager.selectedQueueIds.collectAsState(initial = emptySet())
    val showDialog = remember { mutableStateOf(false) }
    val showStartScreenDialog = remember { mutableStateOf(false) }
    val showBatteryOptimizationDialog = remember { mutableStateOf(false) }
    val showBackgroundStartDialog = remember { mutableStateOf(false) }
    val startScreen by settingsManager.startScreen.collectAsState(initial = StartDestinations.ALL_QUEUES)
    val showAlertTimeDialog = remember { mutableStateOf(false) }
    val alertTime by settingsManager.alertTime.collectAsState(initial = 30)

    BackHandler {
        onBackAction()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackAction) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                SettingSectionTitle(stringResource(R.string.settings_my_queues))

                // Підготовка тексту для опису (Subtitle)
                val selectedNames = selectedIds
                    .mapNotNull { idString -> allQueues[idString.toIntOrNull()] }
                    .sorted()

                val queueSummary = if (selectedNames.isEmpty()) {
                    stringResource(R.string.settings_no_queues)
                } else {
                    stringResource(
                        R.string.settings_selected_queues,
                        selectedNames.joinToString(", ")
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showDialog.value = true
                        } // Відкриваємо діалог при натисканні на весь рядок
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_add_my_queues),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = queueSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, // Щоб довгий список не розтягнув екран до нескінченності
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                SettingSectionTitle(stringResource(R.string.settings_navigation_title))

                // Визначаємо текст поточної опції для підзаголовка
                val currentStartScreenName = when (startScreen) {
                    StartDestinations.ALL_QUEUES -> stringResource(R.string.settings_all_queues)
                    StartDestinations.MY_QUEUES -> stringResource(R.string.settings_my_queues)
                    else -> ""
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showStartScreenDialog.value = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_start_screen), // Або відповідний stringResource
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = currentStartScreenName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Заголовок для секції фільтрації
                SettingSectionTitle(stringResource(R.string.settings_filter))

                // Рядок з перемикачем
                val onlyActual by settingsManager.onlyActual.collectAsState(initial = false)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clickable {
                            scope.launch { settingsManager.setOnlyActual(!onlyActual) }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_active_only_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.settings_active_only_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = onlyActual,
                        onCheckedChange = { value ->
                            scope.launch { settingsManager.setOnlyActual(value) }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Заголовок для секції Сповіщень
                SettingSectionTitle(stringResource(R.string.settings_notification_title))

                // Пункт: Час попередження
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAlertTimeDialog.value = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_alert_time_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.settings_alert_time_description, alertTime),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                VolumeSettingItem(context, settingsManager)

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showBatteryOptimizationDialog.value = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_battery_optimization_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showBackgroundStartDialog.value = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_background_startup_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                TestNotificationSection(context, scope, snackbarHostState, onScheduleTest = { minutes ->
                    settingsManager.scheduleTestNotification(context, minutes)
                })
            }
        }
    }

    if (showDialog.value) {
        AlertDialog(
            onDismissRequest = { showDialog.value = false },
            title = { Text(stringResource(R.string.settings_choose_my_queues)) },
            text = {
                if (allQueues.isEmpty()) {
                    // Якщо дані ще не завантажились
                    Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn {
                        // Сортуємо для зручності вибору
                        val sortedQueues = allQueues.toList().sortedBy { it.second }

                        items(sortedQueues) { (id, name) ->
                            val isChecked = selectedIds.contains(id.toString())
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val newSet = selectedIds.toMutableSet()
                                        if (isChecked) newSet.remove(id.toString())
                                        else newSet.add(id.toString())

                                        scope.launch { settingsManager.saveSelectedQueues(newSet) }
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Checkbox(checked = isChecked, onCheckedChange = null)
                                Text(text = name, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog.value = false }) { Text(stringResource(R.string.settings_done)) }
            }
        )
    }

    if (showStartScreenDialog.value) {
        AlertDialog(
            onDismissRequest = { showStartScreenDialog.value = false },
            title = { Text(stringResource(R.string.settings_start_screen)) },
            text = {
                Column {
                    // Опція: Всі черги
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch { settingsManager.setStartScreen(StartDestinations.ALL_QUEUES) }
                                showStartScreenDialog.value = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = startScreen == StartDestinations.ALL_QUEUES,
                            onClick = null // Click обробляється в Row
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_all_queues))
                    }

                    // Опція: Мої черги
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch { settingsManager.setStartScreen(StartDestinations.MY_QUEUES) }
                                showStartScreenDialog.value = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = startScreen == StartDestinations.MY_QUEUES,
                            onClick = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_my_queues))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStartScreenDialog.value = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }

    if (showBatteryOptimizationDialog.value) {
        AlertDialog(
            onDismissRequest = { showBatteryOptimizationDialog.value = false },
            title = { Text(stringResource(R.string.settings_battery_optimization_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_battery_optimization_description))
                    Spacer(modifier = Modifier.height(16.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryOptimizationDialog.value = false
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(intent)
                }) {
                    Text(stringResource(R.string.settings_title))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBatteryOptimizationDialog.value = false
                }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }

    if (showBackgroundStartDialog.value) {
        AlertDialog(
            onDismissRequest = { showBackgroundStartDialog.value = false },
            title = { Text(stringResource(R.string.settings_background_startup_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_background_startup_description))
                    Spacer(modifier = Modifier.height(16.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showBackgroundStartDialog.value = false
                    openBackgroundSettings(context)
                }) {
                    Text(stringResource(R.string.settings_title))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBackgroundStartDialog.value = false
                }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }

    if (showAlertTimeDialog.value) {
        AlertDialog(
            onDismissRequest = { showAlertTimeDialog.value = false },
            title = { Text(stringResource(R.string.settings_alert_time_title)) },
            text = {
                Column {
                    listOf(30, 20, 10).forEach { mins ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        settingsManager.saveAlertTime(mins)
                                    }
                                    showAlertTimeDialog.value = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = alertTime == mins,
                                onClick = null // Обробка в Row
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(text = stringResource(R.string.settings_alert_time_value, mins))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAlertTimeDialog.value = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        )
    }
}

@Composable
fun SettingSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun VolumeSettingItem(context: Context, settingsManager: SettingsManager) {
    //val context = LocalContext.current
    // Збираємо значення з DataStore
    val volume by settingsManager.notificationVolume.collectAsState(initial = 0.5f)
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            //.padding(16.dp)
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_volume, (volume * 100).toInt()),
            style = MaterialTheme.typography.titleMedium
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeMute,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )

            Slider(
                value = volume,
                onValueChange = { newValue ->
                    // Використовуємо scope для запуску suspend функції збереження
                    scope.launch {
                        settingsManager.saveVolume(newValue)
                    }
                },
                onValueChangeFinished = {
                    playPreviewSound(context, volume)
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                valueRange = 0f..1f
            )

            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }

        Text(
            text = stringResource(R.string.settings_volume_description),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

// Функція для швидкої перевірки звуку
private fun playPreviewSound(context: Context, volume: Float) {
    val mediaPlayer = MediaPlayer() // Створюємо пустий об'єкт
    try {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        mediaPlayer.setAudioAttributes(audioAttributes) // Встановлюємо атрибути ДО підготовки

        // Вказуємо шлях до файлу через дескриптор ресурсу
        val assetFileDescriptor = context.resources.openRawResourceFd(R.raw.notification)
        mediaPlayer.setDataSource(
            assetFileDescriptor.fileDescriptor,
            assetFileDescriptor.startOffset,
            assetFileDescriptor.length
        )
        assetFileDescriptor.close()

        mediaPlayer.setVolume(volume, volume)
        mediaPlayer.prepare() // Тепер підготовлюємо
        mediaPlayer.start()

        mediaPlayer.setOnCompletionListener {
            it.release()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error in playPreviewSound", e)
        mediaPlayer.release() // Обов'язково звільняємо при помилці
    }
}

@Composable
fun TestNotificationSection(
    context: Context,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    onScheduleTest: (Int) -> Unit
) {
    Column(modifier = Modifier
        .fillMaxWidth()
        //.padding(16.dp)
        .padding(vertical = 8.dp)
    ) {
        Text(stringResource(R.string.settings_notification_test_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_notification_test_description), style = MaterialTheme.typography.bodySmall)

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf(1, 5, 10, 30).forEach { mins ->
                Button(onClick = {
                    onScheduleTest(mins)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = context.getString(R.string.settings_notification_test_toast, mins),
                            duration = SnackbarDuration.Short
                        )
                    }
                }) {
                    Text(stringResource(R.string.settings_notification_test_time, mins))
                }
            }
        }
    }
}

private val manufacturerGroups = listOf(
    setOf("huawei", "honor") to listOf(
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity")
    ),
    setOf("xiaomi", "redmi", "poco") to listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.miui.securitycenter", "com.miui.powerkeeper.ui.PowerHideActivity"),
        ComponentName("com.miui.permcenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
    ),
    setOf("vivo", "iqoo") to listOf(
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.vivo.securedaemonservice", "com.vivo.securedaemonservice.activity.BgStartUpManagerActivity")
    ),
    setOf("oppo", "realme", "oneplus") to listOf(
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.AppAutoLaunchActivity"),
        ComponentName("com.realme.securitycenter", "com.realme.securitycenter.startupapp.StartupAppListActivity"),
        ComponentName("com.coloros.oppoguardelf", "com.coloros.oppoguardelf.ui.startup.StartupAppListActivity")
    ),
    setOf("asus") to listOf(
        ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"),
        ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
        ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartServiceActivity")
    ),
    setOf("nokia") to listOf(
        ComponentName("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"),
        ComponentName("com.evenwell.powersaving.g2", "com.evenwell.powersaving.g2.exception.PowerSaverExceptionActivity")
    ),
    setOf("letv") to listOf(
        ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")
    ),
    setOf("lenovo", "motorola") to listOf(
        ComponentName("com.lenovo.security", "com.lenovo.security.powersetting.PowerAppsWhiteListActivity"),
        ComponentName("com.lenovo.safecenter", "com.lenovo.safecenter.autostart.AutoStartActivity")
    ),
    setOf("meizu") to listOf(
        ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
        ComponentName("com.meizu.safe", "com.meizu.safe.permission.PermissionMainActivity")
    ),
    setOf("samsung") to listOf(
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity"),
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
    )
)

fun openBackgroundSettings(context: Context) {
    val manufacturer = Build.MANUFACTURER.lowercase()

    // Шукаємо першу групу, яка містить назву нашого виробника
    val components = manufacturerGroups.find { (brands, _) ->
        brands.contains(manufacturer)
    }?.second

    var started = false

    components?.forEach { component ->
        if (!started) {
            val intent = Intent().apply {
                setComponent(component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (isActivityExists(context, intent)) {
                try {
                    context.startActivity(intent)
                    started = true
                } catch (_: Exception) {
                    // Ігноруємо та пробуємо наступний компонент у списку
                }
            }
        }
    }

    // Резервний план (якщо бренд не в списку або нічого не спрацювало)
    if (!started) {
        val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallbackIntent)
    }
}

// Допоміжна функція для перевірки існування Activity
private fun isActivityExists(context: Context, intent: Intent): Boolean {
    val list = context.packageManager.queryIntentActivities(
        intent, PackageManager.MATCH_DEFAULT_ONLY
    )
    return list.isNotEmpty()
}
package com.mykolaiv.energy

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mykolaiv.energy.ui.theme.EnergyAppMainTheme
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "MainActivity"
var mock = false
class MainActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager
    private lateinit var snackbarHostState: SnackbarHostState
    private val appName by lazy { getString(R.string.app_name) }
    private val notificationChannelDescription by lazy { getString(R.string.notification_channel_description) }
    private val notificationEnabled by lazy { getString(R.string.notification_enabled) }
    private val notificationWarning by lazy { getString(R.string.notification_warning) }
    private var startDestinationSetting: String? by mutableStateOf(null)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)

        // 1. Створюємо канал (завжди)
        createNotificationChannel()

        // 2. Перевіряємо, чи вже є дозвіл. Якщо так — запускаємо сервіс.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            setupBackgroundWork(this)
        }

        // 3. Запускаємо логіку перевірки/запиту дозволу (якщо дозволу немає, вона викличе лаунчер)
        checkNotificationPermission()
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        splashScreen.setKeepOnScreenCondition { startDestinationSetting == null }

        lifecycleScope.launch {
            // Отримуємо перше значення з Flow і записуємо в нашу змінну
            startDestinationSetting = settingsManager.startScreen.first()
        }

        setContent {
            val navController = rememberNavController()
            val hostState = remember { SnackbarHostState() }
            snackbarHostState = hostState
            val onlyActual by settingsManager.onlyActual.collectAsState(initial = false)

            EnergyAppMainTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val apiService = remember { ApiService.create() }
                val scaffoldState = rememberBottomSheetScaffoldState(
                    bottomSheetState = rememberStandardBottomSheetState(
                        initialValue = SheetValue.PartiallyExpanded,
                        skipHiddenState = true
                    )
                )

                val view = LocalView.current
                val window = (view.context as Activity).window

                val systemBarColor = MaterialTheme.colorScheme.surfaceContainer

                SideEffect {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = systemBarColor.luminance() > 0.5f
                        isAppearanceLightNavigationBars = systemBarColor.luminance() > 0.5f
                    }
                }

                var queues by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
                var times by remember { mutableStateOf<Map<Int, TimeSeries>>(emptyMap()) }
                var series by remember { mutableStateOf<List<OutageItem>>(emptyList()) }
                var emergencyQueues by remember { mutableStateOf<List<EmergencyQueue>>(emptyList()) }
                var emergencySpecialQueues by remember {
                    mutableStateOf<List<EmergencySpecialQueue>>(
                        emptyList()
                    )
                }
                val isEmergencyActive = emergencyQueues.any { it.enable == 1 } ||
                        emergencySpecialQueues.any { it.enable == 1 }
                val bottomSheetColor =
                    if (isEmergencyActive) Color(0xFFE53935) else Color(0xFF43A047)
                var isLoading by remember { mutableStateOf(true) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                var errorType by remember { mutableIntStateOf(0) } // 0: OK, 1: Empty, 2: Error

                // Відстежуємо поточний маршрут для підсвічування кнопок
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val onBackAction: () -> Unit = {
                    val startRoute = startDestinationSetting ?: "main"
                    if (currentRoute == startRoute) {
                        (navController.context as? Activity)?.finish()
                    } else {
                        navController.navigate(startRoute) {
                            popUpTo(navController.graph.startDestinationId) {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    }
                }

                // Визначаємо, чи потрібно показувати нижню панель
                val showBottomSheet = currentRoute == "main" || currentRoute == "my_queues"

                // Анімуємо зміну висоти розгортання
                val animatedPeekHeight by animateDpAsState(
                    targetValue = if (showBottomSheet) 48.dp else 0.dp,
                    animationSpec = tween(durationMillis = 300), // швидкість розгортання
                    label = "PeekHeightAnimation"
                )

                fun loadData(context: Context) {
                    scope.launch(Dispatchers.IO) {
                        isLoading = true
                        errorMessage = null
                        val qRes: List<Queue>
                        val tRes: List<TimeSeries>
                        val aRes: List<ActiveOutage>
                        try {
                            if (!mock) {
                                qRes = apiService.getQueues()
                                tRes = apiService.getTimeSeries()
                                aRes = apiService.getActiveOutages()
                            } else {
                                val qJson = context.readAssetFile("3.json")
                                val tJson = context.readAssetFile("time-series.json")
                                val aJson = context.readAssetFile("active.json")

                                qRes = Gson().fromJson(
                                    qJson,
                                    object : TypeToken<List<Queue>>() {}.type
                                )
                                tRes = Gson().fromJson(
                                    tJson,
                                    object : TypeToken<List<TimeSeries>>() {}.type
                                )
                                aRes = Gson().fromJson(
                                    aJson,
                                    object : TypeToken<List<ActiveOutage>>() {}.type
                                )
                            }
                            val gavRes = apiService.getEmergencyQueues()
                            val sgavRes = apiService.getEmergencySpecialQueues()

                            withContext(Dispatchers.Main) {
                                queues = qRes.associateBy({ it.id }, { it.name })
                                times = tRes.associateBy { it.id }

                                // Отримання графіків відключень
                                val allSeries = aRes.flatMap { outageBlock ->
                                    outageBlock.series.map { item ->
                                        item.copy(dateTo = outageBlock.to)
                                    }
                                }

                                series = allSeries

                                // Повідомлення на екрані, якщо графіків немає
                                if (series.isEmpty()) {
                                    errorMessage = context.getString(R.string.no_schedules)
                                    errorType = 1 // Стан "Порожньо"
                                } else {
                                    errorMessage = null
                                    errorType = 0
                                }

                                emergencyQueues = gavRes.sortedBy { it.id }
                                emergencySpecialQueues = sgavRes.sortedBy { it.id }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                errorMessage = context.getString(
                                    R.string.connection_error,
                                    e.localizedMessage ?: "Unknown error"
                                )
                                errorType = 2 // Стан "Помилка мережі"
                            }
                        } finally {
                            isLoading = false
                        }
                    }
                }

                @OptIn(ExperimentalLayoutApi::class)
                @Composable
                fun EmergencySection(queues: List<EmergencyQueue>) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            //.background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Заголовок секції
                        Text(
                            text = stringResource(R.string.emergency_queues),
                            //color = Color.LightGray,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp),
                            textAlign = TextAlign.Center
                        )

                        // Гнучка сітка для прямокутників
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            queues.forEach { queue ->
                                // Стан: 1 - червоний (актуальне відключення), 0 - зелений (є світло)
                                val isActive = queue.enable == 1
                                val color = if (isActive) Color(0xFFE53935) else Color(0xFF43A047)

                                Box(
                                    modifier = Modifier
                                        .background(color, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.emergency_queues_prefix, queue.name),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                @OptIn(ExperimentalLayoutApi::class)
                @Composable
                fun EmergencySpecialSection(queues: List<EmergencySpecialQueue>) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            //.background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Заголовок секції
                        Text(
                            text = stringResource(R.string.emergency_special_queues),
                            //color = Color.LightGray,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp),
                            textAlign = TextAlign.Center
                        )

                        // Гнучка сітка для прямокутників
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            queues.forEach { queue ->
                                // Стан: 1 - червоний (актуальне відключення), 0 - зелений (є світло)
                                val isActive = queue.enable == 1
                                val color = if (isActive) Color(0xFFE53935) else Color(0xFF43A047)

                                Box(
                                    modifier = Modifier
                                        .background(color, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.emergency_special_queues_prefix,
                                            queue.name
                                        ),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                LaunchedEffect(currentRoute) {
                    if (!showBottomSheet && scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded) {
                        scaffoldState.bottomSheetState.partialExpand()
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        //.background(systemBarColor)
                ) {
                    Scaffold(
                        containerColor = systemBarColor,
                        //snackbarHost = { SnackbarHost(snackbarHostState) },
                        snackbarHost = {
                            SnackbarHost(hostState = snackbarHostState) { data ->
                                Snackbar(
                                    modifier = Modifier.padding(12.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    actionContentColor = MaterialTheme.colorScheme.primary,
                                    action = {
                                        data.visuals.actionLabel?.let { actionLabel ->
                                            TextButton(onClick = { data.performAction() }) {
                                                Text(actionLabel)
                                            }
                                        }
                                    },
                                    content = {
                                        Text(
                                            text = data.visuals.message,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                )
                            }
                        },
                        bottomBar = {
                            NavigationBar(
                                // Можна додати фон, якщо він здається занадто прозорим
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            Icons.Default.Home,
                                            contentDescription = null
                                        )
                                    }, // Можна замінити на Home
                                    label = { Text(
                                        text = stringResource(R.string.all_queues),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    ) },
                                    selected = currentRoute == "main",
                                    onClick = {
                                        if (currentRoute != "main") {
                                            navController.navigate("main") {
                                                popUpTo("main") { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    }
                                )
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = null
                                        )
                                    }, // Замініть на Person/Star
                                    label = { Text(
                                        text = stringResource(R.string.my_queues_title),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    ) },
                                    selected = currentRoute == "my_queues",
                                    onClick = {
                                        navController.navigate("my_queues") {
                                            popUpTo("main") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                                    label = { Text(
                                        text = stringResource(R.string.queues_info_short_title),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    ) },
                                    selected = currentRoute == "queue_info",
                                    onClick = {
                                        navController.navigate("queue_info") {
                                            popUpTo("main") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                                NavigationBarItem(
                                    icon = { Icon(Icons.Default.PowerOff, contentDescription = null) },
                                    label = { Text(
                                        text = stringResource(R.string.poweroff_info_title),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    ) },
                                    selected = currentRoute == "poweroff_info",
                                    onClick = {
                                        navController.navigate("poweroff_info") {
                                            popUpTo("main") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            Icons.Default.Settings,
                                            contentDescription = null
                                        )
                                    },
                                    label = { Text(
                                        text = stringResource(R.string.settings_title),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    ) },
                                    selected = currentRoute == "settings",
                                    onClick = {
                                        navController.navigate("settings") {
                                            popUpTo("main") { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                )
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(systemBarColor)
                                .padding(innerPadding)
                        ) {
                            BottomSheetScaffold(
                                modifier = Modifier.consumeWindowInsets(innerPadding),
                                scaffoldState = scaffoldState,
                                sheetPeekHeight = animatedPeekHeight, // 👈 Панель просто "піде" вниз
                                sheetSwipeEnabled = showBottomSheet, // Вимикаємо свайп, коли панель прихована
                                sheetDragHandle = {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp),
                                        color = bottomSheetColor,
                                        shape = RoundedCornerShape(
                                            topStart = 16.dp,
                                            topEnd = 16.dp
                                        ),
                                        onClick = {
                                            scope.launch {
                                                if (scaffoldState.bottomSheetState.currentValue == SheetValue.PartiallyExpanded) {
                                                    scaffoldState.bottomSheetState.expand()
                                                } else {
                                                    scaffoldState.bottomSheetState.partialExpand()
                                                }
                                            }
                                        }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                stringResource(R.string.emergency_queues_panel_title),
                                                color = Color.White
                                            )
                                        }
                                    }
                                },
                                sheetContent = {
                                    // Контент панелі
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                            .heightIn(
                                                min = 100.dp,
                                                max = 500.dp
                                            ) // Обмежуємо висоту, щоб не закрила весь екран
                                    ) {
                                        if (emergencyQueues.isNotEmpty()) {
                                            EmergencySection(queues = emergencyQueues)
                                        }
                                        if (emergencySpecialQueues.isNotEmpty()) {
                                            EmergencySpecialSection(
                                                queues = emergencySpecialQueues
                                            )
                                        }
                                    }
                                }
                            ) { sheetPadding ->
                                // Контент ваших екранів (NavHost)
                                // Важливо: додаємо padding(sheetPadding), щоб контент екрана не ховався за ярликом
                                Box(
                                    modifier = Modifier
                                    .fillMaxSize()
                                    .padding(sheetPadding)
                                ) {
                                    if (startDestinationSetting != null) {
                                        //val navController = rememberNavController()
                                        NavHost(
                                            navController = navController,
                                            startDestination = startDestinationSetting!!
                                        ) {
                                            composable("main") {
                                                MainScreen(
                                                    series = series,
                                                    queues = queues,
                                                    times = times,
                                                    isLoading = isLoading,
                                                    errorMessage = errorMessage,
                                                    errorType = errorType,
                                                    onlyActual = onlyActual,
                                                    onRefresh = { loadData(context) },
                                                    onBackAction = onBackAction
                                                )
                                            }
                                            composable("my_queues") {
                                                MyQueuesScreen(
                                                    navController = navController,
                                                    series = series,
                                                    queues = queues,
                                                    times = times,
                                                    isLoading = isLoading,
                                                    errorMessage = errorMessage,
                                                    errorType = errorType,
                                                    onRefresh = { loadData(context) },
                                                    onlyActual = onlyActual,
                                                    settingsManager = settingsManager, // Створений в onCreate
                                                    snackbarHostState = snackbarHostState,
                                                    onBackAction = onBackAction
                                                )
                                            }
                                            composable("queue_info") {
                                                QueueInfoScreen(
                                                    navController = navController,
                                                    snackbarHostState = snackbarHostState,
                                                    onBackAction = onBackAction
                                                )
                                            }
                                            composable("poweroff_info") {
                                                PoweroffInfoScreen(
                                                    navController = navController,
                                                    settingsManager = settingsManager,
                                                    snackbarHostState = snackbarHostState,
                                                    onBackAction = onBackAction
                                                )
                                            }
                                            composable("settings") {
                                                SettingsScreen(
                                                    navController = navController,
                                                    allQueues = queues,
                                                    settingsManager = settingsManager,
                                                    snackbarHostState = snackbarHostState,
                                                    onBackAction = onBackAction
                                                )
                                            }
                                            composable(
                                                "pdf_viewer/{url}/{title}",
                                                arguments = listOf(
                                                    navArgument("url") {
                                                        type = NavType.StringType
                                                    },
                                                    navArgument("title") {
                                                        type = NavType.StringType
                                                    }
                                                )
                                            ) { backStackEntry ->
                                                val url =
                                                    backStackEntry.arguments?.getString("url") ?: ""
                                                val title =
                                                    backStackEntry.arguments?.getString("title")
                                                        ?: "PDF"
                                                PdfViewerScreen(
                                                    url,
                                                    title,
                                                    navController,
                                                    snackbarHostState = snackbarHostState,
                                                    onBackAction = onBackAction
                                                )
                                            }
                                        }
                                    } else {
                                        // Заставка, поки вантажаться налаштування
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        lifecycleScope.launch {
            if (isGranted) {
                // 1. Повідомляємо користувача, що все добре
                snackbarHostState.showSnackbar(
                    message = notificationEnabled,
                    duration = SnackbarDuration.Short
                )

                // 2. Активуємо фоновий сервіс, щоб він міг надсилати сповіщення
                setupBackgroundWork(this@MainActivity)
            } else {
                // Можна пояснити, чому програма не зможе попереджати про відключення
                snackbarHostState.showSnackbar(
                    message = notificationWarning,
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = appName
            val descriptionText = notificationChannelDescription
            val importance = NotificationManager.IMPORTANCE_HIGH // Щоб сповіщення спливало зверху
            val channel = NotificationChannel("OUTAGE_NOTIFICATIONS", name, importance).apply {
                description = descriptionText
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(1000, 1000, 1000)
                // Дозволяємо випливаюче вікно поверх усього
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    series: List<OutageItem>,
    queues: Map<Int, String>,
    times: Map<Int, TimeSeries>,
    isLoading: Boolean,
    errorMessage: String?,
    errorType: Int,
    onlyActual: Boolean,
    onRefresh: () -> Unit,
    onBackAction: () -> Unit
) {
    val context = LocalContext.current
    val allQueuesLabel = stringResource(R.string.all_queues)

    var selectedQueue by rememberSaveable { mutableStateOf(allQueuesLabel) }
    var expanded by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Обробка дат з API
    val availableDates = remember(series) {
        if (series.isEmpty()) {
            emptyList()
        } else {
            // ОБОВ'ЯЗКОВО додаємо take(10) тут:
            val datesFromApi = series.mapNotNull { it.dateTo?.take(10) }.distinct().sorted()
            val today = LocalDate.now().toString()

            if (datesFromApi.size == 1 && datesFromApi.first() > today) {
                listOf(today, datesFromApi.first())
            } else {
                datesFromApi
            }
        }
    }

    // Стан вибраної дати (за замовчуванням остання доступна або сьогодні)
    // 1. Отримуємо рядок "сьогодні" один раз
    val currentToday = getTodayDateString()

    // 2. Стан вибраної дати з пріоритетом на "сьогодні"
    var selectedDate by remember(availableDates) {
        // Цей блок спрацює, коли зміняться availableDates (наприклад, після loadData)
        val bestDate = when {
            availableDates.contains(currentToday) -> currentToday
            availableDates.isNotEmpty() -> availableDates.first()
            else -> currentToday
        }
        mutableStateOf(bestDate)
    }

    // Якщо користувач не закривав програму, а доба змінилася
    LaunchedEffect(Unit) {
        while(true) {
            // Оновлюємо хвилини для підсвітки карток
            currentTime = System.currentTimeMillis()

            // Перевіряємо, чи не змінилася доба
            val realTimeToday = getTodayDateString()
            if (selectedDate < realTimeToday && availableDates.contains(realTimeToday)) {
                selectedDate = realTimeToday
            }

            kotlinx.coroutines.delay(60_000.milliseconds)
        }
    }

    // Автооновлення при поверненні в додаток (Resume)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentTime = System.currentTimeMillis()
                onRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler {
        onBackAction()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                // Додаємо фон для всієї шапки, щоб вона не зливалася
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    //color = Color(0xFF1a1c1e),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = 0.dp
                ) {
                    Column(
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Вибір черги (Dropdown)
                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = !expanded },
                                modifier = Modifier.weight(1f)
                            ) {
                                TextField(
                                    value = selectedQueue,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = {
                                        Text(
                                            stringResource(R.string.choose_queue),
                                            //color = Color.White.copy(0.7f)
                                        )
                                    },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                    /*colors = ExposedDropdownMenuDefaults.textFieldColors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedContainerColor = Color(0xFF2c2f33),
                                        unfocusedContainerColor = Color(0xFF2c2f33)
                                    ),*/
                                    colors = ExposedDropdownMenuDefaults.textFieldColors(
                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.menuAnchor(
                                        type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                        enabled = true
                                    )
                                )
                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(allQueuesLabel) },
                                        onClick = { selectedQueue = allQueuesLabel; expanded = false }
                                    )
                                    queues.values.sortedBy { it }.forEach { name ->
                                        DropdownMenuItem(
                                            text = { Text(name) },
                                            onClick = { selectedQueue = name; expanded = false }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Кнопка "Оновити"
                            IconButton(
                                onClick = { onRefresh() },
                                enabled = !isLoading,
                                //colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = "Оновити")
                                }
                            }
                            if (availableDates.isNotEmpty()) {
                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(
                                            Icons.Default.DateRange,
                                            contentDescription = "Вибрати дату"
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        availableDates.forEach { date ->
                                            DropdownMenuItem(
                                                text = {
                                                    val label =
                                                        if (date == LocalDate.now().toString())
                                                            stringResource(
                                                                R.string.today_queue,
                                                                formatDisplayDate(date)
                                                            )
                                                        else formatDisplayDate(date)
                                                    Text(label)
                                                },
                                                onClick = {
                                                    selectedDate = date
                                                    showMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Додаємо selectedDate в залежності remember
                    val filteredItems by remember(series, selectedQueue, onlyActual, selectedDate, currentTime) {
                        derivedStateOf {
                            series.filter { item ->
                                val qName = queues[item.outageQueueId] ?: ""
                                val tInfo = times[item.timeSeriesId]

                                // ЗАМІНА: Замість isScheduleForToday використовуємо selectedDate з комбобоксу
                                val dateMatch = item.dateTo?.take(10) == selectedDate

                                val queueMatch =
                                    selectedQueue == allQueuesLabel || qName == selectedQueue

                                val actualMatch =
                                    !onlyActual || !isToday(selectedDate) || (tInfo != null && !isPast(tInfo.end))

                                // Тепер елемент проходить, якщо збігаються ВСІ три умови
                                dateMatch && queueMatch && actualMatch
                            }.sortedBy {
                                (queues[it.outageQueueId] ?: "") + (times[it.timeSeriesId]?.start ?: "")
                            }
                        }
                    }

                    // Об'єднуємо результати перед виводом у LazyColumn
                    val mergedItems = remember(filteredItems, times, currentTime) {
                        mergeScheduleItems(filteredItems, times)
                    }

                    // Відображення помилок
                    when {
                        isLoading && series.isEmpty() -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                                color = colorResource(R.color.blue)
                            )
                        }

                        // Якщо сталася помилка мережі (errorType 2)
                        errorType == 2 && series.isEmpty() -> {
                            ErrorPlaceholder(
                                icon = Icons.Default.Warning,
                                message = errorMessage ?: stringResource(R.string.network_error),
                                onRetry = { onRefresh() }
                            )
                        }

                        // Якщо даних немає взагалі або фільтр по сьогоднішньому дню порожній
                        !isLoading && filteredItems.isEmpty() -> {
                            val message = if (series.isEmpty()) {
                                stringResource(R.string.no_schedules) // Немає даних від API
                            } else {
                                stringResource(R.string.no_today_schedules) // Дані є (наприклад на завтра), але не на сьогодні
                            }

                            ErrorPlaceholder(
                                icon = Icons.Default.Info,
                                message = message,
                                color = colorResource(R.color.orange)
                            )
                        }

                        else -> {
                            Column(modifier = Modifier.fillMaxSize()) {
                                if (availableDates.isNotEmpty()) {
                                    Text(
                                        text = formatDisplayDate(selectedDate),
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp) // Відступи, щоб текст не тулився до країв
                                    )
                                }

                                // Список ГПВ
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(
                                        top = 8.dp,
                                        bottom = 16.dp,
                                        start = 8.dp,
                                        end = 8.dp
                                    )
                                ) {
                                    // Додаємо key, який залежить від черги, ID тайм-серії та часу початку/кінця
                                    items(
                                        items = mergedItems,
                                        key = { (item, tInfo) -> "${item.outageQueueId}_${tInfo.id}_${tInfo.start}_${tInfo.end}" }
                                    ) { (item, tInfo) ->
                                        val qName = queues[item.outageQueueId] ?: "ID: ${item.outageQueueId}"

                                        OutageCard(
                                            context = context,
                                            item = item,
                                            queueName = qName,
                                            time = tInfo,
                                            isCurrent = isToday(selectedDate) && isTimeCurrent(tInfo.start, tInfo.end)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Допоміжна функція для відображення помилок
@Composable
fun BoxScope.ErrorPlaceholder(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String,
    color: Color = colorResource(R.color.orange),
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.align(Alignment.Center).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            color = color,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                modifier = Modifier.padding(top = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colorResource(R.color.blue))
            ) {
                Text(stringResource(R.string.try_again))
            }
        }
    }
}

fun isToday(dateString: String): Boolean {
    val today = LocalDate.now().toString().take(10)
    return dateString == today
}

// Допоміжна функція для фільтра "Тільки актуальні"
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
fun isPast(end: String): Boolean {
    if (end == "00:00:00") return false // Останній слот доби вважаємо актуальним до кінця
    return try {
        val now = LocalTime.now()
        val e = LocalTime.parse(end, timeFormatter)
        now.isAfter(e)
    } catch (e: Exception) {
        Log.e(TAG, "Error in isPast", e)
        false
    }
}

fun Context.readAssetFile(fileName: String): String {
    return this.assets.open(fileName).bufferedReader().use { it.readText() }
}

fun mergeScheduleItems(
    items: List<OutageItem>,
    times: Map<Int, TimeSeries>
): List<Pair<OutageItem, TimeSeries>> {
    if (items.isEmpty()) return emptyList()

    val result = mutableListOf<Pair<OutageItem, TimeSeries>>()

    var currentItem = items[0]
    var currentTime = times[currentItem.timeSeriesId] ?: return emptyList()

    for (i in 1 until items.size) {
        val nextItem = items[i]
        val nextTime = times[nextItem.timeSeriesId] ?: continue

        // 1. Перевірка, чи обидва блоки є жорстким відключенням
        val isBothStrictOff = (currentItem.type == "OFF" || currentItem.type == "SURE_OFF") &&
                (nextItem.type == "OFF" || nextItem.type == "SURE_OFF")

        // 2. Умова однакового типу: або типи ідентичні (наприклад, обидва PROBABLY_OFF), або обидва strict off
        val sameType = currentItem.type == nextItem.type || isBothStrictOff
        val sameQueue = currentItem.outageQueueId == nextItem.outageQueueId
        val continuousTime = currentTime.end == nextTime.start

        if (sameType && sameQueue && continuousTime) {
            // Об'єднуємо часовий проміжок
            currentTime = TimeSeries(
                id = currentTime.id,
                start = currentTime.start,
                end = nextTime.end
            )

            // Визначаємо результуючий тип для блоку відключень
            val finalType = when {
                currentItem.type == "SURE_OFF" || nextItem.type == "SURE_OFF" -> "SURE_OFF"
                currentItem.type == "OFF" || nextItem.type == "OFF" -> "OFF"
                else -> "PROBABLY_OFF"
            }

            // КРИТИЧНО: Оновлюємо currentItem, підставляючи дані нового кроку
            // та синхронізуючи ID тайм-серії, щоб уникнути багів відображення в картках
            currentItem = nextItem.copy(
                type = finalType,
                timeSeriesId = currentTime.id
            )
        } else {
            // Ланцюжок розірвався — зберігаємо накопичений блок
            result.add(currentItem to currentTime)

            // Починаємо новий блок
            currentItem = nextItem
            currentTime = nextTime
        }
    }

    // Не забуваємо додати фінальний хвіст
    result.add(currentItem to currentTime)

    return result
}

fun getTodayDateString(): String {
    return LocalDate.now().toString().take(10)
}

fun formatDisplayDate(dateString: String): String {
    return try {
        // Якщо рядок вже обрізаний до 10 символів або повний ISO
        val cleanDate = dateString.take(10)
        val parts = cleanDate.split("-")
        if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else dateString
    } catch (e: Exception) {
        Log.e(TAG, "Error in formatDisplayDate", e)
        dateString
    }
}

fun setupBackgroundWork(context: Context) {
    Log.d(TAG, "setupBackgroundWork")
    val workRequest = PeriodicWorkRequestBuilder<ScheduleWorker>(
        15, TimeUnit.MINUTES // Мінімальний інтервал для Android — 15 хв
    ).setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // Тільки з інтернетом
            .build()
    ).build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "ScheduleCheck",
        ExistingPeriodicWorkPolicy.UPDATE, // Не перезапускати, якщо вже працює
        workRequest
    )
}
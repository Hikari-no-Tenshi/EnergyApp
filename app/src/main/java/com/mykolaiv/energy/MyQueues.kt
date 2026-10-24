package com.mykolaiv.energy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyQueuesScreen(
    @Suppress("unused") navController: NavController,
    series: List<OutageItem>,
    queues: Map<Int, String>,
    times: Map<Int, TimeSeries>,
    isLoading: Boolean,
    errorMessage: String?,
    errorType: Int,
    onRefresh: () -> Unit,
    onlyActual: Boolean,
    settingsManager: SettingsManager,
    @Suppress("unused") snackbarHostState: SnackbarHostState,
    onBackAction: () -> Unit
) {
    val context = LocalContext.current
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

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

    // Підписуємося на список ID вибраних черг із DataStore
    val selectedIds by settingsManager.selectedQueueIds.collectAsState(initial = emptySet())

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
    //val todayDate = remember { LocalDate.now().toString().take(10) }
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

    // 1. Фільтруємо графіки: тільки ті, що вказані в налаштуваннях і на сьогодні
    val myFilteredItems = remember(series, selectedIds, selectedDate, onlyActual, currentTime) {
        series.filter { item ->
            // ОБОВ'ЯЗКОВО: беремо перші 10 символів від дати з API
            val dateFromApi = item.dateTo?.take(10)
            //println("DEBUG: Comparing Api:$dateFromApi with Selected:$selectedDate")
            val dateMatch = dateFromApi == selectedDate

            val isMyQueue = selectedIds.contains(item.outageQueueId.toString())

            val tInfo = times[item.timeSeriesId]
            val actualMatch = !onlyActual || !isToday(selectedDate) || (tInfo != null && !isPast(tInfo.end))

            dateMatch && isMyQueue && actualMatch
        }.sortedBy {
            (queues[it.outageQueueId] ?: "") + (times[it.timeSeriesId]?.start ?: "")
        }
    }

    // 2. Об'єднуємо суміжні блоки (OFF/SURE_OFF), як ми робили в MainScreen
    val mergedMyItems = remember(myFilteredItems, times, currentTime) {
        mergeScheduleItems(myFilteredItems, times)
    }

    BackHandler {
        onBackAction()
    }

    Scaffold(
        topBar = {
            var menuExpanded by remember { mutableStateOf(false) }
            TopAppBar(
                title = { Text(stringResource(R.string.my_queues_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackAction) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
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
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.DateRange, contentDescription = "Вибрати дату")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                availableDates.forEach { date ->
                                    DropdownMenuItem(
                                        text = {
                                            val label = if (date == LocalDate.now().toString())
                                                stringResource(R.string.today_queue,formatDisplayDate(date))
                                            else formatDisplayDate(date)
                                            Text(label)
                                        },
                                        onClick = {
                                            selectedDate = date
                                            menuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
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
            when {
                // Якщо користувач ще нічого не вибрав у налаштуваннях
                selectedIds.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.no_my_queues),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

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
                        message = errorMessage ?: "Помилка з'єднання",
                        onRetry = { onRefresh() }
                    )
                }

                // Якщо даних немає взагалі або фільтр по сьогоднішньому дню порожній
                !isLoading && myFilteredItems.isEmpty() -> {
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

                // Відображення списку вибраних черг
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

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                top = 8.dp,
                                bottom = 16.dp,
                                start = 8.dp,
                                end = 8.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(mergedMyItems) { (item, tInfo) ->
                                val qName = queues[item.outageQueueId] ?: "ID: ${item.outageQueueId}"

                                OutageCard(
                                    context,
                                    item,
                                    qName,
                                    tInfo,
                                    isToday(selectedDate) && isTimeCurrent(tInfo.start, tInfo.end)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
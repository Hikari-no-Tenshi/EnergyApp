package com.mykolaiv.energy

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.gson.Gson
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoweroffSearchableSelect(
    label: String,
    selectedName: String,
    items: List<Pair<Int, String>>, // ID та Назва
    onItemSelected: (Int, String) -> Unit,
    enabled: Boolean = true
) {
    var isSheetOpen by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    val filteredItems = items.filter { it.second.contains(searchText, ignoreCase = true) }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        OutlinedButton(
            onClick = { isSheetOpen = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(if (selectedName.isEmpty()) label else "$label $selectedName")
        }

        if (isSheetOpen) {
            ModalBottomSheet(onDismissRequest = { isSheetOpen = false }) {
                Column(modifier = Modifier.fillMaxHeight(0.8f).padding(16.dp)) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        label = { Text(stringResource(R.string.searching)) },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Search, null) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn {
                        items(filteredItems) { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    onItemSelected(id, name)
                                    isSheetOpen = false
                                    searchText = ""
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoweroffInfoScreen(
    @Suppress("unused") navController: NavController,
    settingsManager: SettingsManager,
    snackbarHostState: SnackbarHostState,
    onBackAction: () -> Unit
) {
    val api = remember { ApiService.create() }
    val apiPoweroff = remember { ApiPoweroffService.create() }
    val scope = rememberCoroutineScope()
    val gson = remember { Gson() }

    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalActivity.current as? ComponentActivity
        ?: throw IllegalStateException("LocalActivity is not a ComponentActivity")

    val poweroffInfoViewModel: PoweroffinfoViewModel = viewModel(
        viewModelStoreOwner = activity,
        factory = PoweroffinfoViewModel.provideFactory(apiPoweroff)
    )

    // Читаємо збережені адреси з DataStore
    val savedAddressesRaw by settingsManager.savedAddresses.collectAsState(initial = emptySet())

    // Конвертуємо рядки JSON назад у об'єкти
    val savedAddresses = remember(savedAddressesRaw) {
        savedAddressesRaw.map { gson.fromJson(it, SavedAddress::class.java) }
            .sortedBy { it.cityName }
    }

    // Визначаємо поточні параметри для запиту
    val currentBranchId = poweroffInfoViewModel.selectedSavedAddress?.branchId
        ?: poweroffInfoViewModel.selectedBranchId
    val currentHouseId = poweroffInfoViewModel.selectedSavedAddress?.houseId
        ?: poweroffInfoViewModel.selectedHouse?.first

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // 1. Оновлюємо дати у ViewModel на поточні, якщо настав новий день
                poweroffInfoViewModel.refreshDatesIfNeeded()
                // 2. Виконуємо запит при поверненні в додаток
                if (currentBranchId != 0 && currentHouseId != null) {
                    poweroffInfoViewModel.performFetch(currentBranchId, currentHouseId)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // АВТОМАТИЧНЕ ОНОВЛЕННЯ при зміні дат
    LaunchedEffect(poweroffInfoViewModel.startDate, poweroffInfoViewModel.endDate) {
        val currentBranchId = poweroffInfoViewModel.selectedSavedAddress?.branchId ?: poweroffInfoViewModel.selectedBranchId
        val currentHouseId = poweroffInfoViewModel.selectedSavedAddress?.houseId ?: poweroffInfoViewModel.selectedHouse?.first

        if (currentBranchId != 0 && currentHouseId != null) {
            poweroffInfoViewModel.performFetch(currentBranchId, currentHouseId)
        }
    }

    // Завантаження дільниць при старті
    LaunchedEffect(Unit) {
        if (poweroffInfoViewModel.filials.isEmpty()) {
            val res = withContext(Dispatchers.IO) { api.getFilials() }
            poweroffInfoViewModel.filials = res.map { it.idfilial to it.fullname }
        }
    }

    BackHandler {
        onBackAction()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.poweroff_info_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackAction) { // Повернення назад
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад"/*, tint = Color.White*/)
                    }
                },
                actions = {
                    // Кнопка "Оновити"
                    IconButton(
                        onClick = {
                            if (currentBranchId != 0 && currentHouseId != null) {
                                poweroffInfoViewModel.performFetch(currentBranchId, currentHouseId)
                            }
                        },
                        enabled = !poweroffInfoViewModel.isSearching,
                    ) {
                        if (poweroffInfoViewModel.isSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Оновити")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 12.dp).verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // --- ПІКЕРИ ДАТИ ---
            Text(
                stringResource(R.string.poweroff_period),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    DatePickerField(stringResource(R.string.date_from), poweroffInfoViewModel.startDate) { poweroffInfoViewModel.startDate = it }
                }
                Box(modifier = Modifier.weight(1f)) {
                    DatePickerField(stringResource(R.string.date_to), poweroffInfoViewModel.endDate) { poweroffInfoViewModel.endDate = it }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // --- БЛОК ПОШУКУ (Expandable Card) ---
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .animateContentSize(), // Плавна анімація розгортання
                shape = RoundedCornerShape(6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { poweroffInfoViewModel.isManualSearchExpanded = !poweroffInfoViewModel.isManualSearchExpanded } // Тап по всій картці
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.poweroff_search_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    // Підзаголовок з поточною адресою (показуємо, якщо вибрано будинок і картка згорнута)
                    if (!poweroffInfoViewModel.isManualSearchExpanded && poweroffInfoViewModel.selectedHouse != null) {
                        Text(
                            text = "${poweroffInfoViewModel.selectedCity?.second}, ${poweroffInfoViewModel.selectedStreet?.second}, ${poweroffInfoViewModel.selectedHouse?.second}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Icon(
                        imageVector = if (poweroffInfoViewModel.isManualSearchExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    // Вміст, що розгортається
                    if (poweroffInfoViewModel.isManualSearchExpanded) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            // 1. Дільниця
                            PoweroffSearchableSelect(stringResource(R.string.filia_title), poweroffInfoViewModel.selectedFilial?.second ?: "", poweroffInfoViewModel.filials, { id, name ->
                                poweroffInfoViewModel.selectedFilial = id to name
                                poweroffInfoViewModel.selectedBranchId = id
                                poweroffInfoViewModel.selectedSavedAddress = null
                                poweroffInfoViewModel.poweroffList = emptyList()
                                poweroffInfoViewModel.cities = emptyList(); poweroffInfoViewModel.streets = emptyList(); poweroffInfoViewModel.houses = emptyList()
                                scope.launch { poweroffInfoViewModel.cities = apiPoweroff.getPoweroffCities(id).map { it.id to it.name } }
                            })

                            // 2. Населений пункт
                            PoweroffSearchableSelect(stringResource(R.string.city_title), poweroffInfoViewModel.selectedCity?.second ?: "", poweroffInfoViewModel.cities, { id, name ->
                                poweroffInfoViewModel.selectedCity = id to name
                                poweroffInfoViewModel.selectedSavedAddress = null
                                poweroffInfoViewModel.streets = emptyList(); poweroffInfoViewModel.houses = emptyList()
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) { apiPoweroff.getPoweroffStreets(id) }
                                    poweroffInfoViewModel.streets = res.values.map { it.id to (it.name ?: "---") }.sortedBy { it.second }
                                }
                            }, enabled = poweroffInfoViewModel.cities.isNotEmpty())

                            // 3. Вулиця
                            PoweroffSearchableSelect(stringResource(R.string.street_title), poweroffInfoViewModel.selectedStreet?.second ?: "", poweroffInfoViewModel.streets, { id, name ->
                                poweroffInfoViewModel.selectedStreet = id to name
                                poweroffInfoViewModel.selectedSavedAddress = null
                                poweroffInfoViewModel.houses = emptyList()
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) { apiPoweroff.getPoweroffHouses(id) }
                                    poweroffInfoViewModel.houses = res.values.map { it.id to (it.name ?: "---") }.sortedBy { it.second }
                                }
                            }, enabled = poweroffInfoViewModel.streets.isNotEmpty())

                            // 4. Будинок
                            PoweroffSearchableSelect(stringResource(R.string.house_title), poweroffInfoViewModel.selectedHouse?.second ?: "", poweroffInfoViewModel.houses, { id, name ->
                                poweroffInfoViewModel.selectedHouse = id to name
                                poweroffInfoViewModel.selectedSavedAddress = null
                                poweroffInfoViewModel.isManualSearchExpanded = false // АВТОМАТИЧНЕ ЗГОРТАННЯ
                                scope.launch { poweroffInfoViewModel.performFetch(poweroffInfoViewModel.selectedBranchId, id) }
                            }, enabled = poweroffInfoViewModel.houses.isNotEmpty())
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (savedAddresses.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .animateContentSize(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.saved_addresses_label),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = poweroffInfoViewModel.selectedSavedAddress?.displayString ?: stringResource(R.string.choose_from_saved),
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier
                                    .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                    .fillMaxWidth(),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                ),
                                shape = RoundedCornerShape(6.dp),
                                textStyle = MaterialTheme.typography.bodyMedium
                            )

                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                savedAddresses.forEach { address ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                address.displayString,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        },
                                        onClick = {
                                            poweroffInfoViewModel.selectedSavedAddress = address

                                            // Повне скидання ручного пошуку
                                            poweroffInfoViewModel.selectedFilial = null
                                            poweroffInfoViewModel.selectedCity = null
                                            poweroffInfoViewModel.selectedStreet = null
                                            poweroffInfoViewModel.selectedHouse = null
                                            poweroffInfoViewModel.cities = emptyList()
                                            poweroffInfoViewModel.streets = emptyList()
                                            poweroffInfoViewModel.houses = emptyList()
                                            poweroffInfoViewModel.isManualSearchExpanded = false

                                            expanded = false

                                            scope.launch {
                                                poweroffInfoViewModel.performFetch(address.branchId, address.houseId)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // --- КНОПКА ЗБЕРЕЖЕННЯ ---
            // Визначаємо, чи зараз активна адреса зі збережених
            val isFromSaved = poweroffInfoViewModel.selectedSavedAddress != null

            // Умова для активації кнопки:
            // Або ми щось вибрали вручну (можна зберегти),
            // Або ми вибрали щось зі списку (можна видалити).
            val canAction = (poweroffInfoViewModel.selectedFilial != null && poweroffInfoViewModel.selectedCity != null &&
                    poweroffInfoViewModel.selectedStreet != null && poweroffInfoViewModel.selectedHouse != null) || isFromSaved

            val deletedMessage = stringResource(R.string.address_deleted_toast)
            val savedMessage = stringResource(R.string.address_saved_toast)
            val cancelMessage = stringResource(R.string.settings_cancel)

            Button(
                onClick = {
                    if (isFromSaved) {
                        // --- ЛОГІКА ВИДАЛЕННЯ ---
                        // 1. Готуємо дані для видалення
                        val addressToId = gson.toJson(poweroffInfoViewModel.selectedSavedAddress)
                        val originalSet = savedAddressesRaw // Зберігаємо копію старого набору
                        val updatedSet = originalSet.filter { it != addressToId }.toSet()

                        // Зберігаємо посилання на об'єкт, який видаляємо (щоб повернути в UI)
                        val deletedAddressBackup = poweroffInfoViewModel.selectedSavedAddress

                        scope.launch {
                            // 2. Виконуємо видалення
                            settingsManager.saveSavedAddresses(updatedSet)
                            poweroffInfoViewModel.selectedSavedAddress = null
                            poweroffInfoViewModel.poweroffList = emptyList()

                            // 3. Показуємо Snackbar та чекаємо на дію користувача
                            val result = snackbarHostState.showSnackbar(
                                message = deletedMessage,
                                actionLabel = cancelMessage,
                                duration = SnackbarDuration.Long // Краще дати більше часу на роздуми
                            )

                            // 4. Якщо користувач натиснув "Скасувати"
                            if (result == SnackbarResult.ActionPerformed) {
                                settingsManager.saveSavedAddresses(originalSet.toSet())
                                // Повертаємо вибір у UI, якщо користувач нічого не встиг вибрати іншого
                                poweroffInfoViewModel.selectedSavedAddress = deletedAddressBackup

                                // Знову завантажуємо дані для цієї адреси
                                deletedAddressBackup?.let { address ->
                                    poweroffInfoViewModel.performFetch(address.branchId, address.houseId)
                                }
                            }
                        }
                    } else {
                        // --- ЛОГІКА ДОДАВАННЯ (Ваша існуюча) ---
                        val newAddress = SavedAddress(
                            branchId = poweroffInfoViewModel.selectedBranchId,
                            houseId = poweroffInfoViewModel.selectedHouse!!.first,
                            cityName = poweroffInfoViewModel.selectedCity!!.second,
                            streetName = poweroffInfoViewModel.selectedStreet!!.second,
                            houseName = poweroffInfoViewModel.selectedHouse!!.second
                        )
                        val jsonAddress = gson.toJson(newAddress)

                        if (!savedAddressesRaw.contains(jsonAddress)) {
                            val updatedSet = savedAddressesRaw + jsonAddress
                            scope.launch {
                                settingsManager.saveSavedAddresses(updatedSet)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = savedMessage,
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                        }
                    }
                },
                enabled = canAction,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                shape = RoundedCornerShape(6.dp),
                colors = if (isFromSaved)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                else
                    ButtonDefaults.buttonColors()
            ) {
                Icon(
                    if (isFromSaved) Icons.Default.Delete else Icons.Default.Add,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isFromSaved) stringResource(R.string.remove_address) else stringResource(R.string.add_address))
            }

            val isAddressSelected = poweroffInfoViewModel.selectedHouse != null || poweroffInfoViewModel.selectedSavedAddress != null

            val displayAddress = when {
                // Якщо вибрано зі збережених
                poweroffInfoViewModel.selectedSavedAddress != null -> {
                    "${poweroffInfoViewModel.selectedSavedAddress?.displayString}"
                }
                // Якщо вибрано вручну через пошук
                poweroffInfoViewModel.selectedHouse != null -> {
                    "${poweroffInfoViewModel.selectedCity!!.second}, ${poweroffInfoViewModel.selectedStreet!!.second}, ${poweroffInfoViewModel.selectedHouse!!.second}"
                }
                else -> ""
            }

            // Формуємо фінальний заголовок
            val titleText = if (displayAddress.isNotEmpty()) {
                stringResource(R.string.poweroff_info_title_with_address, displayAddress)
            } else {
                stringResource(R.string.poweroff_info_title)
            }

            // ВИВІД РЕЗУЛЬТАТУ
            // --- ВИВІД РЕЗУЛЬТАТУ ---
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .animateContentSize(), // Додаємо анімацію зміни розміру картки
                shape = RoundedCornerShape(6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Заголовок показуємо завжди, якщо вибрана адреса
                    if (displayAddress.isNotEmpty()) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = 8.dp),
                            textAlign = TextAlign.Center
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp), // Використовуємо heightIn для встановлення мінімальної висоти
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            // 1. СТАН ЗАВАНТАЖЕННЯ
                            poweroffInfoViewModel.isSearching -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(40.dp),
                                        strokeWidth = 3.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.searching), // "Пошук..."
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            // 2. Є ДАНІ (СПИСОК)
                            poweroffInfoViewModel.poweroffList.isNotEmpty() -> {
                                Column {
                                    poweroffInfoViewModel.poweroffList.forEach { item ->
                                        val uiSettings = when (item.type) {
                                            "AVARIYA" -> PoweroffTypeUI(
                                                cardColor = MaterialTheme.colorScheme.errorContainer,
                                                contentColor = MaterialTheme.colorScheme.error,
                                                labelText = stringResource(R.string.poweroff_type_emergency),
                                                icon = Icons.Default.Warning
                                            )
                                            "GAO" -> PoweroffTypeUI(
                                                cardColor = MaterialTheme.colorScheme.tertiaryContainer,
                                                contentColor = MaterialTheme.colorScheme.tertiary,
                                                labelText = stringResource(R.string.poweroff_type_gao),
                                                icon = Icons.Default.Info
                                            )
                                            else -> PoweroffTypeUI(
                                                cardColor = MaterialTheme.colorScheme.surfaceVariant,
                                                contentColor = MaterialTheme.colorScheme.primary,
                                                labelText = stringResource(R.string.poweroff_type_plan),
                                                icon = Icons.Default.DateRange
                                            )
                                        }

                                        PoweroffItemCard(item, uiSettings) // Винесено в окремий Composable нижче
                                    }
                                }
                            }

                            // 3. АДРЕСА ВИБРАНА, АЛЕ ВІДКЛЮЧЕНЬ НЕМАЄ
                            isAddressSelected -> {
                                Text(
                                    text = stringResource(R.string.no_poweroff),
                                    modifier = Modifier.padding(16.dp),
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            // 4. НІЧОГО НЕ ВИБРАНО
                            else -> {
                                Text(
                                    text = stringResource(R.string.choose_poweroff_address),
                                    modifier = Modifier.padding(16.dp),
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

fun formatApiDate(dateStr: String): String {
    return try {
        // API повертає "2026-04-23T05:00:00.000000Z"
        val instant = Instant.parse(dateStr)
        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault())
        formatter.format(instant)
    } catch (_: Exception) {
        dateStr.take(16).replace("T", " ")
    }
}

data class PoweroffTypeUI(
    val cardColor: Color,
    val contentColor: Color,
    val labelText: String,
    val icon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    label: String,
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit
) {
    val showDialog = remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    )

    OutlinedButton(
        onClick = { showDialog.value = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp)
    ) {
        Icon(Icons.Default.DateRange, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("$label: ${selectedDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}")
    }

    if (showDialog.value) {
        DatePickerDialog(
            onDismissRequest = { showDialog.value = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        onDateSelected(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate())
                    }
                    showDialog.value = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog.value = false }) { Text(stringResource(R.string.settings_cancel)) }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
fun PoweroffItemCard(item: PoweroffItem, uiSettings: PoweroffTypeUI) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = uiSettings.cardColor)
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = uiSettings.icon,
                    contentDescription = null,
                    tint = uiSettings.contentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = uiSettings.labelText,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.labelMedium,
                    color = uiSettings.contentColor
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${formatApiDate(item.start_at)} — ${formatApiDate(item.finish_at)}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            if (item.reason.isNotBlank()) {
                Text(
                    text = item.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
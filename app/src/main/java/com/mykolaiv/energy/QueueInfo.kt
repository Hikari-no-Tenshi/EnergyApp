package com.mykolaiv.energy

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.joinToString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableSelect(
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
fun QueueInfoScreen(
    navController: NavController,
    @Suppress("unused") snackbarHostState: SnackbarHostState,
    onBackAction: () -> Unit
) {
    //val context = LocalContext.current
    val api = remember { ApiService.create() }
    val scope = rememberCoroutineScope()

    // Стан вибору
    var selectedFilial by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var selectedCity by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var selectedStreet by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var selectedDom by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var queueResult by remember { mutableStateOf("") }

    // Списки даних
    var filials by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }
    var cities by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }
    var streets by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }
    var houses by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }

    // Завантаження дільниць при старті
    LaunchedEffect(Unit) {
        val res = withContext(Dispatchers.IO) { api.getFilials() }
        filials = res.map { it.idfilial to it.fullname }
    }

    @Composable
    fun PdfLinkButton(label: String, url: String, pdfTitle: String, navController: NavController) {
        OutlinedButton(
            onClick = {
                // Кодуємо URL для безпечної передачі в навігації
                val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
                val encodedTitle = Uri.encode(pdfTitle)
                navController.navigate("pdf_viewer/$encodedUrl/$encodedTitle")
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(6.dp)
        ) {
            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(label)
        }
    }

    BackHandler {
        onBackAction()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.queues_info_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackAction) { // Повернення назад
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад"/*, tint = Color.White*/)
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

            Text(
                stringResource(R.string.queues_search_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp).align(Alignment.CenterHorizontally)
            )

            // 1. Дільниця
            SearchableSelect(stringResource(R.string.filia_title), selectedFilial?.second ?: "", filials, { id, name ->
                selectedFilial = id to name
                queueResult = ""
                selectedCity = null; cities = emptyList() // Скидання
                scope.launch { cities = api.getCities(id).map { it.idnaspunkt to it.naznaspunkt } }
            })

            // 2. Населений пункт
            SearchableSelect(stringResource(R.string.city_title), selectedCity?.second ?: "", cities, { id, name ->
                selectedCity = id to name
                selectedStreet = null; streets = emptyList()
                scope.launch { streets = api.getStreets(id).map { it.idstreet to it.nazstreet } }
            }, enabled = cities.isNotEmpty())

            // 3. Вулиця
            SearchableSelect(stringResource(R.string.street_title), selectedStreet?.second ?: "", streets, { id, name ->
                selectedStreet = id to name
                selectedDom = null; houses = emptyList()
                scope.launch { houses = api.getHouses(id).map { it.iddom to it.nazdom } }
            }, enabled = streets.isNotEmpty())

            // 4. Будинок
            SearchableSelect(stringResource(R.string.house_title), selectedDom?.second ?: "", houses, { id, name ->
                selectedDom = id to name
                scope.launch {
                    val res = api.getQueueByDom(id)
                    queueResult =
                        res.joinToString("\n") { "${it.outageType.name}: черга ${it.outage.name}" }
                }
            }, enabled = houses.isNotEmpty())

            // ВИВІД РЕЗУЛЬТАТУ
            Card(
                modifier = Modifier.fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(6.dp)
            ) {
                if (queueResult.isNotEmpty()) {
                    Column(modifier = Modifier.padding(4.dp)) {
                        Text(stringResource(R.string.queues_info_title), fontWeight = FontWeight.Bold)
                        Text(queueResult, fontSize = 18.sp)
                    }
                } else {
                    Text(
                        text = stringResource(R.string.choose_address),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Кнопка для ГПВ
            PdfLinkButton(
                label = stringResource(R.string.gpv_url_label),
                url = "https://off.energy.mk.ua/Chergi_GPV.pdf",
                pdfTitle = stringResource(R.string.gpv_url_label),
                navController = navController
            )

            // Кнопка для ГАВ
            PdfLinkButton(
                label = stringResource(R.string.gav_url_label),
                url = "https://off.energy.mk.ua/Chergi_GAV.pdf",
                pdfTitle = stringResource(R.string.gav_url_label),
                navController = navController
            )
        }
    }
}
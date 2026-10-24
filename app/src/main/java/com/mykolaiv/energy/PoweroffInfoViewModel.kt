package com.mykolaiv.energy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

class PoweroffinfoViewModel(
    private val apiPoweroff: ApiPoweroffService
) : ViewModel() {
    private var fetchJob: Job? = null

    // Стан вибору
    var selectedFilial by mutableStateOf<Pair<Int, String>?>(null)
    var selectedCity by mutableStateOf<Pair<Int, String>?>(null)
    var selectedStreet by mutableStateOf<Pair<Int, String>?>(null)
    var selectedHouse by mutableStateOf<Pair<Int, String>?>(null)
    var selectedBranchId by mutableIntStateOf(0)
    var selectedSavedAddress by mutableStateOf<SavedAddress?>(null)

    var startDate: LocalDate by mutableStateOf(LocalDate.now())
    var endDate: LocalDate by mutableStateOf(LocalDate.now())

    // Списки даних
    var filials by mutableStateOf(emptyList<Pair<Int, String>>())
    var cities by  mutableStateOf(emptyList<Pair<Int, String>>())
    var streets by mutableStateOf(emptyList<Pair<Int, String>>())
    var houses by mutableStateOf(emptyList<Pair<Int, String>>())

    var isManualSearchExpanded by mutableStateOf(false)

    var poweroffList by mutableStateOf<List<PoweroffItem>>(emptyList())
    var isSearching by mutableStateOf(false)

    // Функція запиту даних
    fun performFetch(branchId: Int, houseId: Int) {
        fetchJob?.cancel()

        // Розраховуємо межі часу
        val filterStart = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val filterEnd = endDate.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant()

        val request = PoweroffRequest(
            from = filterStart.epochSecond,
            to = filterEnd.epochSecond,
            branch = branchId,
            house_id = houseId
        )

        fetchJob = viewModelScope.launch {
            try {
                isSearching = true
                val response = withContext(Dispatchers.IO) { apiPoweroff.getPoweroffInfo(request) }

                // Фільтрація та сортування
                poweroffList = response.data.filter { item ->
                    try {
                        val itemStart = Instant.parse(item.start_at)
                        // Важливо: переконайтеся, що API повертає end_at.
                        // Якщо ні, використовуйте поле, де вказано час завершення.
                        val itemEnd = Instant.parse(item.finish_at)

                        // ЛОГІКА ПЕРЕТИНУ:
                        // Подія почалася до кінця фільтру ТА закінчилася після початку фільтру
                        val startsBeforeFilterEnds = !itemStart.isAfter(filterEnd)
                        val endsAfterFilterStarts = !itemEnd.isBefore(filterStart)

                        startsBeforeFilterEnds && endsAfterFilterStarts
                    } catch (_: Exception) {
                        true
                    }
                }.sortedBy { it.start_at }

            } catch (_: CancellationException) {
                // Ігноруємо скасування
            } catch (e: Exception) {
                e.printStackTrace()
                poweroffList = emptyList()
            } finally {
                // Вимикаємо пошук тільки якщо корутина завершилася сама, а не була вбита новим запитом
                if (isActive) {
                    isSearching = false
                }
            }
        }
    }

    // У PoweroffinfoViewModel
    fun refreshDatesIfNeeded() {
        val today = LocalDate.now()
        // Якщо збережена дата старту менша за сьогоднішню — оновлюємо обидві дати
        if (startDate.isBefore(today)) {
            startDate = today
            endDate = today
        }
    }

    companion object {
        fun provideFactory(apiPoweroff: ApiPoweroffService): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PoweroffinfoViewModel(apiPoweroff) as T
            }
        }
    }
}
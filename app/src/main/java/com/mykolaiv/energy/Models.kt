package com.mykolaiv.energy

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.Serializable

data class Queue(val id: Int, val name: String)
data class TimeSeries(val id: Int, val start: String, val end: String)
data class ActiveOutage(val id: Int, val from: String, val to: String?, val series: List<OutageItem>)
data class EmergencyQueue(val id: Int, val name: String, val enable: Int)
data class EmergencySpecialQueue(val id: Int, val name: String, val enable: Int)
data class FilialResponse(val idfilial: Int, val fullname: String)
data class CityResponse(val idnaspunkt: Int, val naznaspunkt: String)
data class StreetResponse(val idstreet: Int, val nazstreet: String)
data class HouseResponse(val iddom: Int, val nazdom: String)
data class QueueByDomResponse(val outage: OutageDetails, val outageType: OutageTypeDetails)
data class OutageDetails(val id: Int, val name: String, val enabled: Int)
data class OutageTypeDetails(val id: Int, val name: String)
data class PoweroffCityResponse(val id: Int, val name: String)
data class PoweroffStreetResponse(val id: Int, val name: String?)
data class PoweroffHouseResponse(val id: Int, val name: String?)
data class OutageItem(
    @SerializedName("outage_queue_id")
    val outageQueueId: Int,
    @SerializedName("time_series_id")
    val timeSeriesId: Int,
    val type: String,
    val dateTo: String? = null
)

@Suppress("PropertyName")
data class PoweroffRequest(
    val page: Int = 1,
    val perPage: Int = 100,
    val from: Long,
    val to: Long,
    val branch: Int,
    val house_id: Int
)

data class PoweroffInfoResponse(
    val data: List<PoweroffItem>,
    val total: Int,
    @SerializedName("last_page") val lastPage: Int
)

@Suppress("PropertyName")
data class PoweroffItem(
    val id: Int,
    val type: String,
    val start_at: String,
    val finish_at: String,
    val reason: String
)

@Serializable
data class SavedAddress(
    val branchId: Int,
    val houseId: Int,
    val cityName: String,
    val streetName: String,
    val houseName: String
) {
    val displayString get() = "$cityName, $streetName, $houseName"
}
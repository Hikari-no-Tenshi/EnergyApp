package com.mykolaiv.energy

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

interface ApiService {
    @GET("api/outage-queue/by-type/3")
    suspend fun getQueues(): List<Queue>

    @GET("api/schedule/time-series")
    suspend fun getTimeSeries(): List<TimeSeries>

    @GET("api/v2/schedule/active")
    suspend fun getActiveOutages(): List<ActiveOutage>

    @GET("api/outage-queue/by-type/1")
    suspend fun getEmergencyQueues(): List<EmergencyQueue>

    @GET("api/outage-queue/by-type/2")
    suspend fun getEmergencySpecialQueues(): List<EmergencySpecialQueue>

    @GET("api/addr/filii")
    suspend fun getFilials(): List<FilialResponse>

    @GET("api/addr/filii/{id}/ns")
    suspend fun getCities(@Path("id") id: Int): List<CityResponse>

    @GET("api/addr/ns/{id}/street")
    suspend fun getStreets(@Path("id") id: Int): List<StreetResponse>

    @GET("api/addr/street/{id}/dom")
    suspend fun getHouses(@Path("id") id: Int): List<HouseResponse>

    @GET("api/addr/dom/{id}/outage-queue")
    suspend fun getQueueByDom(@Path("id") id: Int): List<QueueByDomResponse>

    companion object {
        private const val BASE_URL = "https://off.energy.mk.ua/"
        fun create(): ApiService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }
}
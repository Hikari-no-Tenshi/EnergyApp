package com.mykolaiv.energy

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiPoweroffService {
    @GET("outage/api/v1/address/branch/{id}/city")
    suspend fun getPoweroffCities(@Path("id") id: Int): List<PoweroffCityResponse>

    @GET("outage/api/v1/address/city/{id}/street")
    suspend fun getPoweroffStreets(@Path("id") id: Int): Map<String, PoweroffStreetResponse>

    @GET("outage/api/v1/address/street/{id}/house")
    suspend fun getPoweroffHouses(@Path("id") id: Int): Map<String, PoweroffHouseResponse>

    @POST("outage/api/v1/outage")
    suspend fun getPoweroffInfo(@Body request: PoweroffRequest): PoweroffInfoResponse

    companion object {
        private const val BASE_URL = "https://www.energy.mk.ua/"
        fun create(): ApiPoweroffService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiPoweroffService::class.java)
        }
    }
}
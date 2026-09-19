package com.example.nfcwritter.data

import com.google.gson.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface SellerApi {
    @POST("api/seller/login/")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @GET("api/seller/dashboard/")
    suspend fun dashboard(): Response<JsonElement>

    @GET("api/seller/orders/")
    suspend fun orders(): Response<JsonElement>

    @GET("api/seller/orders/{id}/")
    suspend fun order(@Path("id") id: Long): Response<JsonElement>

    @GET("api/seller/cards/available/")
    suspend fun availableCards(): Response<JsonElement>

    @GET("api/seller/cards/{id}/")
    suspend fun card(@Path("id") id: Long): Response<JsonElement>

    @POST("api/seller/orders/{id}/assign-card/")
    suspend fun assignCard(@Path("id") id: Long, @Body request: AssignCardRequest): Response<JsonElement>

    @POST("api/seller/cards/{id}/programmed/")
    suspend fun markProgrammed(@Path("id") id: Long): Response<JsonElement>

    @POST("api/seller/cards/{id}/activate/")
    suspend fun activate(@Path("id") id: Long): Response<JsonElement>
}

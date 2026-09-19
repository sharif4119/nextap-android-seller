package com.example.nfcwritter.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SellerRepository(private val api: SellerApi, private val tokenStore: TokenStore) {
    companion object {
        const val BASE_URL = "https://nfc-digital-card.onrender.com/"

        fun create(tokenStore: TokenStore): SellerRepository {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val token = tokenStore.cachedToken
                val request = if (token.isNullOrBlank()) chain.request() else chain.request().newBuilder()
                    .header("Authorization", "Token $token").build()
                chain.proceed(request)
            }.build()
            val api = Retrofit.Builder().baseUrl(BASE_URL).client(client)
                .addConverterFactory(GsonConverterFactory.create()).build().create(SellerApi::class.java)
            return SellerRepository(api, tokenStore)
        }
    }

    suspend fun login(username: String, password: String): ApiResult<String> = safe {
        val response = api.login(LoginRequest(username, password))
        if (response.isSuccessful) {
            val body = response.body() ?: error("Empty login response")
            tokenStore.save(body.token, body.username)
            body.username
        } else throw ApiException(response.code(), errorMessage(response))
    }

    suspend fun dashboard() = request(api::dashboard, JsonMappers::dashboard)
    suspend fun orders() = request(api::orders, JsonMappers::orders)
    suspend fun order(id: Long) = request({ api.order(id) }, JsonMappers::order)
    suspend fun availableCards() = request(api::availableCards, JsonMappers::cards)
    suspend fun card(id: Long) = request({ api.card(id) }, JsonMappers::card)
    suspend fun assignCard(orderId: Long, cardId: Long): ApiResult<SellerOrder> {
        return when (val assigned = request({ api.assignCard(orderId, AssignCardRequest(cardId)) }) { true }) {
            is ApiResult.Success -> order(orderId)
            is ApiResult.Error -> assigned
        }
    }
    suspend fun markProgrammed(cardId: Long) = request({ api.markProgrammed(cardId) }) { true }
    suspend fun activate(cardId: Long) = request({ api.activate(cardId) }) { true }
    suspend fun logout() = tokenStore.clear()

    private suspend fun <T> request(call: suspend () -> Response<JsonElement>, map: (JsonElement) -> T): ApiResult<T> = safe {
        val response = call()
        if (!response.isSuccessful) throw ApiException(response.code(), errorMessage(response))
        map(response.body() ?: JsonObject())
    }

    private suspend fun <T> safe(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (e: ApiException) {
        ApiResult.Error(e.message ?: "Request failed", e.code == 401 || e.code == 403)
    } catch (e: Exception) {
        ApiResult.Error(e.message ?: "Unable to reach the server")
    }

    private fun errorMessage(response: Response<*>): String {
        val raw = runCatching { response.errorBody()?.string() }.getOrNull()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val o = Gson().fromJson(raw, JsonObject::class.java)
                listOf("detail", "error", "message", "non_field_errors").firstNotNullOfOrNull { key ->
                    o.get(key)?.let { if (it.isJsonArray) it.asJsonArray.joinToString { v -> v.asString } else it.asString }
                }
            }.getOrNull()?.let { return it }
        }
        return "Server returned ${response.code()}"
    }

    private class ApiException(val code: Int, message: String) : Exception(message)
}

package com.example.nfcwritter.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.authDataStore by preferencesDataStore(name = "seller_auth")

class TokenStore(private val context: Context) {
    private val tokenKey = stringPreferencesKey("auth_token")
    private val usernameKey = stringPreferencesKey("username")
    private val serverKey = stringPreferencesKey("auth_server")

    @Volatile var cachedToken: String? = null
        private set

    suspend fun load(): String? {
        val preferences = context.authDataStore.data.first()
        val storedToken = preferences[tokenKey]

        if (storedToken != null && preferences[serverKey] != SellerRepository.BASE_URL) {
            context.authDataStore.edit {
                it.remove(tokenKey)
                it.remove(usernameKey)
                it[serverKey] = SellerRepository.BASE_URL
            }
            cachedToken = null
            return null
        }

        if (preferences[serverKey] != SellerRepository.BASE_URL) {
            context.authDataStore.edit { it[serverKey] = SellerRepository.BASE_URL }
        }

        cachedToken = storedToken
        return cachedToken
    }

    suspend fun save(token: String, username: String) {
        context.authDataStore.edit {
            it[tokenKey] = token
            it[usernameKey] = username
            it[serverKey] = SellerRepository.BASE_URL
        }
        cachedToken = token
    }

    suspend fun clear() {
        context.authDataStore.edit {
            it.remove(tokenKey)
            it.remove(usernameKey)
            it[serverKey] = SellerRepository.BASE_URL
        }
        cachedToken = null
    }
}

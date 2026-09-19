package com.example.nfcwritter.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.authDataStore by preferencesDataStore(name = "seller_auth")

class TokenStore(private val context: Context) {
    private val tokenKey = stringPreferencesKey("auth_token")
    private val usernameKey = stringPreferencesKey("username")

    @Volatile var cachedToken: String? = null
        private set

    suspend fun load(): String? {
        cachedToken = context.authDataStore.data.map { it[tokenKey] }.first()
        return cachedToken
    }

    suspend fun save(token: String, username: String) {
        context.authDataStore.edit {
            it[tokenKey] = token
            it[usernameKey] = username
        }
        cachedToken = token
    }

    suspend fun clear() {
        context.authDataStore.edit { it.clear() }
        cachedToken = null
    }
}

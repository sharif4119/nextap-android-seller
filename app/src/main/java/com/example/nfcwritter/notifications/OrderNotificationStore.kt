package com.example.nfcwritter.notifications

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.orderNotificationDataStore by preferencesDataStore(name = "seller_order_notifications")

class OrderNotificationStore(private val context: Context) {
    suspend fun detect(scope: String, currentOrderIds: Set<Long>): OrderDetection {
        val state = read(scope)
        val detection = OrderObservationPolicy.detect(state, currentOrderIds)
        if (detection.establishedBaseline) write(scope, detection.state)
        return detection
    }

    suspend fun markNotified(scope: String, orderIds: Set<Long>) {
        if (orderIds.isEmpty()) return
        context.orderNotificationDataStore.edit { preferences ->
            val initializedKey = initializedKey(scope)
            val observedKey = observedKey(scope)
            val observed = preferences[observedKey].orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
            val updated = OrderObservationPolicy.markNotified(
                OrderObservationState(preferences[initializedKey] == true, observed),
                orderIds,
            )
            preferences[initializedKey] = updated.baselineEstablished
            preferences[observedKey] = updated.observedOrderIds.map(Long::toString).toSet()
        }
    }

    private suspend fun read(scope: String): OrderObservationState {
        val preferences = context.orderNotificationDataStore.data.first()
        return OrderObservationState(
            baselineEstablished = preferences[initializedKey(scope)] == true,
            observedOrderIds = preferences[observedKey(scope)].orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
        )
    }

    private suspend fun write(scope: String, state: OrderObservationState) {
        context.orderNotificationDataStore.edit { preferences ->
            preferences[initializedKey(scope)] = state.baselineEstablished
            preferences[observedKey(scope)] = state.observedOrderIds.map(Long::toString).toSet()
        }
    }

    private fun initializedKey(scope: String) = booleanPreferencesKey("baseline_$scope")
    private fun observedKey(scope: String) = stringSetPreferencesKey("observed_$scope")
}

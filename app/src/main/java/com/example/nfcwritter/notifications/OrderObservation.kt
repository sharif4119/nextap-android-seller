package com.example.nfcwritter.notifications

import java.security.MessageDigest

data class OrderObservationState(
    val baselineEstablished: Boolean = false,
    val observedOrderIds: Set<Long> = emptySet(),
)

data class OrderDetection(
    val state: OrderObservationState,
    val newOrderIds: Set<Long> = emptySet(),
    val establishedBaseline: Boolean = false,
)

object OrderObservationPolicy {
    fun detect(state: OrderObservationState, currentOrderIds: Set<Long>): OrderDetection {
        if (!state.baselineEstablished) {
            return OrderDetection(
                state = OrderObservationState(true, currentOrderIds),
                establishedBaseline = true,
            )
        }
        return OrderDetection(state, currentOrderIds - state.observedOrderIds)
    }

    fun markNotified(state: OrderObservationState, notifiedOrderIds: Set<Long>): OrderObservationState =
        state.copy(observedOrderIds = state.observedOrderIds + notifiedOrderIds)

    fun onLogout(state: OrderObservationState): OrderObservationState = state
}

object OrderNotificationScope {
    fun key(baseUrl: String, username: String): String {
        val input = "${baseUrl.trim().lowercase()}|${username.trim().lowercase()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }
    }
}

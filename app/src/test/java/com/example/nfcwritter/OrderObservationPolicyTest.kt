package com.example.nfcwritter

import com.example.nfcwritter.notifications.OrderNotificationScope
import com.example.nfcwritter.notifications.OrderObservationPolicy
import com.example.nfcwritter.notifications.OrderObservationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderObservationPolicyTest {
    @Test
    fun firstLogin_establishesBaselineWithoutNewOrders() {
        val result = OrderObservationPolicy.detect(OrderObservationState(), setOf(10, 11, 12))

        assertTrue(result.establishedBaseline)
        assertTrue(result.newOrderIds.isEmpty())
        assertEquals(setOf(10L, 11L, 12L), result.state.observedOrderIds)
    }

    @Test
    fun laterCheck_detectsOnlyNewOrderIds() {
        val baseline = OrderObservationState(true, setOf(10, 11))

        val result = OrderObservationPolicy.detect(baseline, setOf(10, 11, 12))

        assertEquals(setOf(12L), result.newOrderIds)
        assertFalse(result.establishedBaseline)
    }

    @Test
    fun notifiedOrder_isNotDetectedAgain() {
        val baseline = OrderObservationState(true, setOf(10))
        val detected = OrderObservationPolicy.detect(baseline, setOf(10, 11))
        val persisted = OrderObservationPolicy.markNotified(detected.state, detected.newOrderIds)

        val repeated = OrderObservationPolicy.detect(persisted, setOf(10, 11))

        assertTrue(repeated.newOrderIds.isEmpty())
    }

    @Test
    fun multipleOrders_areReturnedTogether() {
        val result = OrderObservationPolicy.detect(
            OrderObservationState(true, setOf(20)),
            setOf(20, 21, 22, 23),
        )

        assertEquals(setOf(21L, 22L, 23L), result.newOrderIds)
    }

    @Test
    fun persistedState_afterAppRestartPreventsDuplicates() {
        val beforeRestart = OrderObservationPolicy.markNotified(
            OrderObservationState(true, setOf(30)),
            setOf(31),
        )
        val restoredState = beforeRestart.copy()

        val result = OrderObservationPolicy.detect(restoredState, setOf(30, 31))

        assertTrue(result.newOrderIds.isEmpty())
    }

    @Test
    fun logout_preservesHistoryAndOrdersArrivingWhileLoggedOutRemainNew() {
        val beforeLogout = OrderObservationState(true, setOf(40))
        val afterLogout = OrderObservationPolicy.onLogout(beforeLogout)

        val afterLogin = OrderObservationPolicy.detect(afterLogout, setOf(40, 41))

        assertEquals(beforeLogout, afterLogout)
        assertEquals(setOf(41L), afterLogin.newOrderIds)
    }

    @Test
    fun scope_isDifferentForSellerAndBackend() {
        val base = "https://nextap.pythonanywhere.com/"
        assertNotEquals(
            OrderNotificationScope.key(base, "seller-one"),
            OrderNotificationScope.key(base, "seller-two"),
        )
        assertNotEquals(
            OrderNotificationScope.key(base, "seller-one"),
            OrderNotificationScope.key("https://staging.example.com/", "seller-one"),
        )
    }
}

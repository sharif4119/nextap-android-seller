package com.example.nfcwritter

import com.example.nfcwritter.data.JsonMappers
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonMappersTest {
    @Test
    fun dashboard_acceptsCountFieldNames() {
        val json = JsonParser.parseString(
            """{"pending_orders_count":2,"available_cards":3,"assigned":4,"programmed_cards":5,"active_cards_count":6}"""
        )

        assertEquals(2, JsonMappers.dashboard(json).pendingOrders)
        assertEquals(3, JsonMappers.dashboard(json).availableCards)
        assertEquals(4, JsonMappers.dashboard(json).assignedCards)
        assertEquals(5, JsonMappers.dashboard(json).programmedCards)
        assertEquals(6, JsonMappers.dashboard(json).activeCards)
    }

    @Test
    fun order_mapsNestedCustomerAndAssignedCard() {
        val json = JsonParser.parseString(
            """{"id":12,"customer":{"full_name":"Asha","phone_number":"01700"},"product_name":"PVC Card","status":"pending","assigned_card":{"id":9,"uid":"ABC","status":"assigned","public_url":"https://example.com/c/9"}}"""
        )

        val order = JsonMappers.order(json)
        assertEquals("Asha", order.customer)
        assertEquals("01700", order.phone)
        assertEquals(9L, order.assignedCard?.id)
        assertEquals("https://example.com/c/9", order.assignedCard?.publicUrl)
    }

    @Test
    fun card_doesNotInventMissingPublicUrl() {
        val card = JsonMappers.card(JsonParser.parseString("""{"id":7,"uid":"XYZ","status":"available"}"""))
        assertNull(card.publicUrl)
    }
}

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

    @Test
    fun prepareCardResponse_mapsReturnedOrderAndGeneratedCard() {
        val json = JsonParser.parseString(
            """{"data":{"order":{"id":44,"customer_name":"Nadia","city":"Dhaka","product":{"name":"Metal Card","price":"1499.00"},"payment_status":"paid","order_status":"processing","assigned_card":{"card_id":81,"card_uid":"NX-0044","status":"ASSIGNED","public_url":"https://example.com/n/nx-0044"}}}}"""
        )

        val order = JsonMappers.order(json)
        assertEquals("Dhaka", order.city)
        assertEquals("Metal Card", order.product)
        assertEquals("1499.00", order.price)
        assertEquals(81L, order.assignedCard?.id)
        assertEquals("NX-0044", order.assignedCard?.uid)
        assertEquals(true, order.assignedCard?.canWrite)
    }

    @Test
    fun order_mapsBackendPrintDesignUrlOntoPreparedCard() {
        val json = JsonParser.parseString(
            """{"id":44,"print_design":{"pdf_url":"/api/seller/print-designs/44/pdf/"},"assigned_card":{"id":81,"uid":"NX-0044","status":"ASSIGNED","public_url":"https://example.com/n/nx-0044"}}"""
        )

        assertEquals(
            "/api/seller/print-designs/44/pdf/",
            JsonMappers.order(json).assignedCard?.printDesignUrl,
        )
    }
}

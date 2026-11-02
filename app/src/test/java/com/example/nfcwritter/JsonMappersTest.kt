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

    @Test
    fun personalOrder_mapsCategoryQuantityAndAllocationServerValues() {
        val order = JsonMappers.order(JsonParser.parseString(
            """{"id":51,"customer_name":"Asha","product_name":"Personal Compact","price":"650.00","card_category":"PERSONAL","business_order_type":"","quantity":1,"prepared_count":1,"order_status":"CARD_ASSIGNED","allocations":[{"id":701,"position":1,"representative":null,"card":{"id":901,"card_uid":"NXT-P-901","status":"ASSIGNED","public_url":"https://nextap.pythonanywhere.com/c/personal-token/","print_pdf_url":"https://nextap.pythonanywhere.com/api/seller/cards/901/print/"}}]}"""
        ))

        assertEquals("PERSONAL", order.cardCategory)
        assertEquals(1, order.quantity)
        assertEquals(1, order.preparedCount)
        assertEquals(701L, order.allocations.single().id)
        assertEquals("https://nextap.pythonanywhere.com/c/personal-token/", order.allocations.single().card?.publicUrl)
        assertEquals("https://nextap.pythonanywhere.com/api/seller/cards/901/print/", order.allocations.single().card?.printDesignUrl)
    }

    @Test
    fun businessSingleOrder_mapsRepresentativeAndCardIndependently() {
        val order = JsonMappers.order(JsonParser.parseString(
            """{"id":52,"card_category":"BUSINESS","business_order_type":"SINGLE","quantity":1,"prepared_count":1,"allocations":[{"id":702,"position":1,"representative":{"id":15,"name":"Rahim","designation":"Manager","phone":"01700","email":"rahim@example.test"},"card":{"id":902,"card_uid":"NXT-B-902","status":"PROGRAMMED","public_url":"https://nextap.pythonanywhere.com/c/business-single/","print_pdf_url":"/api/seller/cards/902/print/","card_category":"BUSINESS","representative":{"id":15,"name":"Rahim","designation":"Manager","phone":"01700","email":"rahim@example.test"}}}]}"""
        ))

        assertEquals("SINGLE", order.businessOrderType)
        assertEquals("Rahim", order.allocations.single().representative?.name)
        assertEquals("Manager", order.allocations.single().card?.representative?.designation)
        assertEquals("PROGRAMMED", order.allocations.single().card?.normalizedStatus)
    }

    @Test
    fun businessMultipleOrder_keepsAllocationIdsRepresentativesAndUrlsDistinct() {
        val order = JsonMappers.order(JsonParser.parseString(
            """{"id":53,"card_category":"BUSINESS","business_order_type":"MULTIPLE","quantity":2,"prepared_count":1,"order_status":"CARD_ASSIGNED","assigned_card":{"id":903,"card_uid":"NXT-B-903","status":"ASSIGNED","public_url":"https://nextap.pythonanywhere.com/c/card-one/"},"allocations":[{"id":703,"position":1,"representative":{"id":16,"name":"Rahim","designation":"Manager"},"card":{"id":903,"card_uid":"NXT-B-903","status":"ASSIGNED","public_url":"https://nextap.pythonanywhere.com/c/card-one/","print_pdf_url":"/api/seller/cards/903/print/"}},{"id":704,"position":2,"representative":{"id":17,"name":"Karim","designation":"Director"},"card":null}]}"""
        ))

        assertEquals("MULTIPLE", order.businessOrderType)
        assertEquals(2, order.quantity)
        assertEquals(1, order.preparedCount)
        assertEquals(listOf(703L, 704L), order.allocations.map { it.id })
        assertEquals(listOf("Rahim", "Karim"), order.allocations.map { it.representative?.name })
        assertEquals("https://nextap.pythonanywhere.com/c/card-one/", order.allocations[0].card?.publicUrl)
        assertNull(order.allocations[1].card)
    }
}

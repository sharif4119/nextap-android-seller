package com.example.nfcwritter.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject

object JsonMappers {
    fun dashboard(element: JsonElement): DashboardStats {
        val o = payloadObject(element)
        return DashboardStats(
            pendingOrders = o.int("pending_orders", "pending_orders_count", "pending"),
            availableCards = o.int("available_cards", "available_cards_count", "available"),
            assignedCards = o.int("assigned_cards", "assigned_cards_count", "assigned"),
            programmedCards = o.int("programmed_cards", "programmed_cards_count", "programmed"),
            activeCards = o.int("active_cards", "active_cards_count", "active"),
        )
    }

    fun orders(element: JsonElement): List<SellerOrder> = listPayload(element).mapNotNull {
        runCatching { order(it) }.getOrNull()
    }

    fun order(element: JsonElement): SellerOrder {
        val o = payloadObject(element)
        val customerObject = o.obj("customer")
        val productObject = o.obj("product")
        val assigned = o.obj("assigned_card") ?: o.obj("card") ?: o.obj("nfc_card")
            ?: o.takeIf { it.has("card_uid") && it.has("public_url") }
        val card = assigned?.let(::card)?.let { mapped ->
            if (mapped.printDesignUrl != null) mapped else mapped.copy(printDesignUrl = printDesignUrl(o))
        }
        val allocations = o.get("allocations")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val allocation = item.asJsonObject
            val allocationCard = allocation.obj("card")?.let(::card)
            CardAllocation(
                id = allocation.long("id"),
                position = allocation.int("position").takeIf { it > 0 } ?: 1,
                representative = allocation.obj("representative")?.let(::representative),
                card = allocationCard,
            )
        }.orEmpty()
        val quantity = o.int("quantity").takeIf { it > 0 } ?: 1
        val preparedCount = if (o.has("prepared_count") && !o.get("prepared_count").isJsonNull) {
            o.int("prepared_count")
        } else {
            allocations.count { it.card != null }.takeIf { allocations.isNotEmpty() }
                ?: if (card != null) 1 else 0
        }
        return SellerOrder(
            id = o.long("id", "order_id"),
            customer = o.text("customer_name", "name")
                ?: customerObject?.text("name", "full_name", "username") ?: "—",
            phone = o.text("phone", "phone_number") ?: customerObject?.text("phone", "phone_number") ?: "—",
            address = o.text("address", "shipping_address") ?: customerObject?.text("address") ?: "—",
            city = o.text("city") ?: customerObject?.text("city") ?: "—",
            product = o.text("product_name", "card_type") ?: productObject?.text("name", "title") ?: "—",
            price = o.text("price", "total", "total_price") ?: productObject?.text("price") ?: "—",
            paymentStatus = o.text("payment_status", "payment") ?: "—",
            orderStatus = o.text("order_status", "status") ?: "—",
            assignedCard = card,
            cardCategory = o.text("card_category")?.uppercase().orEmpty(),
            businessOrderType = o.text("business_order_type")?.uppercase().orEmpty(),
            quantity = quantity,
            preparedCount = preparedCount,
            allocations = allocations,
        )
    }

    fun cards(element: JsonElement): List<NfcCard> = listPayload(element).mapNotNull {
        runCatching { card(it) }.getOrNull()
    }

    fun card(element: JsonElement): NfcCard = card(payloadObject(element))

    private fun card(o: JsonObject) = NfcCard(
        id = o.long("card_id", "id"),
        uid = o.text("card_uid", "uid", "serial_number", "code") ?: "—",
        status = o.text("status", "card_status") ?: "—",
        publicUrl = o.text("public_url", "url"),
        printDesignUrl = printDesignUrl(o),
        cardCategory = o.text("card_category")?.uppercase().orEmpty(),
        representative = o.obj("representative")?.let(::representative),
        ownerUsername = o.text("owner_username"),
    )

    private fun representative(o: JsonObject) = CardRepresentative(
        id = o.long("id"),
        name = o.text("name") ?: "—",
        designation = o.text("designation").orEmpty(),
        phone = o.text("phone").orEmpty(),
        email = o.text("email").orEmpty(),
    )

    private fun printDesignUrl(o: JsonObject): String? =
        o.text("print_design_url", "print_pdf_url", "design_pdf_url", "card_design_url", "artwork_pdf_url")
            ?: o.obj("print_design")?.text("pdf_url", "download_url", "url")
            ?: o.obj("card_design")?.text("pdf_url", "download_url", "url")

    private fun payloadObject(element: JsonElement): JsonObject {
        var current = element.asJsonObject
        repeat(3) {
            if (current.has("id") || current.has("order_id") || current.has("card_id")) return current
            val nested = listOf("data", "order", "card", "dashboard").firstNotNullOfOrNull { key ->
                current.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
            } ?: return current
            current = nested
        }
        return current
    }

    private fun listPayload(element: JsonElement): List<JsonObject> {
        val array = if (element.isJsonArray) element.asJsonArray else {
            val root = element.asJsonObject
            listOf("results", "data", "orders", "cards").firstNotNullOfOrNull { key ->
                root.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            } ?: return emptyList()
        }
        return array.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
    }

    private fun JsonObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        get(key)?.takeUnless { it.isJsonNull || it.isJsonObject || it.isJsonArray }?.asString
    }

    private fun JsonObject.int(vararg keys: String): Int = keys.firstNotNullOfOrNull { key ->
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() }
    } ?: 0

    private fun JsonObject.long(vararg keys: String): Long = keys.firstNotNullOfOrNull { key ->
        get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asLong }.getOrNull() }
    } ?: error("Missing id")

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}

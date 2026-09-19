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
        val assigned = o.obj("assigned_card") ?: o.obj("card")
        return SellerOrder(
            id = o.long("id", "order_id"),
            customer = o.text("customer_name", "name")
                ?: customerObject?.text("name", "full_name", "username") ?: "—",
            phone = o.text("phone", "phone_number") ?: customerObject?.text("phone", "phone_number") ?: "—",
            address = o.text("address", "shipping_address") ?: customerObject?.text("address") ?: "—",
            product = o.text("product_name", "product", "card_type") ?: "—",
            paymentStatus = o.text("payment_status", "payment") ?: "—",
            orderStatus = o.text("order_status", "status") ?: "—",
            assignedCard = assigned?.let(::card),
        )
    }

    fun cards(element: JsonElement): List<NfcCard> = listPayload(element).mapNotNull {
        runCatching { card(it) }.getOrNull()
    }

    fun card(element: JsonElement): NfcCard = card(payloadObject(element))

    private fun card(o: JsonObject) = NfcCard(
        id = o.long("id", "card_id"),
        uid = o.text("uid", "card_uid", "serial_number", "code") ?: "—",
        status = o.text("status", "card_status") ?: "—",
        publicUrl = o.text("public_url", "url"),
    )

    private fun payloadObject(element: JsonElement): JsonObject {
        val root = element.asJsonObject
        return listOf("data", "order", "card", "dashboard").firstNotNullOfOrNull { key ->
            root.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
        } ?: root
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

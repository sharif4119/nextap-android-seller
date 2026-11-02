package com.example.nfcwritter.data

data class LoginRequest(val username: String, val password: String)
data class LoginResponse(val token: String, val username: String)
data class AssignCardRequest(val card_id: Long)

data class DashboardStats(
    val pendingOrders: Int = 0,
    val availableCards: Int = 0,
    val assignedCards: Int = 0,
    val programmedCards: Int = 0,
    val activeCards: Int = 0,
)

data class SellerOrder(
    val id: Long,
    val customer: String = "—",
    val phone: String = "—",
    val address: String = "—",
    val city: String = "—",
    val product: String = "—",
    val price: String = "—",
    val paymentStatus: String = "—",
    val orderStatus: String = "—",
    val assignedCard: NfcCard? = null,
    val cardCategory: String = "",
    val businessOrderType: String = "",
    val quantity: Int = 1,
    val preparedCount: Int = 0,
    val allocations: List<CardAllocation> = emptyList(),
)

data class CardRepresentative(
    val id: Long,
    val name: String = "—",
    val designation: String = "",
    val phone: String = "",
    val email: String = "",
)

data class CardAllocation(
    val id: Long,
    val position: Int = 1,
    val representative: CardRepresentative? = null,
    val card: NfcCard? = null,
)

data class NfcCard(
    val id: Long,
    val uid: String = "—",
    val status: String = "—",
    val publicUrl: String? = null,
    val printDesignUrl: String? = null,
    val cardCategory: String = "",
    val representative: CardRepresentative? = null,
    val ownerUsername: String? = null,
) {
    val normalizedStatus: String get() = status.trim().uppercase()
    val canWrite: Boolean get() = publicUrl?.isNotBlank() == true && normalizedStatus == "ASSIGNED"
}

data class PrintDesignDocument(
    val fileName: String,
    val bytes: ByteArray,
)

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Error(val message: String, val unauthorized: Boolean = false) : ApiResult<Nothing>
}

package com.example.nfcwritter.notifications

import android.content.Context
import com.example.nfcwritter.data.ApiResult
import com.example.nfcwritter.data.SellerRepository
import com.example.nfcwritter.data.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface OrderCheckResult {
    data object NoSession : OrderCheckResult
    data object BaselineEstablished : OrderCheckResult
    data object NoNewOrders : OrderCheckResult
    data object Unauthorized : OrderCheckResult
    data class NotificationsPosted(val count: Int) : OrderCheckResult
    data class PermissionRequired(val pendingCount: Int) : OrderCheckResult
    data class RetryLater(val message: String) : OrderCheckResult
}

class NewOrderChecker(context: Context) {
    private val appContext = context.applicationContext
    private val tokenStore = TokenStore(appContext)
    private val repository = SellerRepository.create(tokenStore)
    private val store = OrderNotificationStore(appContext)
    private val publisher = OrderNotificationPublisher(appContext)

    suspend fun check(): OrderCheckResult = checkMutex.withLock {
        try {
            if (tokenStore.load().isNullOrBlank()) return@withLock OrderCheckResult.NoSession
            val username = tokenStore.cachedUsername
                ?: return@withLock OrderCheckResult.NoSession
            val scope = OrderNotificationScope.key(SellerRepository.BASE_URL, username)
            when (val response = repository.orders()) {
                is ApiResult.Error -> if (response.unauthorized) {
                    OrderCheckResult.Unauthorized
                } else {
                    OrderCheckResult.RetryLater(response.message)
                }
                is ApiResult.Success -> {
                    val orders = response.data.distinctBy { it.id }
                    val detection = store.detect(scope, orders.map { it.id }.toSet())
                    if (detection.establishedBaseline) {
                        OrderCheckResult.BaselineEstablished
                    } else if (detection.newOrderIds.isEmpty()) {
                        OrderCheckResult.NoNewOrders
                    } else {
                        val newOrders = orders.filter { it.id in detection.newOrderIds }
                        if (publisher.publish(newOrders, scope)) {
                            store.markNotified(scope, detection.newOrderIds)
                            OrderCheckResult.NotificationsPosted(newOrders.size)
                        } else {
                            OrderCheckResult.PermissionRequired(newOrders.size)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            OrderCheckResult.RetryLater(error.message ?: "Order check failed")
        }
    }

    companion object {
        private val checkMutex = Mutex()
    }
}

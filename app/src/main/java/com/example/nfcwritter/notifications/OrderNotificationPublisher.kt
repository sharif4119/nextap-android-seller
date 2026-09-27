package com.example.nfcwritter.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.nfcwritter.MainActivity
import com.example.nfcwritter.R
import com.example.nfcwritter.data.SellerOrder

class OrderNotificationPublisher(private val context: Context) {
    fun createChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "New Orders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Notifications when new NexTap seller orders arrive"
            },
        )
    }

    fun canPublish(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission")
    fun publish(orders: List<SellerOrder>, scope: String): Boolean {
        if (orders.isEmpty() || !canPublish()) return false
        createChannel()
        val manager = NotificationManagerCompat.from(context)
        val groupKey = "nextap-new-orders-$scope"
        orders.forEach { order ->
            val text = listOf(order.customer, order.product)
                .filter { it.isNotBlank() && it != "—" }
                .joinToString(" • ")
                .ifBlank { "A new seller order is ready to review." }
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_orders)
                .setContentTitle("New NexTap order #${order.id}")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(orderIntent(order.id))
                .setAutoCancel(true)
                .setGroup(groupKey)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            manager.notify(notificationId(order.id), notification)
        }
        if (orders.size > 1) {
            val newest = orders.first()
            val summary = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_orders)
                .setContentTitle("${orders.size} new NexTap orders")
                .setContentText("Tap to review order #${newest.id}")
                .setContentIntent(orderIntent(newest.id))
                .setAutoCancel(true)
                .setGroup(groupKey)
                .setGroupSummary(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            manager.notify(summaryId(scope), summary)
        }
        return true
    }

    private fun orderIntent(orderId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction("${context.packageName}.OPEN_ORDER.$orderId")
            .putExtra(EXTRA_ORDER_ID, orderId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            notificationId(orderId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(orderId: Long): Int =
        NOTIFICATION_ID_BASE + ((orderId xor (orderId ushr 32)).toInt() and 0x0fffffff)

    private fun summaryId(scope: String): Int = SUMMARY_ID_BASE + (scope.hashCode() and 0x000fffff)

    companion object {
        const val EXTRA_ORDER_ID = "notification_order_id"
        const val CHANNEL_ID = "new_orders"
        private const val NOTIFICATION_ID_BASE = 10_000
        private const val SUMMARY_ID_BASE = 300_000_000
    }
}

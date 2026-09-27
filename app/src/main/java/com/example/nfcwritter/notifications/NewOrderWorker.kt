package com.example.nfcwritter.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.nfcwritter.data.SellerRepository
import com.example.nfcwritter.data.TokenStore
import java.util.concurrent.TimeUnit

class NewOrderWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result = when (NewOrderChecker(applicationContext).check()) {
        is OrderCheckResult.RetryLater -> Result.retry()
        OrderCheckResult.Unauthorized -> {
            TokenStore(applicationContext).clear()
            WorkManager.getInstance(applicationContext).cancelAllWorkByTag(OrderNotificationWork.TAG)
            Result.success()
        }
        else -> Result.success()
    }
}

object OrderNotificationWork {
    const val TAG = "nextap-new-order-check"

    fun schedule(context: Context, username: String) {
        val scope = OrderNotificationScope.key(SellerRepository.BASE_URL, username)
        val request = PeriodicWorkRequestBuilder<NewOrderWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            uniqueName(scope),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }

    internal fun uniqueName(scope: String) = "nextap-new-order-check-$scope"
}

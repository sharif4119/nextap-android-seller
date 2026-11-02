package com.example.nfcwritter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.example.nfcwritter.notifications.NewOrderChecker
import com.example.nfcwritter.notifications.OrderCheckResult
import com.example.nfcwritter.notifications.OrderNotificationPublisher
import com.example.nfcwritter.notifications.OrderNotificationWork
import com.example.nfcwritter.ui.AvailableCardsFragment
import com.example.nfcwritter.ui.CardDetailFragment
import com.example.nfcwritter.ui.DashboardFragment
import com.example.nfcwritter.ui.LoginFragment
import com.example.nfcwritter.ui.NfcWriteFragment
import com.example.nfcwritter.ui.OrderDetailFragment
import com.example.nfcwritter.ui.OrdersFragment
import com.example.nfcwritter.ui.SellerViewModel
import com.example.nfcwritter.ui.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), AppNavigator {
    private val viewModel: SellerViewModel by viewModels()
    private var nfcCallback: ((Tag) -> Unit)? = null
    private var changingBottomSelection = false
    private var activityResumed = false
    private var readerModeEnabled = false
    private val nfcAdapter by lazy { NfcAdapter.getDefaultAdapter(this) }
    private val newOrderChecker by lazy { NewOrderChecker(applicationContext) }
    private var foregroundOrderJob: Job? = null
    private var monitoredAccount: String? = null
    private var pendingOrderId: Long? = null
    private var openedPendingOrderId: Long? = null
    private var notificationPermissionRequested = false
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) restartForegroundOrderChecking()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingOrderId = savedInstanceState?.getLong(STATE_PENDING_ORDER_ID)
            ?.takeUnless { it == NO_ORDER_ID }
        captureNotificationOrder(intent)
        setContentView(R.layout.activity_main)
        OrderNotificationPublisher(this).createChannel()
        val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        bottomNavigation.setOnItemSelectedListener { item ->
            if (changingBottomSelection) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_dashboard -> showRoot(DashboardFragment(), R.id.nav_dashboard)
                R.id.nav_orders -> showRoot(OrdersFragment(), R.id.nav_orders)
                R.id.nav_cards -> showRoot(AvailableCardsFragment(), R.id.nav_cards)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        supportFragmentManager.addOnBackStackChangedListener { syncNavigation() }
        viewModel.authenticated.observe(this) { authenticated ->
            when (authenticated) {
                true -> {
                    if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null ||
                        supportFragmentManager.findFragmentById(R.id.fragment_container) is LoginFragment
                    ) showRoot(DashboardFragment(), R.id.nav_dashboard)
                    configureNotificationMonitoring()
                    window.decorView.post { openPendingNotificationOrder() }
                }
                false -> {
                    openedPendingOrderId = null
                    stopNotificationMonitoring(cancelBackground = true)
                    showRoot(LoginFragment())
                }
                null -> Unit
            }
        }
        viewModel.sellerAccount.observe(this) {
            configureNotificationMonitoring()
        }
        viewModel.order.observe(this) { state ->
            val loadedOrderId = (state as? UiState.Data)?.value?.id
            if (loadedOrderId != null && loadedOrderId == pendingOrderId) {
                pendingOrderId = null
                openedPendingOrderId = null
            }
        }
        bottomNavigation.post { syncNavigation() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureNotificationOrder(intent)
        openPendingNotificationOrder()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_PENDING_ORDER_ID, pendingOrderId ?: NO_ORDER_ID)
        super.onSaveInstanceState(outState)
    }

    override fun dashboard() = selectRoot(R.id.nav_dashboard)
    override fun orders() = selectRoot(R.id.nav_orders)
    override fun order(id: Long) = show(OrderDetailFragment.newInstance(id))
    override fun availableCards() = selectRoot(R.id.nav_cards)
    override fun card(id: Long, customer: String, cardType: String, orderId: Long?) =
        show(CardDetailFragment.newInstance(id, customer, cardType, orderId))
    override fun writeCard(
        id: Long,
        publicUrl: String,
        cardUid: String,
        customer: String,
        orderId: Long?,
        rewriteMode: Boolean,
        cardType: String,
        representative: String,
        currentStatus: String,
    ) {
        viewModel.resetProgramming()
        show(NfcWriteFragment.newInstance(
            id, publicUrl, cardUid, customer, orderId,
            rewriteMode, cardType, representative, currentStatus,
        ))
    }
    override fun backToCardDetails(id: Long, customer: String, cardType: String, orderId: Long?) {
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current is NfcWriteFragment && supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
            var restored = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (restored is CardDetailFragment && restored.matchesCard(id)) return
            if (restored is NfcWriteFragment && restored.matchesCard(id) &&
                supportFragmentManager.backStackEntryCount > 0
            ) {
                supportFragmentManager.popBackStackImmediate()
                restored = supportFragmentManager.findFragmentById(R.id.fragment_container)
                if (restored is CardDetailFragment && restored.matchesCard(id)) return
            }
        }
        card(id, customer, cardType, orderId)
    }
    override fun logout() {
        stopNotificationMonitoring(cancelBackground = true)
        viewModel.logout()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        enableRequestedNfcReader()
    }

    override fun onPause() {
        activityResumed = false
        disableNfcReaderMode()
        super.onPause()
    }

    override fun startNfcReader(callback: (Tag) -> Unit): String? {
        val adapter = nfcAdapter ?: return "This device does not support NFC."
        if (!adapter.isEnabled) return "NFC is turned off. Enable NFC in system settings and try again."
        nfcCallback = callback
        return enableRequestedNfcReader()
    }

    override fun stopNfcReader() {
        nfcCallback = null
        disableNfcReaderMode()
    }

    private fun enableRequestedNfcReader(): String? {
        if (nfcCallback == null) return null
        val adapter = nfcAdapter ?: return "This device does not support NFC."
        if (!adapter.isEnabled) return "NFC is turned off. Enable NFC in system settings and try again."
        if (!activityResumed || readerModeEnabled) return null
        return try {
            val extras = Bundle().apply {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
            }
            adapter.enableReaderMode(
                this,
                { tag -> nfcCallback?.invoke(tag) },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                extras,
            )
            readerModeEnabled = true
            Log.i("NexTapNfcReader", "Reader Mode enabled flags=NFC_A|NO_PLATFORM_SOUNDS")
            null
        } catch (e: IllegalStateException) {
            "NFC reader could not start while this screen was opening. Tap Try again."
        } catch (e: UnsupportedOperationException) {
            "This device does not support NFC reader mode."
        } catch (e: SecurityException) {
            "NFC access was denied by the device."
        }
    }

    private fun disableNfcReaderMode() {
        if (!readerModeEnabled) return
        runCatching { nfcAdapter?.disableReaderMode(this) }
        readerModeEnabled = false
        Log.i("NexTapNfcReader", "Reader Mode disabled")
    }

    private fun configureNotificationMonitoring() {
        if (viewModel.authenticated.value != true) return
        val account = viewModel.sellerAccount.value ?: return
        if (monitoredAccount != account) {
            foregroundOrderJob?.cancel()
            monitoredAccount = account
            OrderNotificationWork.schedule(applicationContext, account)
        }
        if (foregroundOrderJob?.isActive != true) startForegroundOrderChecking()
        requestNotificationPermissionIfNeeded()
    }

    private fun startForegroundOrderChecking() {
        foregroundOrderJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive && viewModel.authenticated.value == true) {
                    when (val result = newOrderChecker.check()) {
                        OrderCheckResult.NoSession,
                        OrderCheckResult.Unauthorized -> viewModel.expireSession()
                        is OrderCheckResult.NotificationsPosted -> {
                            viewModel.loadOrders(force = true)
                            viewModel.loadDashboard(force = true)
                        }
                        else -> Unit
                    }
                    delay(FOREGROUND_CHECK_INTERVAL_MS)
                }
            }
        }
    }

    private fun restartForegroundOrderChecking() {
        foregroundOrderJob?.cancel()
        foregroundOrderJob = null
        if (viewModel.authenticated.value == true && monitoredAccount != null) {
            startForegroundOrderChecking()
        }
    }

    private fun stopNotificationMonitoring(cancelBackground: Boolean) {
        foregroundOrderJob?.cancel()
        foregroundOrderJob = null
        monitoredAccount = null
        if (cancelBackground) OrderNotificationWork.cancelAll(applicationContext)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionRequested) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermissionRequested = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun captureNotificationOrder(intent: Intent?) {
        if (intent?.hasExtra(OrderNotificationPublisher.EXTRA_ORDER_ID) == true) {
            pendingOrderId = intent.getLongExtra(OrderNotificationPublisher.EXTRA_ORDER_ID, NO_ORDER_ID)
                .takeUnless { it == NO_ORDER_ID }
            openedPendingOrderId = null
            intent.removeExtra(OrderNotificationPublisher.EXTRA_ORDER_ID)
        }
    }

    private fun openPendingNotificationOrder() {
        if (viewModel.authenticated.value != true) return
        val orderId = pendingOrderId ?: return
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current is OrderDetailFragment && viewModel.selectedOrderId == orderId) {
            pendingOrderId = null
            openedPendingOrderId = null
            return
        }
        if (openedPendingOrderId == orderId) return
        openedPendingOrderId = orderId
        order(orderId)
    }

    private fun show(fragment: Fragment) {
        findViewById<BottomNavigationView>(R.id.bottom_navigation).visibility = android.view.View.GONE
        supportFragmentManager.beginTransaction().replace(R.id.fragment_container, fragment)
            .addToBackStack(null).commit()
    }

    private fun selectRoot(itemId: Int) {
        val navigation = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        if (navigation.selectedItemId != itemId) navigation.selectedItemId = itemId
        else if (supportFragmentManager.backStackEntryCount > 0) when (itemId) {
            R.id.nav_dashboard -> showRoot(DashboardFragment(), itemId)
            R.id.nav_orders -> showRoot(OrdersFragment(), itemId)
            R.id.nav_cards -> showRoot(AvailableCardsFragment(), itemId)
        }
    }

    private fun showRoot(fragment: Fragment, selectedItem: Int? = null) {
        supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current?.javaClass != fragment.javaClass) {
            supportFragmentManager.beginTransaction().replace(R.id.fragment_container, fragment).commit()
        }
        val navigation = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        navigation.visibility = if (fragment is LoginFragment) android.view.View.GONE else android.view.View.VISIBLE
        if (selectedItem != null && navigation.selectedItemId != selectedItem) {
            changingBottomSelection = true
            navigation.selectedItemId = selectedItem
            changingBottomSelection = false
        }
    }

    private fun syncNavigation() {
        val navigation = findViewById<BottomNavigationView>(R.id.bottom_navigation)
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        val isRoot = supportFragmentManager.backStackEntryCount == 0
        navigation.visibility = if (isRoot && current !is LoginFragment) android.view.View.VISIBLE else android.view.View.GONE
        val item = when (current) {
            is DashboardFragment -> R.id.nav_dashboard
            is OrdersFragment -> R.id.nav_orders
            is AvailableCardsFragment -> R.id.nav_cards
            else -> null
        }
        if (item != null && navigation.selectedItemId != item) {
            changingBottomSelection = true
            navigation.selectedItemId = item
            changingBottomSelection = false
        }
    }

    companion object {
        private const val FOREGROUND_CHECK_INTERVAL_MS = 30_000L
        private const val STATE_PENDING_ORDER_ID = "pending_notification_order_id"
        private const val NO_ORDER_ID = -1L
    }
}

interface AppNavigator {
    fun dashboard()
    fun orders()
    fun order(id: Long)
    fun availableCards()
    fun card(id: Long, customer: String = "", cardType: String = "", orderId: Long? = null)
    fun writeCard(
        id: Long,
        publicUrl: String,
        cardUid: String = "—",
        customer: String = "—",
        orderId: Long? = null,
        rewriteMode: Boolean = false,
        cardType: String = "",
        representative: String = "",
        currentStatus: String = "",
    )
    fun backToCardDetails(id: Long, customer: String = "", cardType: String = "", orderId: Long? = null)
    fun logout()
    fun startNfcReader(callback: (Tag) -> Unit): String?
    fun stopNfcReader()
}

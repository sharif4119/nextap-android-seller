package com.example.nfcwritter

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.example.nfcwritter.ui.AvailableCardsFragment
import com.example.nfcwritter.ui.CardDetailFragment
import com.example.nfcwritter.ui.DashboardFragment
import com.example.nfcwritter.ui.LoginFragment
import com.example.nfcwritter.ui.NfcWriteFragment
import com.example.nfcwritter.ui.OrderDetailFragment
import com.example.nfcwritter.ui.OrdersFragment
import com.example.nfcwritter.ui.SellerViewModel

class MainActivity : AppCompatActivity(), AppNavigator {
    private val viewModel: SellerViewModel by viewModels()
    private var nfcCallback: ((Tag) -> Unit)? = null
    private var changingBottomSelection = false
    private var activityResumed = false
    private var readerModeEnabled = false
    private val nfcAdapter by lazy { NfcAdapter.getDefaultAdapter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
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
                true -> if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null ||
                    supportFragmentManager.findFragmentById(R.id.fragment_container) is LoginFragment
                ) showRoot(DashboardFragment(), R.id.nav_dashboard)
                false -> showRoot(LoginFragment())
                null -> Unit
            }
        }
        bottomNavigation.post { syncNavigation() }
    }

    override fun dashboard() = selectRoot(R.id.nav_dashboard)
    override fun orders() = selectRoot(R.id.nav_orders)
    override fun order(id: Long) = show(OrderDetailFragment.newInstance(id))
    override fun availableCards() = selectRoot(R.id.nav_cards)
    override fun card(id: Long) = show(CardDetailFragment.newInstance(id))
    override fun writeCard(id: Long, publicUrl: String, cardUid: String, customer: String) {
        viewModel.resetProgramming()
        show(NfcWriteFragment.newInstance(id, publicUrl, cardUid, customer))
    }
    override fun logout() {
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
}

interface AppNavigator {
    fun dashboard()
    fun orders()
    fun order(id: Long)
    fun availableCards()
    fun card(id: Long)
    fun writeCard(id: Long, publicUrl: String, cardUid: String = "—", customer: String = "—")
    fun logout()
    fun startNfcReader(callback: (Tag) -> Unit): String?
    fun stopNfcReader()
}

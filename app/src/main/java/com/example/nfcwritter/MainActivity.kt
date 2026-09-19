package com.example.nfcwritter

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
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
    private val nfcAdapter by lazy { NfcAdapter.getDefaultAdapter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        viewModel.authenticated.observe(this) { authenticated ->
            when (authenticated) {
                true -> if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null ||
                    supportFragmentManager.findFragmentById(R.id.fragment_container) is LoginFragment
                ) showRoot(DashboardFragment())
                false -> showRoot(LoginFragment())
                null -> Unit
            }
        }
    }

    override fun dashboard() = showRoot(DashboardFragment())
    override fun orders() = show(OrdersFragment())
    override fun order(id: Long) = show(OrderDetailFragment.newInstance(id))
    override fun availableCards() = show(AvailableCardsFragment())
    override fun card(id: Long) = show(CardDetailFragment.newInstance(id))
    override fun writeCard(id: Long, publicUrl: String) = show(NfcWriteFragment.newInstance(id, publicUrl))
    override fun logout() {
        viewModel.logout()
    }

    override fun startNfcReader(callback: (Tag) -> Unit): String? {
        val adapter = nfcAdapter ?: return "This device does not support NFC."
        if (!adapter.isEnabled) return "NFC is turned off. Enable NFC in system settings and try again."
        nfcCallback = callback
        adapter.enableReaderMode(
            this,
            { tag -> nfcCallback?.invoke(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_NFC_BARCODE,
            null,
        )
        return null
    }

    override fun stopNfcReader() {
        nfcCallback = null
        nfcAdapter?.disableReaderMode(this)
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.beginTransaction().replace(R.id.fragment_container, fragment)
            .addToBackStack(null).commit()
    }

    private fun showRoot(fragment: Fragment) {
        supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction().replace(R.id.fragment_container, fragment).commit()
    }
}

interface AppNavigator {
    fun dashboard()
    fun orders()
    fun order(id: Long)
    fun availableCards()
    fun card(id: Long)
    fun writeCard(id: Long, publicUrl: String)
    fun logout()
    fun startNfcReader(callback: (Tag) -> Unit): String?
    fun stopNfcReader()
}

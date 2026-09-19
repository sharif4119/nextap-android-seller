package com.example.nfcwritter.ui

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.nfcwritter.R
import com.example.nfcwritter.nfc.NfcWriteResult
import com.example.nfcwritter.nfc.NfcWriter
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NfcWriteFragment : BaseFragment(R.layout.fragment_nfc_write) {
    private val viewModel: SellerViewModel by activityViewModels()
    private val cardId get() = requireArguments().getLong(ARG_CARD_ID)
    private val publicUrl get() = requireArguments().getString(ARG_URL).orEmpty()
    private var busy = false
    private var tagVerified = false
    private var awaitingFormatVerification = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.url).text = publicUrl
        view.findViewById<MaterialButton>(R.id.retry_button).setOnClickListener {
            if (tagVerified) viewModel.confirmProgrammedAndActivate(cardId) else beginScan()
        }
        view.findViewById<MaterialButton>(R.id.done_button).setOnClickListener { navigator.dashboard() }
        viewModel.programming.observe(viewLifecycleOwner) { state ->
            when (state) {
                UiState.Loading -> showProgress("Tag verified. Updating the card in Django…")
                is UiState.Error -> showError("The tag was verified, but the server update failed: ${state.message}")
                is UiState.Data -> showSuccess()
                UiState.Idle -> Unit
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!tagVerified) beginScan()
    }

    override fun onPause() {
        navigator.stopNfcReader()
        super.onPause()
    }

    private fun beginScan() {
        busy = false
        view?.findViewById<ProgressBar>(R.id.progress)?.visibility = View.VISIBLE
        view?.findViewById<TextView>(R.id.message)?.text = if (awaitingFormatVerification) {
            "The tag was formatted and written. Remove it, then tap the same tag again to verify the URL."
        } else "Hold an NFC tag against the phone. Keep it still while the URL is written and verified."
        view?.findViewById<MaterialButton>(R.id.retry_button)?.visibility = View.GONE
        val error = navigator.startNfcReader { tag ->
            if (busy) return@startNfcReader
            busy = true
            viewLifecycleOwner.lifecycleScope.launch {
                navigator.stopNfcReader()
                val result = withContext(Dispatchers.IO) {
                    if (awaitingFormatVerification) NfcWriter.verify(tag, publicUrl)
                    else NfcWriter.writeAndVerify(tag, publicUrl)
                }
                when (result) {
                    NfcWriteResult.Success -> {
                        tagVerified = true
                        awaitingFormatVerification = false
                        viewModel.confirmProgrammedAndActivate(cardId)
                    }
                    NfcWriteResult.NeedsRetapForVerification -> {
                        awaitingFormatVerification = true
                        beginScan()
                    }
                    is NfcWriteResult.Failure -> showError(result.reason)
                }
            }
        }
        if (error != null) showError(error)
    }

    private fun showProgress(text: String) {
        view?.findViewById<ProgressBar>(R.id.progress)?.visibility = View.VISIBLE
        view?.findViewById<TextView>(R.id.message)?.text = text
        view?.findViewById<MaterialButton>(R.id.retry_button)?.visibility = View.GONE
    }

    private fun showError(text: String) {
        busy = false
        view?.findViewById<ProgressBar>(R.id.progress)?.visibility = View.GONE
        view?.findViewById<TextView>(R.id.message)?.text = text
        view?.findViewById<MaterialButton>(R.id.retry_button)?.visibility = View.VISIBLE
    }

    private fun showSuccess() {
        navigator.stopNfcReader()
        view?.findViewById<ProgressBar>(R.id.progress)?.visibility = View.GONE
        view?.findViewById<TextView>(R.id.message)?.text = "Success. The NFC URL was verified, and the card is programmed and active."
        view?.findViewById<MaterialButton>(R.id.retry_button)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.done_button)?.visibility = View.VISIBLE
    }

    companion object {
        private const val ARG_CARD_ID = "card_id"
        private const val ARG_URL = "public_url"
        fun newInstance(cardId: Long, publicUrl: String) = NfcWriteFragment().apply {
            arguments = bundleOf(ARG_CARD_ID to cardId, ARG_URL to publicUrl)
        }
    }
}

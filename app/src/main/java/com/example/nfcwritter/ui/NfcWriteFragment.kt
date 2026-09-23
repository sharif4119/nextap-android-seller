package com.example.nfcwritter.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import com.example.nfcwritter.R
import com.example.nfcwritter.nfc.NfcCaptureAction
import com.example.nfcwritter.nfc.NfcCaptureController
import com.example.nfcwritter.nfc.NfcCaptureState
import com.example.nfcwritter.nfc.NfcOperationStage
import com.example.nfcwritter.nfc.NfcWriteResult
import com.example.nfcwritter.nfc.NfcWriter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NfcWriteFragment : BaseFragment(R.layout.fragment_nfc_write) {
    private val viewModel: SellerViewModel by activityViewModels()
    private val cardId get() = requireArguments().getLong(ARG_CARD_ID)
    private val publicUrl get() = requireArguments().getString(ARG_URL).orEmpty()
    private val cardUid get() = requireArguments().getString(ARG_UID).orEmpty()
    private val customer get() = requireArguments().getString(ARG_CUSTOMER).orEmpty()
    private val captureController = NfcCaptureController()
    private var writeJob: Job? = null
    private var tagVerified = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupBack(view.findViewById<MaterialToolbar>(R.id.toolbar))
        view.findViewById<TextView>(R.id.card_uid).text = "Card UID  ${cardUid.ifBlank { "—" }}"
        view.findViewById<TextView>(R.id.customer).text = "Customer  ${customer.ifBlank { "—" }}"
        view.findViewById<TextView>(R.id.url).text = "URL  $publicUrl"
        view.findViewById<MaterialButton>(R.id.retry_button).setOnClickListener {
            if (tagVerified) {
                viewModel.confirmProgrammedAndActivate(cardId)
            } else if (captureController.retry()) {
                beginScan()
            }
        }
        view.findViewById<MaterialButton>(R.id.done_button).setOnClickListener {
            viewModel.loadDashboard(force = true)
            navigator.dashboard()
        }
        viewModel.programming.observe(viewLifecycleOwner) { state ->
            when (state) {
                ProgrammingState.Idle -> Unit
                ProgrammingState.MarkingProgrammed -> showWorking("Card written", "Marking the verified card as programmed…")
                ProgrammingState.Activating -> showWorking("Activating card", "The card is programmed. Activating it now…")
                is ProgrammingState.Error -> showError(
                    "The tag was verified, but the server update failed: ${state.message}",
                    nfcFailure = false,
                )
                ProgrammingState.Success -> showSuccess()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        captureController.resume()
        view?.post {
            if (
                viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                !tagVerified && viewModel.programming.value !is ProgrammingState.Success
            ) beginScan()
        }
    }

    override fun onPause() {
        captureController.cancelForLifecycle()
        writeJob?.cancel()
        navigator.stopNfcReader()
        super.onPause()
    }

    override fun onStop() {
        navigator.stopNfcReader()
        super.onStop()
    }

    override fun onDestroyView() {
        navigator.stopNfcReader()
        super.onDestroyView()
    }

    private fun beginScan() {
        val verificationOnly = captureController.state == NfcCaptureState.WAITING_FOR_VERIFICATION
        view?.findViewById<ImageView>(R.id.state_icon)?.setImageResource(R.drawable.ic_nfc)
        view?.findViewById<TextView>(R.id.headline)?.text = if (verificationOnly) "Ready to verify" else "Ready to write"
        view?.findViewById<TextView>(R.id.message)?.text = if (verificationOnly) {
            "Remove the card, then re-tap it. NexTap will read first and will not rewrite unless you explicitly retry."
        } else "Hold the NFC card near the back of your phone. Keep it still until verification finishes."
        view?.findViewById<ProgressBar>(R.id.progress)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.retry_button)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.done_button)?.visibility = View.GONE
        setDiagnostics(
            "NFC reader ready\n" +
                if (verificationOnly) "Waiting to read back an ISO14443-A / DESFire tag"
                else "Waiting for an ISO14443-A / DESFire tag",
        )

        val error = navigator.startNfcReader { tag ->
            val action = captureController.tryAcceptTag() ?: return@startNfcReader
            val detectedTechList = tag.techList.joinToString(separator = ", ")
            val currentView = view ?: return@startNfcReader
            currentView.post {
                if (!isAdded || view == null) return@post
                view?.findViewById<TextView>(R.id.headline)?.text = "Tag detected"
                view?.findViewById<TextView>(R.id.message)?.text = "The NFC card was captured by NexTap. Keep it in place."
                setDiagnostics("Tag detected\nDetected techList: $detectedTechList")
                writeJob = viewLifecycleOwner.lifecycleScope.launch {
                    showWorking(
                        if (action == NfcCaptureAction.VERIFY_ONLY) "Verifying URL" else "Inspecting NFC card",
                        "Keep the card against the phone…",
                    )
                    val result = withContext(Dispatchers.IO) {
                        if (action == NfcCaptureAction.VERIFY_ONLY) {
                            view?.post {
                                appendDiagnostic("Verifying...")
                                showWorking("Verifying URL", "Reading the card back and checking the exact URL…")
                            }
                            NfcWriter.verify(tag, publicUrl)
                        } else NfcWriter.writeAndVerify(tag, publicUrl) { stage ->
                            view?.post {
                                when (stage) {
                                    NfcOperationStage.INSPECTING -> {
                                        appendDiagnostic("Checking current NDEF contents, writability and capacity...")
                                        showWorking("Inspecting NFC card", "Checking the existing URL and card capabilities…")
                                    }
                                    NfcOperationStage.WRITING -> {
                                        appendDiagnostic("Writing...")
                                        showWorking("Writing NFC card", "Writing the secure public URL…")
                                    }
                                    NfcOperationStage.VERIFYING -> {
                                        appendDiagnostic("Verifying...")
                                        showWorking("Verifying URL", "Reading the card back and checking the exact URL…")
                                    }
                                }
                            }
                        }
                    }
                    when (result) {
                        NfcWriteResult.Success -> {
                            appendDiagnostic("Exact URL verification succeeded")
                            captureController.complete()
                            tagVerified = true
                            showWorking("URL verified", "Updating the card in NexTap…")
                            viewModel.confirmProgrammedAndActivate(cardId)
                        }
                        is NfcWriteResult.NeedsRetapForVerification -> {
                            captureController.needsVerificationRetap()
                            appendDiagnostic("Verification re-tap required: ${result.reason}")
                            showVerificationRetap(result.reason)
                        }
                        is NfcWriteResult.Failure -> {
                            showError(result.reason)
                        }
                    }
                }
            }
        }
        if (error != null) {
            showError(error)
        }
    }

    private fun showWorking(title: String, text: String) {
        view?.findViewById<TextView>(R.id.headline)?.text = title
        view?.findViewById<TextView>(R.id.message)?.text = text
        view?.findViewById<ProgressBar>(R.id.progress)?.visibility = View.VISIBLE
        view?.findViewById<MaterialButton>(R.id.retry_button)?.visibility = View.GONE
    }

    private fun showVerificationRetap(text: String) {
        view?.findViewById<TextView>(R.id.headline)?.text = "Re-tap to verify"
        view?.findViewById<TextView>(R.id.message)?.text = text
        view?.findViewById<ProgressBar>(R.id.progress)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.retry_button)?.visibility = View.GONE
    }

    private fun showError(text: String, nfcFailure: Boolean = true) {
        if (nfcFailure) captureController.fail()
        view?.findViewById<TextView>(R.id.headline)?.text = "Couldn’t complete the card"
        view?.findViewById<TextView>(R.id.message)?.text = text
        view?.findViewById<ProgressBar>(R.id.progress)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.retry_button)?.visibility = View.VISIBLE
        appendDiagnostic("Error: $text")
    }

    private fun showSuccess() {
        navigator.stopNfcReader()
        view?.findViewById<ImageView>(R.id.state_icon)?.setImageResource(R.drawable.ic_success)
        view?.findViewById<TextView>(R.id.headline)?.text = "Card programmed successfully"
        view?.findViewById<TextView>(R.id.message)?.text = "URL verified • Card activated"
        view?.findViewById<ProgressBar>(R.id.progress)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.retry_button)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.done_button)?.visibility = View.VISIBLE
        appendDiagnostic("Programmed endpoint succeeded\nActivate endpoint succeeded")
    }

    private fun setDiagnostics(text: String) {
        view?.findViewById<TextView>(R.id.diagnostics)?.text = text
    }

    private fun appendDiagnostic(text: String) {
        val diagnostics = view?.findViewById<TextView>(R.id.diagnostics) ?: return
        val current = diagnostics.text?.toString().orEmpty()
        diagnostics.text = if (current.isBlank()) text else "$current\n$text"
    }

    companion object {
        private const val ARG_CARD_ID = "card_id"
        private const val ARG_URL = "public_url"
        private const val ARG_UID = "card_uid"
        private const val ARG_CUSTOMER = "customer"

        fun newInstance(cardId: Long, publicUrl: String, cardUid: String, customer: String) = NfcWriteFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_CARD_ID, cardId)
                putString(ARG_URL, publicUrl)
                putString(ARG_UID, cardUid)
                putString(ARG_CUSTOMER, customer)
            }
        }
    }
}

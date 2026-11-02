package com.example.nfcwritter.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import com.example.nfcwritter.R
import com.example.nfcwritter.data.NfcCard
import com.example.nfcwritter.data.PrintDesignSupport
import com.example.nfcwritter.data.SellerRepository
import com.example.nfcwritter.nfc.NfcCaptureAction
import com.example.nfcwritter.nfc.NfcCaptureController
import com.example.nfcwritter.nfc.NfcCaptureState
import com.example.nfcwritter.nfc.NfcOperationStage
import com.example.nfcwritter.nfc.NfcWriteResult
import com.example.nfcwritter.nfc.NfcWriter
import com.example.nfcwritter.nfc.NfcRewritePolicy
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
    private val orderId get() = requireArguments().getLong(ARG_ORDER_ID, -1L).takeIf { it > 0L }
    private val rewriteMode get() = requireArguments().getBoolean(ARG_REWRITE_MODE, false)
    private val cardType get() = requireArguments().getString(ARG_CARD_TYPE).orEmpty()
    private val representative get() = requireArguments().getString(ARG_REPRESENTATIVE).orEmpty()
    private val currentStatus get() = requireArguments().getString(ARG_STATUS).orEmpty()
    private val captureController = NfcCaptureController()
    private var writeJob: Job? = null
    private var tagVerified = false
    private var overwriteConfirmationFingerprint: String? = null
    private var pendingRewriteRefresh = false
    private var retryRewriteRefresh = false
    private var pendingBackendUpdateRetry = false

    fun matchesCard(id: Long): Boolean = cardId == id

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupBack(view.findViewById<MaterialToolbar>(R.id.toolbar))
        view.findViewById<MaterialToolbar>(R.id.toolbar).title = if (rewriteMode) "Rewrite NFC Card" else "Write NFC"
        view.findViewById<TextView>(R.id.card_uid).text = "Card UID  ${cardUid.ifBlank { "—" }}"
        view.findViewById<TextView>(R.id.customer).text = "Customer  ${customer.ifBlank { "—" }}"
        view.findViewById<TextView>(R.id.card_type).text = "Card Type  ${cardType.ifBlank { "—" }}"
        view.findViewById<TextView>(R.id.representative).apply {
            text = "Representative  ${representative.ifBlank { "Not assigned" }}"
            visibility = if (cardType.contains("Business", ignoreCase = true)) View.VISIBLE else View.GONE
        }
        view.findViewById<TextView>(R.id.current_status).text = "Current Status  ${currentStatus.ifBlank { "ASSIGNED" }}"
        view.findViewById<TextView>(R.id.url).text = "URL  $publicUrl"
        view.findViewById<MaterialButton>(R.id.retry_button).setOnClickListener {
            if (retryRewriteRefresh) {
                retryRewriteRefresh = false
                requestRewriteFromSuccess()
            } else if (tagVerified && !rewriteMode && pendingBackendUpdateRetry) {
                viewModel.confirmProgrammedAndActivate(cardId, orderId)
            } else if (rewriteMode && tagVerified) {
                startRewriteAgain()
            } else if (captureController.retry()) {
                beginScan()
            }
        }
        view.findViewById<MaterialButton>(R.id.done_button).setOnClickListener {
            if (rewriteMode) {
                navigator.backToCardDetails(cardId, customer, cardType, orderId)
            } else {
                viewModel.loadDashboard(force = true)
                navigator.dashboard()
            }
        }
        view.findViewById<MaterialButton>(R.id.rewrite_button).setOnClickListener { requestRewriteFromSuccess() }
        view.findViewById<MaterialButton>(R.id.rewrite_again_button).setOnClickListener { startRewriteAgain() }
        viewModel.programming.observe(viewLifecycleOwner) { state ->
            if (rewriteMode) return@observe
            when (state) {
                ProgrammingState.Idle -> Unit
                ProgrammingState.MarkingProgrammed -> showWorking("Card written", "Marking the verified card as programmed…")
                ProgrammingState.Activating -> showWorking("Activating card", "The card is programmed. Activating it now…")
                is ProgrammingState.Error -> showError(
                    "The tag was verified, but the server update failed: ${state.message}",
                    nfcFailure = false,
                )
                ProgrammingState.Success -> {
                    pendingBackendUpdateRetry = false
                    showSuccess()
                }
            }
        }
        viewModel.card.observe(viewLifecycleOwner) { state ->
            if (!pendingRewriteRefresh) return@observe
            when (state) {
                is UiState.Error -> {
                    pendingRewriteRefresh = false
                    retryRewriteRefresh = true
                    showError("Could not confirm the current card with NexTap: ${state.message}", nfcFailure = false)
                }
                is UiState.Data -> {
                    if (state.value.id != cardId) return@observe
                    pendingRewriteRefresh = false
                    retryRewriteRefresh = false
                    confirmRewrite(state.value)
                }
                UiState.Loading, UiState.Idle -> Unit
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
        view?.findViewById<TextView>(R.id.headline)?.text = when {
            verificationOnly -> "Ready to verify"
            rewriteMode -> "Ready to rewrite"
            else -> "Ready to write"
        }
        view?.findViewById<TextView>(R.id.message)?.text = if (verificationOnly) {
            "Remove the card, then re-tap it. NexTap will read first and will not rewrite unless you explicitly retry."
        } else if (rewriteMode) {
            "Hold the NFC card against your phone to rewrite its assigned profile link. Keep it still until verification finishes."
        } else "Hold the NFC card near the back of your phone. Keep it still until verification finishes."
        view?.findViewById<ProgressBar>(R.id.progress)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.retry_button)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.done_button)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.rewrite_button)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.rewrite_again_button)?.visibility = View.GONE
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
                        } else NfcWriter.writeAndVerify(
                            tag = tag,
                            expectedUrl = publicUrl,
                            backendBaseUrl = SellerRepository.BASE_URL,
                            forceRewrite = rewriteMode,
                            overwriteConfirmationFingerprint = overwriteConfirmationFingerprint,
                        ) { stage ->
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
                            if (!NfcRewritePolicy.shouldUpdateBackendAfterWrite(rewriteMode)) {
                                viewModel.loadCard(cardId, force = true)
                                orderId?.let { viewModel.loadOrder(it, force = true) }
                                showRewriteSuccess()
                            } else {
                                pendingBackendUpdateRetry = true
                                showWorking("URL verified", "Updating the card in NexTap…")
                                viewModel.confirmProgrammedAndActivate(cardId, orderId)
                            }
                        }
                        is NfcWriteResult.RequiresOverwriteConfirmation -> requestExistingDataConfirmation(result.fingerprint)
                        NfcWriteResult.DifferentNexTapCardProtected -> showError(
                            "This tag contains a different NexTap card link. To protect that assigned card, NexTap did not write to it. Scan the intended card and retry.",
                        )
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
        view?.findViewById<TextView>(R.id.headline)?.text = if (rewriteMode && title == "Writing NFC card") "Writing NFC" else title
        view?.findViewById<TextView>(R.id.message)?.text = text
        view?.findViewById<ProgressBar>(R.id.progress)?.visibility = View.VISIBLE
        view?.findViewById<MaterialButton>(R.id.retry_button)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.done_button)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.rewrite_button)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.rewrite_again_button)?.visibility = View.GONE
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
        view?.findViewById<MaterialButton>(R.id.done_button)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.rewrite_button)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.rewrite_again_button)?.visibility = View.GONE
        appendDiagnostic("Error: $text")
    }

    private fun showSuccess() {
        navigator.stopNfcReader()
        view?.findViewById<ImageView>(R.id.state_icon)?.setImageResource(R.drawable.ic_success)
        view?.findViewById<TextView>(R.id.headline)?.text = "Card programmed successfully"
        view?.findViewById<TextView>(R.id.message)?.text = "URL verified • Card activated"
        view?.findViewById<TextView>(R.id.current_status)?.text = "Current Status  ACTIVE"
        view?.findViewById<ProgressBar>(R.id.progress)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.retry_button)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.done_button)?.visibility = View.VISIBLE
        view?.findViewById<MaterialButton>(R.id.rewrite_button)?.visibility = View.VISIBLE
        appendDiagnostic("Backend card status confirmed ACTIVE")
    }

    private fun showRewriteSuccess() {
        navigator.stopNfcReader()
        view?.findViewById<ImageView>(R.id.state_icon)?.setImageResource(R.drawable.ic_success)
        view?.findViewById<TextView>(R.id.headline)?.text = "NFC Rewritten and Verified"
        view?.findViewById<TextView>(R.id.message)?.text =
            "The assigned NexTap profile link was successfully written and verified. Card activation and profile information are unchanged."
        view?.findViewById<ProgressBar>(R.id.progress)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.retry_button)?.visibility = View.GONE
        view?.findViewById<MaterialButton>(R.id.done_button)?.apply {
            text = "Back to Card Details"
            visibility = View.VISIBLE
        }
        view?.findViewById<MaterialButton>(R.id.rewrite_again_button)?.visibility = View.VISIBLE
        appendDiagnostic("Exact URL verification succeeded\nRewrite mode: no programming or activation endpoint called")
    }

    private fun requestExistingDataConfirmation(fingerprint: String) {
        captureController.fail()
        AlertDialog.Builder(requireContext())
            .setTitle("Replace existing NFC data?")
            .setMessage("This NFC tag contains existing data. Rewriting will replace it with this card's assigned NexTap profile link. No data has been changed yet. Confirm, remove the tag, then re-tap it to continue.")
            .setNegativeButton("Cancel") { _, _ ->
                overwriteConfirmationFingerprint = null
                showError("No NFC data was changed. Tap Retry to scan the intended card.")
            }
            .setPositiveButton("Replace and continue") { _, _ ->
                overwriteConfirmationFingerprint = fingerprint
                showError("Overwrite confirmed. Remove the tag, then tap Retry and re-tap the intended tag.")
            }
            .show()
    }

    private fun requestRewriteFromSuccess() {
        pendingRewriteRefresh = true
        showWorking("Checking card status", "Confirming the assigned URL with NexTap…")
        viewModel.loadCard(cardId, force = true)
    }

    private fun confirmRewrite(card: NfcCard) {
        if (!NfcRewritePolicy.isRewriteAvailable(card.status)) {
            retryRewriteRefresh = true
            showError("Only a prepared PROGRAMMED or ACTIVE card can be rewritten.", nfcFailure = false)
            return
        }
        val url = card.publicUrl
        if (url.isNullOrBlank() || PrintDesignSupport.resolveSameServerUrl(SellerRepository.BASE_URL, url) == null) {
            retryRewriteRefresh = true
            showError("NexTap did not provide a valid assigned URL for this card. Refresh the card before rewriting.", nfcFailure = false)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Rewrite NFC Card?")
            .setMessage("This will overwrite the NFC data on the scanned tag with this card's assigned NexTap profile link. The card's existing account, profile and activation status will remain unchanged.")
            .setNegativeButton("Cancel") { _, _ -> showSuccess() }
            .setPositiveButton("Rewrite NFC") { _, _ ->
                navigator.writeCard(
                    card.id, url, card.uid, customer.ifBlank { card.ownerUsername.orEmpty() }, orderId,
                    rewriteMode = true,
                    cardType = cardType.ifBlank { if (card.cardCategory == "PERSONAL") "Personal Card" else "Business Card" },
                    representative = card.representative?.name ?: representative,
                    currentStatus = card.status,
                )
            }
            .show()
    }

    private fun startRewriteAgain() {
        tagVerified = false
        overwriteConfirmationFingerprint = null
        captureController.restart()
        beginScan()
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
        private const val ARG_ORDER_ID = "order_id"
        private const val ARG_REWRITE_MODE = "rewrite_mode"
        private const val ARG_CARD_TYPE = "card_type"
        private const val ARG_REPRESENTATIVE = "representative"
        private const val ARG_STATUS = "current_status"

        fun newInstance(
            cardId: Long,
            publicUrl: String,
            cardUid: String,
            customer: String,
            orderId: Long? = null,
            rewriteMode: Boolean = false,
            cardType: String = "",
            representative: String = "",
            currentStatus: String = "",
        ) = NfcWriteFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_CARD_ID, cardId)
                putString(ARG_URL, publicUrl)
                putString(ARG_UID, cardUid)
                putString(ARG_CUSTOMER, customer)
                if (orderId != null) putLong(ARG_ORDER_ID, orderId)
                putBoolean(ARG_REWRITE_MODE, rewriteMode)
                putString(ARG_CARD_TYPE, cardType)
                putString(ARG_REPRESENTATIVE, representative)
                putString(ARG_STATUS, currentStatus)
            }
        }
    }
}

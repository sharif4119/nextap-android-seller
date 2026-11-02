package com.example.nfcwritter.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import com.example.nfcwritter.R
import com.example.nfcwritter.data.NfcCard
import com.example.nfcwritter.data.PrintDesignSupport
import com.example.nfcwritter.data.SellerRepository
import com.example.nfcwritter.nfc.NfcRewritePolicy
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar

class CardDetailFragment : BaseFragment(R.layout.fragment_card_detail) {
    private val viewModel: SellerViewModel by activityViewModels()
    private val cardId get() = requireArguments().getLong(ARG_ID)
    private val customer get() = requireArguments().getString(ARG_CUSTOMER).orEmpty()
    private val orderId get() = requireArguments().getLong(ARG_ORDER_ID, -1L).takeIf { it > 0L }
    private val requestedCardType get() = requireArguments().getString(ARG_CARD_TYPE).orEmpty()
    private lateinit var printDesignActions: PrintDesignActions
    private var pendingRewriteRefresh = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        printDesignActions = PrintDesignActions(this, viewModel::downloadPrintDesign)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupBack(view.findViewById<MaterialToolbar>(R.id.toolbar))
        printDesignActions.bind(view)
        view.findViewById<TextView>(R.id.title).text = "Card #$cardId"
        val message = view.findViewById<TextView>(R.id.status_message)
        val write = view.findViewById<MaterialButton>(R.id.write_button)
        val rewrite = view.findViewById<MaterialButton>(R.id.rewrite_button)
        rewrite.setOnClickListener {
            pendingRewriteRefresh = true
            rewrite.isEnabled = false
            message.text = "Refreshing this card from NexTap…"
            viewModel.loadCard(cardId, force = true)
        }
        viewModel.card.observe(viewLifecycleOwner) { state ->
            when (state) {
                UiState.Loading -> message.text = if (pendingRewriteRefresh) "Refreshing this card from NexTap…" else "Loading card…"
                is UiState.Error -> {
                    message.text = state.message
                    rewrite.isEnabled = true
                    if (pendingRewriteRefresh) pendingRewriteRefresh = false
                }
                is UiState.Data -> with(state.value) {
                    if (id != cardId) return@observe
                    printDesignActions.show(this)
                    message.text = when (normalizedStatus) {
                        "ACTIVE" -> "Active and ready to use"
                        "PROGRAMMED" -> "Programmed; activation pending"
                        "AVAILABLE" -> "Available for automatic order preparation"
                        else -> ""
                    }
                    message.setTextColor(requireContext().getColor(if (normalizedStatus == "ACTIVE") R.color.status_success else R.color.text_secondary))
                    view.findViewById<TextView>(R.id.uid).text = "UID: $uid"
                    view.findViewById<TextView>(R.id.card_category).text = "Category: ${cardCategory.ifBlank { "—" }}"
                    val rep = representative?.let {
                        listOf(it.name, it.designation, it.phone, it.email).filter(String::isNotBlank).joinToString("  •  ")
                    }?.takeIf(String::isNotBlank) ?: "No representative assigned"
                    view.findViewById<TextView>(R.id.representative).text = "Representative: $rep"
                    view.findViewById<TextView>(R.id.card_status).text = "Status: $status"
                    view.findViewById<TextView>(R.id.public_url).text = "Public URL: ${publicUrl ?: "Not provided by server"}"
                    write.visibility = if (canWrite) View.VISIBLE else View.GONE
                    val customerName = customer.ifBlank { ownerUsername.orEmpty() }
                    val categoryName = requestedCardType.ifBlank {
                        if (cardCategory == "PERSONAL") "Personal Card" else "Business Card"
                    }
                    write.setOnClickListener {
                        publicUrl?.let {
                            navigator.writeCard(id, it, uid, customerName, orderId, cardType = categoryName, representative = representative?.name.orEmpty(), currentStatus = status)
                        }
                    }
                    rewrite.visibility = if (NfcRewritePolicy.isRewriteAvailable(status)) View.VISIBLE else View.GONE
                    rewrite.isEnabled = !pendingRewriteRefresh
                    if (pendingRewriteRefresh) {
                        pendingRewriteRefresh = false
                        confirmRewrite(this, customerName, categoryName)
                    }
                }
                UiState.Idle -> Unit
            }
        }
        viewModel.loadCard(cardId)
    }

    override fun onDestroyView() {
        printDesignActions.clear()
        super.onDestroyView()
    }

    companion object {
        private const val ARG_ID = "card_id"
        private const val ARG_CUSTOMER = "customer"
        private const val ARG_CARD_TYPE = "card_type"
        private const val ARG_ORDER_ID = "order_id"

        fun newInstance(id: Long, customer: String = "", cardType: String = "", orderId: Long? = null) = CardDetailFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_ID, id)
                putString(ARG_CUSTOMER, customer)
                putString(ARG_CARD_TYPE, cardType)
                if (orderId != null) putLong(ARG_ORDER_ID, orderId)
            }
        }
    }

    fun matchesCard(id: Long): Boolean = cardId == id

    private fun confirmRewrite(card: NfcCard, customerName: String, categoryName: String) {
        val canonicalUrl = card.publicUrl
        if (!NfcRewritePolicy.isRewriteAvailable(card.status) || canonicalUrl.isNullOrBlank()) {
            view?.findViewById<TextView>(R.id.status_message)?.text =
                "This card is not ready to rewrite, or NexTap did not provide its assigned URL."
            return
        }
        if (PrintDesignSupport.resolveSameServerUrl(SellerRepository.BASE_URL, canonicalUrl) == null) {
            view?.findViewById<TextView>(R.id.status_message)?.text =
                "NexTap returned an invalid card URL. Refresh the card or contact support."
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Rewrite NFC Card?")
            .setMessage("This will overwrite the NFC data on the scanned tag with this card's assigned NexTap profile link. The card's existing account, profile and activation status will remain unchanged.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Rewrite NFC") { _, _ ->
                navigator.writeCard(
                    card.id,
                    canonicalUrl,
                    card.uid,
                    customerName,
                    orderId,
                    rewriteMode = true,
                    cardType = categoryName,
                    representative = card.representative?.name.orEmpty(),
                    currentStatus = card.status,
                )
            }
            .show()
    }
}

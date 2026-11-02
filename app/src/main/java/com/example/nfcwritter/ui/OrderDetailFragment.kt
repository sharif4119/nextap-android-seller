package com.example.nfcwritter.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.example.nfcwritter.R
import com.example.nfcwritter.data.CardAllocation
import com.example.nfcwritter.data.NfcCard
import com.example.nfcwritter.data.SellerOrder
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class OrderDetailFragment : BaseFragment(R.layout.fragment_order_detail) {
    private val viewModel: SellerViewModel by activityViewModels()
    private val orderId get() = requireArguments().getLong(ARG_ID)
    private var preparingCard = false
    private var preparingAllocationId: Long? = null
    private lateinit var printDesignActions: PrintDesignActions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        printDesignActions = PrintDesignActions(this, viewModel::downloadPrintDesign)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupBack(view.findViewById<MaterialToolbar>(R.id.toolbar))
        printDesignActions.bind(view)
        view.findViewById<TextView>(R.id.title).text = "Order #$orderId"
        val message = view.findViewById<TextView>(R.id.status)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val prepare = view.findViewById<MaterialButton>(R.id.prepare_button)
        val write = view.findViewById<MaterialButton>(R.id.write_button)
        val activate = view.findViewById<MaterialButton>(R.id.activate_button)
        val refresh = view.findViewById<MaterialButton>(R.id.refresh_order_button)

        refresh.setOnClickListener { viewModel.loadOrder(orderId, force = true) }
        prepare.setOnClickListener {
            preparingCard = true
            viewModel.prepareCard(orderId)
        }
        viewModel.order.observe(viewLifecycleOwner) { state ->
            when (state) {
                UiState.Loading -> {
                    progress.visibility = View.VISIBLE
                    message.visibility = View.VISIBLE
                    message.setTextColor(requireContext().getColor(R.color.text_secondary))
                    message.text = if (preparingCard) "Preparing a new NFC card…" else "Loading order details…"
                    prepare.isEnabled = false
                    write.isEnabled = false
                    activate.isEnabled = false
                    refresh.isEnabled = false
                }
                is UiState.Error -> {
                    progress.visibility = View.GONE
                    message.visibility = View.VISIBLE
                    message.setTextColor(requireContext().getColor(R.color.status_error))
                    message.text = state.message
                    preparingCard = false
                    preparingAllocationId = null
                    prepare.isEnabled = true
                    write.isEnabled = true
                    activate.isEnabled = true
                    refresh.isEnabled = true
                }
                is UiState.Data -> {
                    if (state.value.id != orderId) return@observe
                    progress.visibility = View.GONE
                    preparingCard = false
                    preparingAllocationId = null
                    prepare.isEnabled = true
                    write.isEnabled = true
                    activate.isEnabled = true
                    refresh.isEnabled = true
                    renderOrder(view, state.value, message, prepare, write, activate)
                }
                UiState.Idle -> Unit
            }
        }
        viewModel.loadOrder(orderId)
    }

    private fun renderOrder(
        view: View,
        order: SellerOrder,
        message: TextView,
        prepare: MaterialButton,
        write: MaterialButton,
        activate: MaterialButton,
    ) = with(order) {
        view.findViewById<TextView>(R.id.order_status).text = orderStatus.replace('_', ' ').uppercase()
        view.findViewById<TextView>(R.id.customer).text = customer
        view.findViewById<TextView>(R.id.phone).text = "Phone  $phone"
        val location = listOf(address, city).filter { it.isNotBlank() && it != "—" }.distinct().joinToString(", ")
        view.findViewById<TextView>(R.id.address).text = "Address  ${location.ifBlank { "—" }}"
        view.findViewById<TextView>(R.id.product).text = product
        view.findViewById<TextView>(R.id.price).text = "Price  $price"
        view.findViewById<TextView>(R.id.payment).text = "Payment  ${paymentStatus.replace('_', ' ')}"
        val category = cardCategory.uppercase()
        val kind = businessOrderType.uppercase()
        val categoryLabel = when {
            category == "PERSONAL" -> "Personal Card"
            category == "BUSINESS" && (kind == "MULTIPLE" || quantity > 1) -> "Business Multiple Cards"
            category == "BUSINESS" -> "Business Single Card"
            else -> "Card order"
        }
        view.findViewById<TextView>(R.id.order_type).text = "Category  $categoryLabel  •  Quantity  $quantity"
        view.findViewById<TextView>(R.id.card_progress).text = "Prepared  ${preparedCount.coerceAtMost(quantity)} of $quantity"

        val card = assignedCard
        val allocationContainer = view.findViewById<LinearLayout>(R.id.allocations_container)
        allocationContainer.removeAllViews()
        if (allocations.isNotEmpty()) {
            allocationContainer.visibility = View.VISIBLE
            allocations.forEach { allocation -> allocationContainer.addView(allocationView(allocation, quantity, customer, categoryLabel)) }
            view.findViewById<TextView>(R.id.card_empty).visibility = View.GONE
            view.findViewById<LinearLayout>(R.id.card_content).visibility = View.GONE
            prepare.visibility = View.GONE
            write.visibility = View.GONE
            activate.visibility = View.GONE
            printDesignActions.show(card ?: allocations.firstNotNullOfOrNull { it.card })
            message.visibility = View.GONE
            return@with
        }
        allocationContainer.visibility = View.GONE
        view.findViewById<TextView>(R.id.card_empty).visibility = if (card == null) View.VISIBLE else View.GONE
        view.findViewById<LinearLayout>(R.id.card_content).visibility = if (card == null) View.GONE else View.VISIBLE
        prepare.visibility = if (card == null) View.VISIBLE else View.GONE
        write.visibility = View.GONE
        activate.visibility = View.GONE

        if (card == null) {
            printDesignActions.show(null)
            message.visibility = View.GONE
            return@with
        }

        printDesignActions.show(card)

        view.findViewById<TextView>(R.id.card_uid).text = "Card UID  ${card.uid}"
        view.findViewById<TextView>(R.id.card_status).text = "Status  ${card.normalizedStatus}"
        view.findViewById<TextView>(R.id.public_url).text = "Public URL\n${card.publicUrl ?: "Not returned by server"}"

        when (card.normalizedStatus) {
            "ACTIVE" -> showInfo(message, "This NFC card is active and ready to use.", R.color.status_success)
            "PROGRAMMED" -> {
                showInfo(message, "The card is programmed. Complete the pending server activation.", R.color.text_secondary)
                activate.visibility = View.VISIBLE
                activate.setOnClickListener { viewModel.activateProgrammedCard(id, card.id) }
            }
            "ASSIGNED" -> if (card.publicUrl.isNullOrBlank()) {
                showInfo(message, "The server did not return a public URL. Refresh the order before writing.", R.color.status_error)
            } else {
                message.visibility = View.GONE
                write.visibility = View.VISIBLE
                write.setOnClickListener { navigator.writeCard(card.id, card.publicUrl, card.uid, customer, id, cardType = categoryLabel, representative = card.representative?.name.orEmpty(), currentStatus = card.status) }
            }
            else -> showInfo(message, "Card status: ${card.status}", R.color.text_secondary)
        }
    }

    private fun allocationView(allocation: CardAllocation, quantity: Int, customer: String, categoryLabel: String): View {
        val context = requireContext()
        val cardView = MaterialCardView(context).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(1).toFloat()
            strokeWidth = dp(1)
            setStrokeColor(android.graphics.Color.parseColor("#E3EAF2"))
            setCardBackgroundColor(context.getColor(R.color.surface_card))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        cardView.addView(content)
        val heading = TextView(context).apply {
            text = "Card ${allocation.position} of $quantity"
            setTextColor(context.getColor(R.color.text_primary))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        content.addView(heading)
        val representative = allocation.representative
        val representativeText = representative?.let {
            listOf(it.name, it.designation, it.phone, it.email).filter(String::isNotBlank).joinToString("  •  ")
        }?.takeIf(String::isNotBlank) ?: "Representative not assigned"
        content.addView(detailLine("Representative  $representativeText"))

        val assigned = allocation.card
        if (assigned == null) {
            content.addView(detailLine("Not prepared yet"))
            val prepare = MaterialButton(context).apply {
                text = if (preparingAllocationId == allocation.id) "Preparing…" else "Prepare this NFC card"
                isEnabled = preparingAllocationId != allocation.id
                setOnClickListener {
                    preparingAllocationId = allocation.id
                    isEnabled = false
                    viewModel.prepareAllocation(orderId, allocation.id)
                }
            }
            content.addView(prepare, matchWidthParams(top = 8))
        } else {
            content.addView(detailLine("Card UID  ${assigned.uid}"))
            content.addView(detailLine("Status  ${assigned.normalizedStatus}"))
            content.addView(detailLine("Public URL  ${assigned.publicUrl ?: "Not returned by server"}", selectable = true))
            when (assigned.normalizedStatus) {
                "ASSIGNED" -> {
                    if (assigned.canWrite) {
                        val write = MaterialButton(context).apply {
                            text = "Write NFC"
                            setOnClickListener {
                                navigator.writeCard(
                                    assigned.id, assigned.publicUrl.orEmpty(), assigned.uid, customer, orderId,
                                    cardType = categoryLabel,
                                    representative = allocation.representative?.name.orEmpty(),
                                    currentStatus = assigned.status,
                                )
                            }
                        }
                        content.addView(write, matchWidthParams(top = 8))
                    } else content.addView(detailLine("The server did not return a public URL; refresh before writing."))
                }
                "PROGRAMMED" -> {
                    content.addView(detailLine("Programmed; activation is pending."))
                    val activate = MaterialButton(context).apply {
                        text = "Activate this card"
                        setOnClickListener { viewModel.activateProgrammedCard(orderId, assigned.id) }
                    }
                    content.addView(activate, matchWidthParams(top = 8))
                }
                "ACTIVE" -> content.addView(detailLine("ACTIVE  •  Ready to use"))
            }
            val details = MaterialButton(context).apply {
                text = "Card details & PDF options"
                setOnClickListener { navigator.card(assigned.id, customer, categoryLabel, orderId) }
            }
            content.addView(details, matchWidthParams(top = 8))
        }
        return cardView
    }

    private fun detailLine(text: String, selectable: Boolean = false) = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f
        setTextColor(requireContext().getColor(R.color.text_secondary))
        setPadding(0, dp(6), 0, 0)
        if (selectable) setTextIsSelectable(true)
    }

    private fun matchWidthParams(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showInfo(view: TextView, text: String, color: Int) {
        view.visibility = View.VISIBLE
        view.text = text
        view.setTextColor(requireContext().getColor(color))
    }

    override fun onDestroyView() {
        printDesignActions.clear()
        super.onDestroyView()
    }

    companion object {
        private const val ARG_ID = "order_id"
        fun newInstance(id: Long) = OrderDetailFragment().apply {
            arguments = Bundle().apply { putLong(ARG_ID, id) }
        }
    }
}

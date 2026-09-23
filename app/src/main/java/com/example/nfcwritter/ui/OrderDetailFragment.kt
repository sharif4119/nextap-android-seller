package com.example.nfcwritter.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import com.example.nfcwritter.R
import com.example.nfcwritter.data.SellerOrder
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class OrderDetailFragment : BaseFragment(R.layout.fragment_order_detail) {
    private val viewModel: SellerViewModel by activityViewModels()
    private val orderId get() = requireArguments().getLong(ARG_ID)
    private var preparingCard = false
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
                    prepare.isEnabled = true
                    write.isEnabled = true
                    activate.isEnabled = true
                    refresh.isEnabled = true
                }
                is UiState.Data -> {
                    if (state.value.id != orderId) return@observe
                    progress.visibility = View.GONE
                    preparingCard = false
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

        val card = assignedCard
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
                write.setOnClickListener { navigator.writeCard(card.id, card.publicUrl, card.uid, customer) }
            }
            else -> showInfo(message, "Card status: ${card.status}", R.color.text_secondary)
        }
    }

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

package com.example.nfcwritter.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import com.example.nfcwritter.R
import com.google.android.material.button.MaterialButton

class OrderDetailFragment : BaseFragment(R.layout.fragment_order_detail) {
    private val viewModel: SellerViewModel by activityViewModels()
    private val orderId get() = requireArguments().getLong(ARG_ID)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.title).text = "Order #$orderId"
        val status = view.findViewById<TextView>(R.id.status)
        val assign = view.findViewById<MaterialButton>(R.id.assign_button)
        val write = view.findViewById<MaterialButton>(R.id.write_button)
        assign.setOnClickListener { navigator.availableCards() }
        viewModel.order.observe(viewLifecycleOwner) { state ->
            when (state) {
                UiState.Loading -> status.text = "Loading order…"
                is UiState.Error -> status.text = state.message
                is UiState.Data -> with(state.value) {
                    status.text = ""
                    view.findViewById<TextView>(R.id.customer).text = "Customer: $customer"
                    view.findViewById<TextView>(R.id.phone).text = "Phone: $phone"
                    view.findViewById<TextView>(R.id.address).text = "Address: $address"
                    view.findViewById<TextView>(R.id.product).text = "Product: $product"
                    view.findViewById<TextView>(R.id.payment).text = "Payment: $paymentStatus"
                    view.findViewById<TextView>(R.id.order_status).text = "Order status: $orderStatus"
                    view.findViewById<TextView>(R.id.assigned_card).text = assignedCard?.let { "Assigned card: #${it.id} (${it.status})" } ?: "Assigned card: None"
                    assign.visibility = if (assignedCard == null) View.VISIBLE else View.GONE
                    write.visibility = if (assignedCard != null) View.VISIBLE else View.GONE
                    write.setOnClickListener { assignedCard?.let { navigator.card(it.id) } }
                }
                UiState.Idle -> Unit
            }
        }
        viewModel.loadOrder(orderId)
    }

    companion object {
        private const val ARG_ID = "order_id"
        fun newInstance(id: Long) = OrderDetailFragment().apply { arguments = bundleOf(ARG_ID to id) }
    }
}

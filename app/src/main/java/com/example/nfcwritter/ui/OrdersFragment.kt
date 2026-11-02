package com.example.nfcwritter.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nfcwritter.R
import com.google.android.material.button.MaterialButton

class OrdersFragment : BaseFragment(R.layout.fragment_list) {
    private val viewModel: SellerViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.title).text = "Orders"
        val status = view.findViewById<TextView>(R.id.status)
        val adapter = RowAdapter(navigator::order)
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }
        view.findViewById<MaterialButton>(R.id.refresh_button).setOnClickListener { viewModel.loadOrders(force = true) }
        viewModel.orders.observe(viewLifecycleOwner) { state ->
            when (state) {
                UiState.Loading -> status.text = "Loading orders…"
                is UiState.Error -> status.text = state.message
                is UiState.Data -> {
                    status.text = if (state.value.isEmpty()) "No orders found." else "${state.value.size} orders"
                    adapter.submit(state.value.map {
                        RowItem(
                            it.id,
                            "Order #${it.id}",
                            "${it.customer}  •  ${it.product}\n${orderCategory(it)}  •  Qty ${it.quantity}  •  Price ${it.price}",
                            payment = it.paymentStatus,
                            orderStatus = it.orderStatus,
                            cardStatus = if (it.quantity > 1 || it.allocations.size > 1) {
                                "${it.preparedCount}/${it.quantity} prepared"
                            } else it.assignedCard?.status ?: "NFC not prepared",
                        )
                    })
                }
                UiState.Idle -> Unit
            }
        }
        viewModel.loadOrders()
    }

    private fun orderCategory(order: com.example.nfcwritter.data.SellerOrder): String = when {
        order.cardCategory == "PERSONAL" -> "Personal Card"
        order.cardCategory == "BUSINESS" && (order.businessOrderType == "MULTIPLE" || order.quantity > 1) -> "Business Multiple Cards"
        order.cardCategory == "BUSINESS" -> "Business Single Card"
        else -> "Card order"
    }
}

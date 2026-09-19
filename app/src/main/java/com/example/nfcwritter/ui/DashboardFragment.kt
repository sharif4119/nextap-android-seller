package com.example.nfcwritter.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import com.example.nfcwritter.R
import com.google.android.material.button.MaterialButton

class DashboardFragment : BaseFragment(R.layout.fragment_dashboard) {
    private val viewModel: SellerViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.orders_button).setOnClickListener { navigator.orders() }
        view.findViewById<MaterialButton>(R.id.refresh_button).setOnClickListener { viewModel.loadDashboard() }
        view.findViewById<MaterialButton>(R.id.logout_button).setOnClickListener { navigator.logout() }
        val status = view.findViewById<TextView>(R.id.status)
        viewModel.dashboard.observe(viewLifecycleOwner) { state ->
            when (state) {
                UiState.Loading -> status.text = "Loading dashboard…"
                is UiState.Error -> status.text = state.message
                is UiState.Data -> with(state.value) {
                    status.text = ""
                    view.findViewById<TextView>(R.id.pending_orders).text = "Pending orders\n$pendingOrders"
                    view.findViewById<TextView>(R.id.available_cards).text = "Available cards\n$availableCards"
                    view.findViewById<TextView>(R.id.assigned_cards).text = "Assigned cards\n$assignedCards"
                    view.findViewById<TextView>(R.id.programmed_cards).text = "Programmed cards\n$programmedCards"
                    view.findViewById<TextView>(R.id.active_cards).text = "Active cards\n$activeCards"
                }
                UiState.Idle -> Unit
            }
        }
        viewModel.loadDashboard()
    }
}

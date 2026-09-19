package com.example.nfcwritter.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nfcwritter.R
import com.google.android.material.button.MaterialButton

class AvailableCardsFragment : BaseFragment(R.layout.fragment_list) {
    private val viewModel: SellerViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.title).text = "Select an available card"
        val status = view.findViewById<TextView>(R.id.status)
        val adapter = RowAdapter { cardId -> viewModel.assignCard(cardId) }
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }
        view.findViewById<MaterialButton>(R.id.refresh_button).setOnClickListener { viewModel.loadAvailableCards() }
        viewModel.cards.observe(viewLifecycleOwner) { state ->
            when (state) {
                UiState.Loading -> status.text = "Loading cards…"
                is UiState.Error -> status.text = state.message
                is UiState.Data -> {
                    status.text = if (state.value.isEmpty()) "No available cards." else "Tap a card to assign it."
                    adapter.submit(state.value.map { RowItem(it.id, "Card #${it.id}", "UID: ${it.uid} • ${it.status}") })
                }
                UiState.Idle -> Unit
            }
        }
        viewModel.order.observe(viewLifecycleOwner) { state ->
            if (state is UiState.Data && state.value.assignedCard != null) navigator.card(state.value.assignedCard.id)
            if (state is UiState.Error) status.text = state.message
        }
        viewModel.loadAvailableCards()
    }
}

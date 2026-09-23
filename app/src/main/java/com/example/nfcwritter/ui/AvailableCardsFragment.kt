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
        view.findViewById<TextView>(R.id.title).text = "Cards"
        val status = view.findViewById<TextView>(R.id.status)
        val adapter = RowAdapter(navigator::card)
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }
        view.findViewById<MaterialButton>(R.id.refresh_button).setOnClickListener { viewModel.loadAvailableCards(force = true) }
        viewModel.cards.observe(viewLifecycleOwner) { state ->
            when (state) {
                UiState.Loading -> status.text = "Loading cards…"
                is UiState.Error -> status.text = state.message
                is UiState.Data -> {
                    status.text = if (state.value.isEmpty()) "No available or prepared cards." else "${state.value.size} cards ready for seller action"
                    adapter.submit(state.value.map { RowItem(it.id, "Card #${it.id}", "UID  ${it.uid}", cardStatus = it.status) })
                }
                UiState.Idle -> Unit
            }
        }
        viewModel.loadAvailableCards()
    }
}

package com.example.nfcwritter.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import com.example.nfcwritter.R
import com.google.android.material.button.MaterialButton

class CardDetailFragment : BaseFragment(R.layout.fragment_card_detail) {
    private val viewModel: SellerViewModel by activityViewModels()
    private val cardId get() = requireArguments().getLong(ARG_ID)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TextView>(R.id.title).text = "Card #$cardId"
        val message = view.findViewById<TextView>(R.id.status_message)
        val write = view.findViewById<MaterialButton>(R.id.write_button)
        viewModel.card.observe(viewLifecycleOwner) { state ->
            when (state) {
                UiState.Loading -> message.text = "Loading card…"
                is UiState.Error -> message.text = state.message
                is UiState.Data -> with(state.value) {
                    message.text = ""
                    view.findViewById<TextView>(R.id.uid).text = "UID: $uid"
                    view.findViewById<TextView>(R.id.card_status).text = "Status: $status"
                    view.findViewById<TextView>(R.id.public_url).text = "Public URL: ${publicUrl ?: "Not provided by server"}"
                    write.visibility = if (publicUrl.isNullOrBlank()) View.GONE else View.VISIBLE
                    write.setOnClickListener { publicUrl?.let { navigator.writeCard(id, it) } }
                }
                UiState.Idle -> Unit
            }
        }
        viewModel.loadCard(cardId)
    }

    companion object {
        private const val ARG_ID = "card_id"
        fun newInstance(id: Long) = CardDetailFragment().apply { arguments = bundleOf(ARG_ID to id) }
    }
}

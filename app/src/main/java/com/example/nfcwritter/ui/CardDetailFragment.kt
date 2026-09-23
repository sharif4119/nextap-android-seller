package com.example.nfcwritter.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import com.example.nfcwritter.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar

class CardDetailFragment : BaseFragment(R.layout.fragment_card_detail) {
    private val viewModel: SellerViewModel by activityViewModels()
    private val cardId get() = requireArguments().getLong(ARG_ID)
    private lateinit var printDesignActions: PrintDesignActions

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
        viewModel.card.observe(viewLifecycleOwner) { state ->
            when (state) {
                UiState.Loading -> message.text = "Loading card…"
                is UiState.Error -> message.text = state.message
                is UiState.Data -> with(state.value) {
                    printDesignActions.show(this)
                    message.text = when (normalizedStatus) {
                        "ACTIVE" -> "Active and ready to use"
                        "PROGRAMMED" -> "Programmed; activation pending"
                        "AVAILABLE" -> "Available for automatic order preparation"
                        else -> ""
                    }
                    message.setTextColor(requireContext().getColor(if (normalizedStatus == "ACTIVE") R.color.status_success else R.color.text_secondary))
                    view.findViewById<TextView>(R.id.uid).text = "UID: $uid"
                    view.findViewById<TextView>(R.id.card_status).text = "Status: $status"
                    view.findViewById<TextView>(R.id.public_url).text = "Public URL: ${publicUrl ?: "Not provided by server"}"
                    write.visibility = if (canWrite) View.VISIBLE else View.GONE
                    write.setOnClickListener { publicUrl?.let { navigator.writeCard(id, it, uid) } }
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
        fun newInstance(id: Long) = CardDetailFragment().apply {
            arguments = Bundle().apply { putLong(ARG_ID, id) }
        }
    }
}

package com.example.nfcwritter.ui

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import com.example.nfcwritter.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class LoginFragment : BaseFragment(R.layout.fragment_login) {
    private val viewModel: SellerViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val username = view.findViewById<TextInputEditText>(R.id.username)
        val password = view.findViewById<TextInputEditText>(R.id.password)
        val button = view.findViewById<MaterialButton>(R.id.login_button)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val error = view.findViewById<TextView>(R.id.error)
        button.setOnClickListener {
            val user = username.text?.toString()?.trim().orEmpty()
            val pass = password.text?.toString().orEmpty()
            if (user.isBlank() || pass.isBlank()) {
                error.text = "Enter both username and password."
                error.visibility = View.VISIBLE
            } else viewModel.login(user, pass)
        }
        viewModel.login.observe(viewLifecycleOwner) { state ->
            val loading = state is UiState.Loading
            progress.visibility = if (loading) View.VISIBLE else View.GONE
            button.isEnabled = !loading
            if (state is UiState.Error) {
                error.text = state.message
                error.visibility = View.VISIBLE
            } else if (state !is UiState.Idle) error.visibility = View.GONE
        }
    }
}

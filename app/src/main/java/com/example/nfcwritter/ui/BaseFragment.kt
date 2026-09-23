package com.example.nfcwritter.ui

import androidx.fragment.app.Fragment
import com.example.nfcwritter.AppNavigator
import com.google.android.material.appbar.MaterialToolbar

abstract class BaseFragment(layoutId: Int) : Fragment(layoutId) {
    protected val navigator: AppNavigator get() = requireActivity() as AppNavigator

    protected fun setupBack(toolbar: MaterialToolbar) {
        toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
    }
}

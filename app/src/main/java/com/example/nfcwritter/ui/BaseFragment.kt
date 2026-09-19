package com.example.nfcwritter.ui

import androidx.fragment.app.Fragment
import com.example.nfcwritter.AppNavigator

abstract class BaseFragment(layoutId: Int) : Fragment(layoutId) {
    protected val navigator: AppNavigator get() = requireActivity() as AppNavigator
}

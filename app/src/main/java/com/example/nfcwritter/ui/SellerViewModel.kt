package com.example.nfcwritter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.nfcwritter.data.ApiResult
import com.example.nfcwritter.data.DashboardStats
import com.example.nfcwritter.data.NfcCard
import com.example.nfcwritter.data.SellerOrder
import com.example.nfcwritter.data.SellerRepository
import com.example.nfcwritter.data.TokenStore
import kotlinx.coroutines.launch

class SellerViewModel(application: Application) : AndroidViewModel(application) {
    private val tokenStore = TokenStore(application)
    private val repository = SellerRepository.create(tokenStore)

    private val _authenticated = MutableLiveData<Boolean?>(null)
    val authenticated: LiveData<Boolean?> = _authenticated
    private val _login = MutableLiveData<UiState<String>>(UiState.Idle)
    val login: LiveData<UiState<String>> = _login
    private val _dashboard = MutableLiveData<UiState<DashboardStats>>(UiState.Idle)
    val dashboard: LiveData<UiState<DashboardStats>> = _dashboard
    private val _orders = MutableLiveData<UiState<List<SellerOrder>>>(UiState.Idle)
    val orders: LiveData<UiState<List<SellerOrder>>> = _orders
    private val _order = MutableLiveData<UiState<SellerOrder>>(UiState.Idle)
    val order: LiveData<UiState<SellerOrder>> = _order
    private val _cards = MutableLiveData<UiState<List<NfcCard>>>(UiState.Idle)
    val cards: LiveData<UiState<List<NfcCard>>> = _cards
    private val _card = MutableLiveData<UiState<NfcCard>>(UiState.Idle)
    val card: LiveData<UiState<NfcCard>> = _card
    private val _programming = MutableLiveData<UiState<Unit>>(UiState.Idle)
    val programming: LiveData<UiState<Unit>> = _programming

    var selectedOrderId: Long? = null
    var selectedCardId: Long? = null

    init { viewModelScope.launch { _authenticated.value = tokenStore.load() != null } }

    fun login(username: String, password: String) = launchInto(_login, { repository.login(username, password) }) {
        _authenticated.value = true
    }

    fun loadDashboard() = launchInto(_dashboard, repository::dashboard)
    fun loadOrders() = launchInto(_orders, repository::orders)
    fun loadOrder(id: Long) {
        selectedOrderId = id
        launchInto(_order, { repository.order(id) }) { selectedCardId = it.assignedCard?.id }
    }
    fun loadAvailableCards() = launchInto(_cards, repository::availableCards)
    fun loadCard(id: Long) {
        selectedCardId = id
        launchInto(_card, { repository.card(id) })
    }
    fun assignCard(cardId: Long) {
        val orderId = selectedOrderId ?: return
        selectedCardId = cardId
        launchInto(_order, { repository.assignCard(orderId, cardId) }) { selectedCardId = it.assignedCard?.id ?: cardId }
    }

    fun confirmProgrammedAndActivate(cardId: Long) {
        _programming.value = UiState.Loading
        viewModelScope.launch {
            when (val programmed = repository.markProgrammed(cardId)) {
                is ApiResult.Success -> when (val activated = repository.activate(cardId)) {
                    is ApiResult.Success -> _programming.value = UiState.Data(Unit)
                    is ApiResult.Error -> handleError(activated, _programming)
                }
                is ApiResult.Error -> handleError(programmed, _programming)
            }
        }
    }

    fun resetProgramming() { _programming.value = UiState.Idle }

    fun logout() = viewModelScope.launch {
        repository.logout()
        _authenticated.value = false
    }

    private fun <T> launchInto(
        target: MutableLiveData<UiState<T>>,
        call: suspend () -> ApiResult<T>,
        success: (T) -> Unit = {},
    ) {
        target.value = UiState.Loading
        viewModelScope.launch {
            when (val result = call()) {
                is ApiResult.Success -> {
                    success(result.data)
                    target.value = UiState.Data(result.data)
                }
                is ApiResult.Error -> handleError(result, target)
            }
        }
    }

    private fun <T> handleError(error: ApiResult.Error, target: MutableLiveData<UiState<T>>) {
        target.value = UiState.Error(error.message)
        if (error.unauthorized) viewModelScope.launch {
            repository.logout()
            _authenticated.value = false
        }
    }
}

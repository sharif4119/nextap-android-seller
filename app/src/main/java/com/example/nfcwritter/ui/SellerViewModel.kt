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
import com.example.nfcwritter.data.PrintDesignDocument
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

sealed interface ProgrammingState {
    data object Idle : ProgrammingState
    data object MarkingProgrammed : ProgrammingState
    data object Activating : ProgrammingState
    data object Success : ProgrammingState
    data class Error(val message: String) : ProgrammingState
}

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
    private val _programming = MutableLiveData<ProgrammingState>(ProgrammingState.Idle)
    val programming: LiveData<ProgrammingState> = _programming

    var selectedOrderId: Long? = null
    var selectedCardId: Long? = null
    private var dashboardJob: Job? = null
    private var ordersJob: Job? = null
    private var orderJob: Job? = null
    private var cardsJob: Job? = null
    private var cardJob: Job? = null
    private var orderRequestId: Long? = null
    private var cardRequestId: Long? = null
    private var programmedConfirmed = false

    init { viewModelScope.launch { _authenticated.value = tokenStore.load() != null } }

    fun login(username: String, password: String) = launchInto(_login, { repository.login(username, password) }) {
        _authenticated.value = true
    }

    fun loadDashboard(force: Boolean = false) {
        if (!force && (_dashboard.value is UiState.Loading || _dashboard.value is UiState.Data)) return
        dashboardJob?.cancel()
        dashboardJob = launchInto(_dashboard, repository::dashboard)
    }
    fun loadOrders(force: Boolean = false) {
        if (!force && (_orders.value is UiState.Loading || _orders.value is UiState.Data)) return
        ordersJob?.cancel()
        ordersJob = launchInto(_orders, repository::orders)
    }
    fun loadOrder(id: Long, force: Boolean = false) {
        val current = (_order.value as? UiState.Data)?.value
        if (!force && ((_order.value is UiState.Loading && orderRequestId == id) || current?.id == id)) return
        selectedOrderId = id
        orderRequestId = id
        orderJob?.cancel()
        orderJob = launchInto(_order, { repository.order(id) }) { selectedCardId = it.assignedCard?.id }
    }
    fun loadAvailableCards(force: Boolean = false) {
        if (!force && (_cards.value is UiState.Loading || _cards.value is UiState.Data)) return
        cardsJob?.cancel()
        cardsJob = launchInto(_cards, repository::availableCards)
    }
    fun prepareCard(orderId: Long) {
        if (_order.value is UiState.Loading) return
        selectedOrderId = orderId
        orderRequestId = orderId
        orderJob?.cancel()
        orderJob = launchInto(_order, { repository.prepareCard(orderId) }) { prepared ->
            selectedCardId = prepared.assignedCard?.id
            val orders = (_orders.value as? UiState.Data)?.value
            if (orders != null) _orders.value = UiState.Data(orders.map { if (it.id == prepared.id) prepared else it })
            prepared.assignedCard?.id?.let { cardId ->
                val cards = (_cards.value as? UiState.Data)?.value
                if (cards != null) _cards.value = UiState.Data(cards.filterNot { it.id == cardId })
            }
        }
    }
    fun loadCard(id: Long, force: Boolean = false) {
        val current = (_card.value as? UiState.Data)?.value
        if (!force && ((_card.value is UiState.Loading && cardRequestId == id) || current?.id == id)) return
        selectedCardId = id
        cardRequestId = id
        cardJob?.cancel()
        cardJob = launchInto(_card, { repository.card(id) })
    }
    fun assignCard(cardId: Long) {
        val orderId = selectedOrderId ?: return
        selectedCardId = cardId
        launchInto(_order, { repository.assignCard(orderId, cardId) }) { selectedCardId = it.assignedCard?.id ?: cardId }
    }

    fun confirmProgrammedAndActivate(cardId: Long) {
        if (_programming.value == ProgrammingState.MarkingProgrammed || _programming.value == ProgrammingState.Activating) return
        viewModelScope.launch {
            if (!programmedConfirmed) {
                _programming.value = ProgrammingState.MarkingProgrammed
                when (val programmed = repository.markProgrammed(cardId)) {
                    is ApiResult.Success -> programmedConfirmed = true
                    is ApiResult.Error -> {
                        programmingError(programmed)
                        return@launch
                    }
                }
            }
            _programming.value = ProgrammingState.Activating
            when (val activated = repository.activate(cardId)) {
                is ApiResult.Success -> {
                    val order = (_order.value as? UiState.Data)?.value
                    if (order?.assignedCard?.id == cardId) {
                        val active = order.assignedCard.copy(status = "ACTIVE")
                        _order.value = UiState.Data(order.copy(assignedCard = active))
                    }
                    _programming.value = ProgrammingState.Success
                }
                is ApiResult.Error -> programmingError(activated)
            }
        }
    }

    fun activateProgrammedCard(orderId: Long, cardId: Long) {
        if (_order.value is UiState.Loading) return
        selectedOrderId = orderId
        orderRequestId = orderId
        _order.value = UiState.Loading
        orderJob?.cancel()
        orderJob = viewModelScope.launch {
            when (val activated = repository.activate(cardId)) {
                is ApiResult.Error -> handleError(activated, _order)
                is ApiResult.Success -> when (val refreshed = repository.order(orderId)) {
                    is ApiResult.Error -> handleError(refreshed, _order)
                    is ApiResult.Success -> _order.value = UiState.Data(refreshed.data)
                }
            }
        }
    }

    fun resetProgramming() {
        programmedConfirmed = false
        _programming.value = ProgrammingState.Idle
    }

    suspend fun downloadPrintDesign(card: NfcCard): ApiResult<PrintDesignDocument> {
        val result = repository.printDesign(card)
        if (result is ApiResult.Error && result.unauthorized) {
            repository.logout()
            _authenticated.value = false
        }
        return result
    }

    fun logout() = viewModelScope.launch {
        repository.logout()
        _authenticated.value = false
    }

    private fun <T> launchInto(
        target: MutableLiveData<UiState<T>>,
        call: suspend () -> ApiResult<T>,
        success: (T) -> Unit = {},
    ): Job {
        target.value = UiState.Loading
        return viewModelScope.launch {
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

    private fun programmingError(error: ApiResult.Error) {
        _programming.value = ProgrammingState.Error(error.message)
        if (error.unauthorized) viewModelScope.launch {
            repository.logout()
            _authenticated.value = false
        }
    }
}

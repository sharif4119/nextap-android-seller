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
    private val _sellerAccount = MutableLiveData<String?>(null)
    val sellerAccount: LiveData<String?> = _sellerAccount
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
    private var programmingCardId: Long? = null

    init {
        viewModelScope.launch {
            val authenticated = tokenStore.load() != null
            _sellerAccount.value = tokenStore.cachedUsername.takeIf { authenticated }
            _authenticated.value = authenticated
        }
    }

    fun login(username: String, password: String) = launchInto(_login, { repository.login(username, password) }) {
        _sellerAccount.value = it
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
    fun prepareAllocation(orderId: Long, allocationId: Long) {
        if (_order.value is UiState.Loading) return
        selectedOrderId = orderId
        orderRequestId = orderId
        orderJob?.cancel()
        orderJob = launchInto(_order, { repository.prepareAllocation(orderId, allocationId) }) { prepared ->
            selectedCardId = prepared.allocations.firstOrNull { it.id == allocationId }?.card?.id
                ?: prepared.assignedCard?.id
            val orders = (_orders.value as? UiState.Data)?.value
            if (orders != null) _orders.value = UiState.Data(orders.map { if (it.id == prepared.id) prepared else it })
            val preparedIds = prepared.allocations.mapNotNull { it.card?.id }.toSet()
            if (preparedIds.isNotEmpty()) {
                val cards = (_cards.value as? UiState.Data)?.value
                if (cards != null) _cards.value = UiState.Data(cards.filterNot { it.id in preparedIds })
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

    fun confirmProgrammedAndActivate(cardId: Long, orderId: Long? = null) {
        if (programmingCardId != null) return
        programmingCardId = cardId
        viewModelScope.launch {
            when (val current = repository.card(cardId)) {
                is ApiResult.Error -> {
                    programmingError(current)
                    programmingCardId = null
                    return@launch
                }
                is ApiResult.Success -> when (current.data.normalizedStatus) {
                    "ACTIVE" -> {
                        refreshCardAfterCardChange(cardId)
                        refreshOrderAfterCardChange(orderId)
                        _programming.value = ProgrammingState.Success
                        programmingCardId = null
                        return@launch
                    }
                    "PROGRAMMED" -> Unit
                    "ASSIGNED" -> {
                        _programming.value = ProgrammingState.MarkingProgrammed
                        when (val programmed = repository.markProgrammed(cardId)) {
                            is ApiResult.Error -> {
                                programmingError(programmed)
                                programmingCardId = null
                                return@launch
                            }
                            is ApiResult.Success -> Unit
                        }
                    }
                    else -> {
                        programmingError(ApiResult.Error("This card is not in a programmable status."))
                        programmingCardId = null
                        return@launch
                    }
                }
            }
            _programming.value = ProgrammingState.Activating
            when (val activated = repository.activate(cardId)) {
                is ApiResult.Success -> {
                    refreshCardAfterCardChange(cardId)
                    refreshOrderAfterCardChange(orderId)
                    _programming.value = ProgrammingState.Success
                }
                is ApiResult.Error -> programmingError(activated)
            }
            programmingCardId = null
        }
    }

    private suspend fun refreshOrderAfterCardChange(orderId: Long?) {
        if (orderId == null) return
        when (val refreshed = repository.order(orderId)) {
            is ApiResult.Success -> {
                _order.value = UiState.Data(refreshed.data)
                val orders = (_orders.value as? UiState.Data)?.value
                if (orders != null) _orders.value = UiState.Data(orders.map { if (it.id == orderId) refreshed.data else it })
            }
            is ApiResult.Error -> if (refreshed.unauthorized) programmingError(refreshed)
        }
    }

    private suspend fun refreshCardAfterCardChange(cardId: Long) {
        when (val refreshed = repository.card(cardId)) {
            is ApiResult.Success -> {
                _card.value = UiState.Data(refreshed.data)
                cardRequestId = cardId
            }
            is ApiResult.Error -> if (refreshed.unauthorized) programmingError(refreshed)
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
        programmingCardId = null
        _programming.value = ProgrammingState.Idle
    }

    suspend fun downloadPrintDesign(card: NfcCard): ApiResult<PrintDesignDocument> {
        val result = repository.printDesign(card)
        if (result is ApiResult.Error && result.unauthorized) {
            expireSession()
        }
        return result
    }

    fun logout() = viewModelScope.launch {
        repository.logout()
        _sellerAccount.value = null
        _authenticated.value = false
    }

    fun expireSession() = viewModelScope.launch {
        repository.logout()
        _sellerAccount.value = null
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
            _sellerAccount.value = null
            _authenticated.value = false
        }
    }

    private fun programmingError(error: ApiResult.Error) {
        _programming.value = ProgrammingState.Error(error.message)
        if (error.unauthorized) viewModelScope.launch {
            repository.logout()
            _sellerAccount.value = null
            _authenticated.value = false
        }
    }
}

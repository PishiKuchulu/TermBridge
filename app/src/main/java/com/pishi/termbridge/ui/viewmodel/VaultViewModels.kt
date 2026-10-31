package com.pishi.termbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.data.TrustedHostKeyEntity
import com.pishi.termbridge.domain.SubnetPlanner
import com.pishi.termbridge.settings.ConnectionLogStore
import com.pishi.termbridge.transport.DiscoveredDevice
import com.pishi.termbridge.transport.LocalNetworkScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class KnownHostsViewModel(private val repository: HostRepository) : ViewModel() {
    val keys: StateFlow<List<TrustedHostKeyEntity>> =
        repository.observeTrustedKeys()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun forget(endpoint: String) {
        viewModelScope.launch { repository.forgetTrustedKey(endpoint) }
    }
}

class KnownHostsViewModelFactory(private val repository: HostRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = KnownHostsViewModel(repository) as T
}

class LogsViewModel(private val store: ConnectionLogStore) : ViewModel() {
    val entries = store.entries
    fun clear() = store.clear()
}

class LogsViewModelFactory(private val store: ConnectionLogStore) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LogsViewModel(store) as T
}

data class DiscoverUiState(
    val scanning: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val devices: List<DiscoveredDevice> = emptyList(),
    val finished: Boolean = false,
    val noNetwork: Boolean = false
)

class DiscoverViewModel : ViewModel() {
    private val scanner = LocalNetworkScanner()
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()
    private var job: Job? = null

    /** [own] is the phone's IPv4 address and prefix length, or null with no network. */
    fun scan(own: Pair<String, Int>?) {
        job?.cancel()
        val addresses = own?.let { SubnetPlanner.hostsToScan(it.first, it.second) }.orEmpty()
        if (addresses.isEmpty()) {
            _state.value = DiscoverUiState(noNetwork = true, finished = true)
            return
        }
        val ports = listOf(22, 5900, 3389)
        _state.value = DiscoverUiState(scanning = true, total = addresses.size * ports.size)
        job = viewModelScope.launch {
            scanner.scan(
                addresses = addresses,
                ports = ports,
                onProgress = { done, total ->
                    _state.update { it.copy(done = done, total = total) }
                },
                onFound = { address, port ->
                    _state.update { current ->
                        val existing = current.devices.firstOrNull { it.address == address }
                        val merged = DiscoveredDevice(address, (existing?.ports ?: emptySet()) + port)
                        current.copy(
                            devices = (current.devices.filter { it.address != address } + merged)
                                .sortedBy { d -> d.address.split('.').map { it.toInt() }.fold(0L) { a, b -> a * 256 + b } }
                        )
                    }
                }
            )
            _state.update { it.copy(scanning = false, finished = true) }
        }
    }

    override fun onCleared() {
        job?.cancel()
    }
}

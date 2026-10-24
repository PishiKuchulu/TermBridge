package com.pishi.termbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.data.HostRepositoryException
import com.pishi.termbridge.domain.HostProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Hosts list state. The list comes from Room via the repository;
 * there is no sample or seed data — an empty database shows the
 * empty state, honestly.
 */
class HostsViewModel(private val repository: HostRepository) : ViewModel {
    val hosts: StateFlow<List<HostProfile>> = repository.getHosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    fun deleteHost(id: Long) {
        viewModelScope.launch {
            try {
                repository.deleteHost(id)
            } catch (e: Exception) {
                // Generic message only — repository messages never
                // contain a credential, and neither does this one.
                _error.value = "Could not delete the host. Please try again."
            }
        }
    }

    /** Immediate duplicate: non-secret fields only, no credential.
     *  The copy's name gains a "(copy)" suffix so it is distinguishable. */
    fun duplicateHost(id: Long) {
        viewModelScope.launch {
            try {
                val source = repository.getHost(id) ?: return@launch
                repository.duplicateHost(id, "${source.name} (copy)")
            } catch (e: HostRepositoryException) {
                _error.value = "Could not duplicate the host. Please try again."
            }
        }
    }
}

class HostsViewModelFactory(private val repository: HostRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HostsViewModel::class.java)) {
            return HostsViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

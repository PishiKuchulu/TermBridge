package com.pishi.termbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.data.HostRepositoryException
import com.pishi.termbridge.domain.HostListLogic
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.HostSort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Hosts list state. The list comes from Room via the repository;
 * there is no sample or seed data — an empty database shows the
 * empty state, honestly.
 */
class HostsViewModel(private val repository: HostRepository) : ViewModel() {
    val hosts: StateFlow<List<HostProfile>> = repository.getHosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    // Item T-22: list organization state. All rules live in
    // HostListLogic (pure, unit-tested); this ViewModel only
    // holds the state and exposes the derived sections.
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sort = MutableStateFlow(HostSort.NAME)
    val sort: StateFlow<HostSort> = _sort.asStateFlow()

    private val _tagFilter = MutableStateFlow<String?>(null)
    val tagFilter: StateFlow<String?> = _tagFilter.asStateFlow()

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    /** Every tag in use, for the filter chips. */
    val tags: StateFlow<List<String>> = hosts
        .map { HostListLogic.allTags(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The list as shown: filtered, sorted, grouped. */
    val sections: StateFlow<List<Pair<String, List<HostProfile>>>> =
        combine(hosts, _query, _sort, _tagFilter) { all, q, s, tag ->
            HostListLogic.sections(all, q, tag, s)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setSort(value: HostSort) {
        _sort.value = value
    }

    fun setTagFilter(tag: String?) {
        _tagFilter.value = tag
    }

    fun toggleFavorite(host: HostProfile) {
        viewModelScope.launch {
            try {
                repository.setFavorite(host.id, !host.favorite)
            } catch (e: Exception) {
                _error.value = "Could not update the favorite. Please try again."
            }
        }
    }

    fun toggleSelected(id: Long) {
        _selection.value = if (id in _selection.value) {
            _selection.value - id
        } else {
            _selection.value + id
        }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /** Multi-select delete (item T-22): each host goes through
     *  the same repository delete as a single delete, so
     *  credentials and scoped data are cleaned up identically. */
    fun deleteSelected() {
        val ids = _selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var failed = false
            for (id in ids) {
                try {
                    repository.deleteHost(id)
                } catch (e: Exception) {
                    failed = true
                }
            }
            _selection.value = emptySet()
            if (failed) {
                _error.value = "Some hosts could not be deleted. Please try again."
            }
        }
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

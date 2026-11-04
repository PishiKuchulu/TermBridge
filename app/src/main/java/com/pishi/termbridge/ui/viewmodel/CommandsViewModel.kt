package com.pishi.termbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.data.CommandRepository
import com.pishi.termbridge.data.CommandRepositoryException
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.HostProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Prompt 08 management ViewModel: CRUD, duplicate, and reorder for
 * one selected host's buttons. Execution lives in SessionViewModel
 * (the live terminal), not here — this ViewModel never sends
 * anything to a shell, so managing buttons can never accidentally
 * run one. That separation is the point of the two ViewModels.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandsViewModel(
    private val hostRepository: HostRepository,
    private val commandRepository: CommandRepository,
    private val snippetRepository: com.pishi.termbridge.data.SnippetRepository? = null
) : ViewModel() {
    val hosts: StateFlow<List<HostProfile>> = hostRepository.getHosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedHostId = MutableStateFlow<Long?>(null)
    val selectedHostId: StateFlow<Long?> = _selectedHostId.asStateFlow()

    /** True while the global-snippets pseudo-host is selected (item T-02). */
    val isGlobalSelected: Boolean
        get() = _selectedHostId.value == com.pishi.termbridge.data.GLOBAL_HOST_ID

    val buttons: StateFlow<List<CommandButton>> = _selectedHostId
        .flatMapLatest { hostId ->
            when {
                hostId == null -> emptyFlow()
                hostId == com.pishi.termbridge.data.GLOBAL_HOST_ID ->
                    snippetRepository?.getSnippets() ?: emptyFlow()
                else -> commandRepository.getButtons(hostId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Button being created/edited in the form dialog, null = closed.
     *  Edit loads the FULL button (steps + variables); the list
     *  items are shallow and must never be edited directly. */
    private val _formButton = MutableStateFlow<CommandButton?>(null)
    val formButton: StateFlow<CommandButton?> = _formButton.asStateFlow()

    fun startAdd() {
        val hostId = _selectedHostId.value ?: return
        // Prompt 14: opening a fresh form clears a stale error,
        // so the dialog never shows a previous save's failure.
        _error.value = null
        _formButton.value = CommandButton(hostId = hostId, label = "")
    }

    fun startEdit(id: Long) {
        _error.value = null
        viewModelScope.launch {
            _formButton.value = if (isGlobalSelected) {
                snippetRepository?.getSnippet(id)
            } else {
                commandRepository.getButton(id)
            }
        }
    }

    fun closeForm() {
        _formButton.value = null
    }

    fun selectHost(id: Long) {
        _selectedHostId.value = id
        _error.value = null
    }

    fun clearError() {
        _error.value = null
    }

    fun saveButton(button: CommandButton) {
        viewModelScope.launch {
            try {
                if (button.hostId == com.pishi.termbridge.data.GLOBAL_HOST_ID) {
                    snippetRepository?.saveSnippet(button)
                } else {
                    commandRepository.saveButton(button)
                }
                _error.value = null
                _formButton.value = null
            } catch (e: CommandRepositoryException) {
                _error.value = e.message
            } catch (e: Exception) {
                _error.value = "Could not save the button. Please try again."
            }
        }
    }

    fun deleteButton(id: Long) {
        viewModelScope.launch {
            try {
                if (isGlobalSelected) {
                    snippetRepository?.deleteSnippet(id)
                } else {
                    commandRepository.deleteButton(id)
                }
            } catch (e: Exception) {
                _error.value = "Could not delete the button. Please try again."
            }
        }
    }

    fun duplicateButton(id: Long) {
        viewModelScope.launch {
            try {
                if (isGlobalSelected) {
                    snippetRepository?.duplicateSnippet(id)
                } else {
                    commandRepository.duplicateButton(id)
                }
            } catch (e: Exception) {
                _error.value = "Could not duplicate the button. Please try again."
            }
        }
    }

    fun moveButton(id: Long, up: Boolean) {
        viewModelScope.launch {
            try {
                if (isGlobalSelected) {
                    snippetRepository?.moveSnippet(id, up)
                } else {
                    commandRepository.moveButton(id, up)
                }
            } catch (e: Exception) {
                _error.value = "Could not reorder the button. Please try again."
            }
        }
    }
}

class CommandsViewModelFactory(
    private val hostRepository: HostRepository,
    private val commandRepository: CommandRepository,
    private val snippetRepository: com.pishi.termbridge.data.SnippetRepository? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CommandsViewModel::class.java)) {
            return CommandsViewModel(hostRepository, commandRepository, snippetRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

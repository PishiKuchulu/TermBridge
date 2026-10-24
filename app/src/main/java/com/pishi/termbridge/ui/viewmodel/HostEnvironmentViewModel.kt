package com.pishi.termbridge.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.data.HostEnvironmentException
import com.pishi.termbridge.data.HostEnvironmentRepository
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.domain.EnvVarInfo
import com.pishi.termbridge.domain.EnvironmentValidator
import com.pishi.termbridge.domain.HostEnvironmentSettings
import com.pishi.termbridge.domain.RemoteShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Prompt 09 per-host environment screen state.
 *
 * Values are WRITE-ONLY here, by design: the screen never loads a
 * stored value into a field (there is no API for that in the
 * repository except shell startup). Adding or editing a var
 * always requires typing the value again, and the list shows a
 * masked marker instead of any value. The ViewModel holds the
 * typed value only as a Compose String until save, then clears it.
 */
class HostEnvironmentViewModel(
    private val environmentRepository: HostEnvironmentRepository,
    private val hostRepository: HostRepository,
    private val hostId: Long
) : ViewModel() {
    val settings: StateFlow<HostEnvironmentSettings?> =
        environmentRepository.getSettings(hostId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val envVars: StateFlow<List<EnvVarInfo>> =
        environmentRepository.getEnvVarNames(hostId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var hostName by mutableStateOf("")
        private set

    var remoteWorkingDirectory by mutableStateOf("")
    var remoteShell by mutableStateOf(RemoteShell.POSIX)
    var localDirectoryUri by mutableStateOf<String?>(null)

    var settingsErrors by mutableStateOf<Map<String, String>>(emptyMap())
    var settingsSaved by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
    var isSaving by mutableStateOf(false)

    private var settingsLoaded = false

    init {
        viewModelScope.launch {
            hostRepository.getHost(hostId)?.let { hostName = it.name }
        }
        // Prefill the form once from stored settings; after that,
        // the form is the user's, not the database's, until save.
        viewModelScope.launch {
            environmentRepository.getSettings(hostId).collect { stored ->
                if (!settingsLoaded && stored != null) {
                    remoteWorkingDirectory = stored.remoteWorkingDirectory
                    remoteShell = stored.remoteShell
                    localDirectoryUri = stored.localDirectoryUri
                    settingsLoaded = true
                }
            }
        }
    }

    fun saveSettings() {
        // Prompt 18 (audit-17 R1): saving this screen IS the
        // confirmation — the shell picker is displayed and
        // editable here, so whatever value is saved has been
        // presented to the user. Imported settings and rows from
        // before this flag keep shellConfirmed = false.
        val candidate = HostEnvironmentSettings(
            hostId = hostId,
            remoteWorkingDirectory = remoteWorkingDirectory.trim(),
            localDirectoryUri = localDirectoryUri,
            remoteShell = remoteShell,
            shellConfirmed = true
        )
        val issues = mutableListOf<String>()
        val errors = mutableMapOf<String, String>()
        EnvironmentValidator.validateRemoteDirectory(
            candidate.remoteWorkingDirectory,
            candidate.remoteShell
        )?.let {
            errors[it.field] = it.message
            issues += it.message
        }
        EnvironmentValidator.validateLocalDirectoryUri(candidate.localDirectoryUri)?.let {
            errors[it.field] = it.message
            issues += it.message
        }
        settingsErrors = errors
        if (issues.isNotEmpty()) return
        settingsSaved = false
        saveError = null
        isSaving = true
        viewModelScope.launch {
            try {
                environmentRepository.saveSettings(candidate)
                settingsSaved = true
                settingsLoaded = true
            } catch (e: HostEnvironmentException) {
                saveError = e.message
            } catch (_: Exception) {
                saveError = "Could not save the settings. Please try again."
            } finally {
                isSaving = false
            }
        }
    }

    /** Called after the folder picker returns a SAF tree URI that
     *  the screen has already persistently permitted. Saving the
     *  URI alone (without the permission) would be a dead setting,
     *  so the screen owns the permission step and this only stores. */
    fun setLocalDirectory(uri: String) {
        localDirectoryUri = uri
        settingsSaved = false
    }

    fun clearLocalDirectory() {
        localDirectoryUri = null
        settingsSaved = false
    }

    /** Save one var. Returns an error message, or null on success.
     *  The caller clears the dialog fields on success. */
    suspend fun saveEnvVar(id: Long, name: String, value: String): String? {
        return try {
            environmentRepository.saveEnvVar(hostId, id, name, value.toCharArray())
            null
        } catch (e: HostEnvironmentException) {
            e.message
        } catch (_: Exception) {
            "Could not save the variable. Please try again."
        }
    }

    fun deleteEnvVar(id: Long) {
        viewModelScope.launch {
            try {
                environmentRepository.deleteEnvVar(id, hostId)
            } catch (_: Exception) {
                saveError = "Could not delete the variable. Please try again."
            }
        }
    }
}

class HostEnvironmentViewModelFactory(
    private val environmentRepository: HostEnvironmentRepository,
    private val hostRepository: HostRepository,
    private val hostId: Long
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HostEnvironmentViewModel::class.java)) {
            return HostEnvironmentViewModel(environmentRepository, hostRepository, hostId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

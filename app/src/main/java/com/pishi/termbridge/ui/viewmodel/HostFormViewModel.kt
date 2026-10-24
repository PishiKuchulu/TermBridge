package com.pishi.termbridge.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.data.HostRepositoryException
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.HostValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Add/edit host form state.
 *
 * Compose text fields hold Strings — that is a platform limitation,
 * not a design choice. The ViewModel converts password/passphrase
 * to wipeable CharArrays only at save time, hands them to the
 * repository (which wipes them), and then clears the field strings.
 * A stored passphrase is never loaded back into this form: for an
 * encrypted private key, the passphrase field is always blank on
 * edit, meaning "keep the stored key, ask for the passphrase again
 * at connection time".
 */
class HostFormViewModel(
    private val repository: HostRepository,
    private val hostId: Long?
) : ViewModel {
    var name by mutableStateOf("")
    var hostname by mutableStateOf("")
    var portText by mutableStateOf("22")
    var username by mutableStateOf("")
    var authType by mutableStateOf(AuthType.PASSWORD)
    var notes by mutableStateOf("")
    var tags by mutableStateOf("")
    var password by mutableStateOf("")
    var keyPem by mutableStateOf("")
    var passphrase by mutableStateOf("")

    var errors by mutableStateOf<Map<String, String>>(emptyMap())
    var isSaving by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    private var hasExistingCredential = false
    private var originalAuthType: AuthType? = null

    init {
        if (hostId != null) {
            viewModelScope.launch {
                val profile = repository.getHost(hostId) ?: return@launch
                name = profile.name
                hostname = profile.hostname
                portText = profile.port.toString()
                username = profile.username
                authType = profile.authType
                originalAuthType = profile.authType
                notes = profile.notes
                tags = profile.tags
                hasExistingCredential = repository.hasCredential(hostId)
            }
        }
    }

    fun save() {
        val port = portText.toIntOrNull()
        val profile = HostProfile(
            id = hostId ?: 0L,
            name = name,
            hostname = hostname,
            port = port ?: 0,
            username = username,
            authType = authType,
            notes = notes,
            tags = tags
        )
        // A stored credential is only reusable when the auth type
        // has not changed; otherwise a password blob would be kept
        // under a key auth type (or vice versa).
        val usableExisting = hasExistingCredential && originalAuthType == authType
        val credential: CredentialInput? = when (authType) {
            AuthType.PASSWORD -> when {
                password.isNotEmpty() -> CredentialInput.Password(password.toCharArray())
                usableExisting -> null
                else -> CredentialInput.Password(charArrayOf())
            }
            AuthType.PRIVATE_KEY -> when {
                keyPem.isNotEmpty() -> CredentialInput.PrivateKey(
                    keyPem,
                    if (passphrase.isEmpty()) null else passphrase.toCharArray()
                )
                usableExisting -> null
                else -> CredentialInput.PrivateKey("", null)
            }
        }
        val issues = HostValidator.validate(profile) +
            HostValidator.validateCredential(authType, credential, usableExisting)
        val portIssue = if (port == null) {
            listOf("port" to "Port must be a number.")
        } else {
            emptyList()
        }
        val allIssues = issues.map { it.field to it.message } + portIssue
        if (allIssues.isNotEmpty()) {
            errors = allIssues.toMap()
            return
        }
        errors = emptyMap()
        saveError = null
        isSaving = true
        viewModelScope.launch {
            try {
                repository.saveHost(profile, credential)
                // Clear secret field strings now that they are saved
                // (or, for edit-without-credential, were never set).
                password = ""
                keyPem = ""
                passphrase = ""
                _saved.value = true
            } catch (e: HostRepositoryException) {
                saveError = e.message
            } catch (e: Exception) {
                saveError = "Could not save the host. Please try again."
            } finally {
                isSaving = false
            }
        }
    }
}

class HostFormViewModelFactory(
    private val repository: HostRepository,
    private val hostId: Long?
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HostFormViewModel::class.java)) {
            return HostFormViewModel(repository, hostId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

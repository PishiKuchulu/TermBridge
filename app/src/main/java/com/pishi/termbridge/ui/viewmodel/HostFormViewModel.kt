package com.pishi.termbridge.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.data.HostRepositoryException
import com.pishi.termbridge.data.KeychainRepository
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.HostValidator
import com.pishi.termbridge.domain.KeychainEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
    private val hostId: Long?,
    private val keychainRepository: KeychainRepository? = null
) : ViewModel() {
    var name by mutableStateOf("")
    var hostname by mutableStateOf("")
    var portText by mutableStateOf("22")
    var username by mutableStateOf("")
    var authType by mutableStateOf(AuthType.PASSWORD)
    var notes by mutableStateOf("")
    var tags by mutableStateOf("")
    // Item T-22: list organization, edited here and toggled
    // from the list's star button.
    var groupName by mutableStateOf("")
    var favorite by mutableStateOf(false)
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
    /** True when the edited host's credential comes from a
     *  keychain link: there is no inline blob to fall back on
     *  if the user switches back to an inline credential
     *  without typing one. */
    private var wasLinked = false

    // Item T-01: credential source. When the keychain is
    // available the form can link the host to an entry instead
    // of storing a credential inline; the repository validates
    // the link (entry exists, kind matches, never both).
    val keychainAvailable: Boolean get() = keychainRepository != null
    val keychainEntries: StateFlow<List<KeychainEntry>> =
        keychainRepository?.observeEntries()
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
            ?: MutableStateFlow(emptyList())
    var useKeychain by mutableStateOf(false)
    var selectedKeychainEntryId by mutableStateOf<Long?>(null)

    // Audit #3 (A-17): the values the form was loaded with (or
    // the new-host defaults / prefill), so the screen can warn
    // before Back throws edits away. Null while an edited host
    // is still loading — the guard stays off until then.
    private data class FormBaseline(
        val name: String,
        val hostname: String,
        val portText: String,
        val username: String,
        val authType: AuthType,
        val notes: String,
        val tags: String,
        val groupName: String,
        val favorite: Boolean,
        val secretSnapshot: String,
        val keyPem: String,
        val secretCharsSnapshot: String,
        val useKeychain: Boolean,
        val selectedKeychainEntryId: Long?
    )

    private var baseline: FormBaseline? = null

    private fun captureBaseline() {
        baseline = FormBaseline(
            name = name,
            hostname = hostname,
            portText = portText,
            username = username,
            authType = authType,
            notes = notes,
            tags = tags,
            groupName = groupName,
            favorite = favorite,
            secretSnapshot = password,
            keyPem = keyPem,
            secretCharsSnapshot = passphrase,
            useKeychain = useKeychain,
            selectedKeychainEntryId = selectedKeychainEntryId
        )
    }

    /** True when any field differs from the loaded baseline. */
    val isDirty: Boolean
        get() {
            val base = baseline ?: return false
            return name != base.name ||
                hostname != base.hostname ||
                portText != base.portText ||
                username != base.username ||
                authType != base.authType ||
                notes != base.notes ||
                tags != base.tags ||
                groupName != base.groupName ||
                favorite != base.favorite ||
                password != base.secretSnapshot ||
                keyPem != base.keyPem ||
                passphrase != base.secretCharsSnapshot ||
                useKeychain != base.useKeychain ||
                selectedKeychainEntryId != base.selectedKeychainEntryId
        }

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
                groupName = profile.groupName
                favorite = profile.favorite
                hasExistingCredential = repository.hasCredential(hostId)
                if (profile.keychainEntryId != null) {
                    useKeychain = true
                    selectedKeychainEntryId = profile.keychainEntryId
                    wasLinked = true
                }
                captureBaseline()
            }
        } else {
            captureBaseline()
        }
    }

    /** Pre-fill from quick connect / discovery. Only touches empty new-host fields. */
    fun applyPrefill(host: String, port: Int, user: String?) {
        if (hostId != null) return
        if (hostname.isBlank()) hostname = host
        if (name.isBlank()) name = host
        portText = port.toString()
        if (!user.isNullOrBlank() && username.isBlank()) username = user
        // A prefill is the starting point, not a user edit.
        captureBaseline()
    }

    fun save() {
        val port = portText.toIntOrNull()
        if (useKeychain && selectedKeychainEntryId == null) {
            errors = mapOf(
                "keychainEntry" to "Choose a keychain entry, or use a stored credential."
            )
            return
        }
        val profile = HostProfile(
            id = hostId ?: 0L,
            name = name,
            hostname = hostname,
            port = port ?: 0,
            username = username,
            authType = authType,
            notes = notes,
            tags = tags,
            keychainEntryId = if (useKeychain) selectedKeychainEntryId else null,
            favorite = favorite,
            groupName = groupName
        )
        // A stored credential is only reusable when the auth type
        // has not changed; otherwise a password blob would be kept
        // under a key auth type (or vice versa). A keychain link
        // is not an inline credential: unlinking without typing a
        // new credential must fail validation, not silently save
        // a credential-less host.
        val usableExisting = hasExistingCredential && originalAuthType == authType &&
            !(wasLinked && !useKeychain)
        val credential: CredentialInput? = if (useKeychain) {
            // The credential comes from the entry; the
            // repository enforces the link rules on save.
            null
        } else {
            when (authType) {
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
        }
        // With a keychain link chosen, the "credential required"
        // check is satisfied by the link — the repository
        // re-validates it (exists, kind matches) on save.
        val issues = HostValidator.validate(profile) +
            HostValidator.validateCredential(
                authType, credential, usableExisting || useKeychain
            )
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
    private val hostId: Long?,
    private val keychainRepository: KeychainRepository? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HostFormViewModel::class.java)) {
            return HostFormViewModel(repository, hostId, keychainRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

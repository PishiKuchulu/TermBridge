package com.pishi.termbridge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.data.KeychainRepository
import com.pishi.termbridge.data.KeychainRepositoryException
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.CredentialInput
import com.pishi.termbridge.domain.KeyFileParse
import com.pishi.termbridge.domain.KeyFileParser
import com.pishi.termbridge.domain.KeychainEntry
import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.security.KeyGeneration
import com.pishi.termbridge.security.KeyGenerationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keychain screen state (item T-01) and the credential-file
 * import state machine (item F-01).
 *
 * Import flow: the screen reads the picked file's bytes
 * (bounded, via SAF) and hands them here. The parser
 * classifies them; a private key is then PROVEN by a real
 * sshj load ([KeyGeneration.describe]) and the user sees the
 * fingerprint before anything is saved. An encrypted key asks
 * for its passphrase first — the passphrase is used for the
 * load proof only and is never stored, exactly like a key
 * passphrase everywhere else in this app. Working material
 * lives in this ViewModel only between pick and save/cancel
 * and is wiped on both paths.
 */
class KeychainViewModel(
    private val repository: KeychainRepository
) : ViewModel() {

    val entries: StateFlow<List<KeychainEntry>> = repository.observeEntries()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _message = MutableStateFlow<String?>(null)
    /** User-safe outcome line (an error or a refusal reason). */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Import sub-state: what the picked file turned out to be. */
    sealed class ImportState {
        data object None : ImportState()
        data object NeedsPassphrase : ImportState()
        data class Ready(
            val kind: AuthType,
            val fingerprint: String?,
            val isPasswordFile: Boolean
        ) : ImportState()
        data class Failed(val reason: String) : ImportState()
    }

    private val _importState = MutableStateFlow<ImportState>(ImportState.None)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    // Working material for the in-flight import. Never leaves
    // this ViewModel; wiped by saveImport/cancelImport.
    private var pendingPem: String? = null
    private var pendingPassword: CharArray? = null
    private var pendingPublicKeyText: String? = null
    private var pendingFingerprint: String? = null

    fun clearMessage() {
        _message.value = null
    }

    /** The picked file could not even be read (SAF failure). */
    fun onFileReadFailed() {
        _importState.value = ImportState.Failed("The file could not be read.")
    }

    // ---- direct adds ----

    fun addPassword(label: String, password: CharArray) {
        save(label, AuthType.PASSWORD, CredentialInput.Password(password))
    }

    fun addKey(label: String, pemText: String, passphrase: CharArray?) {
        viewModelScope.launch {
            try {
                val description = withContext(Dispatchers.Default) {
                    KeyGeneration.describe(pemText, passphrase)
                }
                save(
                    label, AuthType.PRIVATE_KEY,
                    CredentialInput.PrivateKey(pemText, null),
                    publicKeyText = description.publicKeyText,
                    fingerprint = description.fingerprint
                )
            } catch (e: KeyGenerationException) {
                _message.value = e.message
            }
        }
    }

    fun generateEd25519(label: String) = generate(label) { KeyGeneration.generateEd25519() }

    fun generateRsa(label: String) = generate(label) { KeyGeneration.generateRsa() }

    private fun generate(label: String, make: () -> com.pishi.termbridge.security.GeneratedKey) {
        viewModelScope.launch {
            try {
                val generated = withContext(Dispatchers.Default) { make() }
                save(
                    label, AuthType.PRIVATE_KEY,
                    CredentialInput.PrivateKey(generated.pemText, null),
                    publicKeyText = generated.publicKeyText,
                    fingerprint = generated.fingerprint
                )
            } catch (e: KeyGenerationException) {
                _message.value = e.message
            }
        }
    }

    private fun save(
        label: String,
        kind: AuthType,
        credential: CredentialInput,
        publicKeyText: String? = null,
        fingerprint: String? = null
    ) {
        viewModelScope.launch {
            try {
                repository.saveEntry(
                    KeychainEntry(label = label.trim(), kind = kind),
                    credential,
                    publicKeyText = publicKeyText,
                    fingerprint = fingerprint
                )
                _message.value = null
            } catch (e: KeychainRepositoryException) {
                _message.value = e.message
            } catch (e: Exception) {
                _message.value = "The entry could not be saved. Please try again."
            }
        }
    }

    // ---- delete ----

    fun delete(entry: KeychainEntry, force: Boolean = false) {
        viewModelScope.launch {
            try {
                repository.deleteEntry(entry.id, force)
                _message.value = null
            } catch (e: KeychainRepositoryException) {
                _message.value = e.message
            } catch (e: Exception) {
                _message.value = "The entry could not be deleted. Please try again."
            }
        }
    }

    // ---- file import (F-01) ----

    fun onFilePicked(bytes: ByteArray) {
        cancelImport()
        when (val result = KeyFileParser.parse(bytes)) {
            is KeyFileParse.Rejected -> {
                _importState.value = ImportState.Failed(result.reason)
            }
            is KeyFileParse.Password -> {
                pendingPassword = result.password
                _importState.value = ImportState.Ready(
                    kind = AuthType.PASSWORD,
                    fingerprint = null,
                    isPasswordFile = true
                )
            }
            is KeyFileParse.PrivateKey -> {
                pendingPem = result.pemText
                if (result.encrypted) {
                    _importState.value = ImportState.NeedsPassphrase
                } else {
                    proveKey(passphrase = null)
                }
            }
        }
        bytes.fill(0)
    }

    fun submitImportPassphrase(passphrase: CharArray) {
        proveKey(passphrase)
    }

    private fun proveKey(passphrase: CharArray?) {
        val pem = pendingPem ?: return
        viewModelScope.launch {
            try {
                val description = withContext(Dispatchers.Default) {
                    KeyGeneration.describe(pem, passphrase)
                }
                pendingPublicKeyText = description.publicKeyText
                pendingFingerprint = description.fingerprint
                _importState.value = ImportState.Ready(
                    kind = AuthType.PRIVATE_KEY,
                    fingerprint = description.fingerprint,
                    isPasswordFile = false
                )
            } catch (e: KeyGenerationException) {
                // The passphrase (if any) was wiped by describe.
                // Keep the PEM so the user can retry with the
                // right passphrase, and say so via the state.
                _importState.value = ImportState.NeedsPassphrase
                _message.value = e.message
            }
        }
    }

    fun saveImport(label: String) {
        val state = _importState.value
        if (state !is ImportState.Ready) return
        val credential: CredentialInput = when {
            pendingPassword != null ->
                CredentialInput.Password(pendingPassword!!)
            pendingPem != null ->
                CredentialInput.PrivateKey(pendingPem!!, null)
            else -> return
        }
        val publicKeyText = pendingPublicKeyText
        val fingerprint = pendingFingerprint
        // The repository takes ownership of the credential's
        // arrays (it wipes them); clear our references before
        // the call so cancelImport cannot double-handle them.
        pendingPassword = null
        pendingPem = null
        pendingPublicKeyText = null
        pendingFingerprint = null
        _importState.value = ImportState.None
        save(
            label, state.kind, credential,
            publicKeyText = publicKeyText,
            fingerprint = fingerprint
        )
    }

    fun cancelImport() {
        pendingPem = null
        pendingPassword?.wipe()
        pendingPassword = null
        pendingPublicKeyText = null
        pendingFingerprint = null
        _importState.value = ImportState.None
    }
}

class KeychainViewModelFactory(
    private val repository: KeychainRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(KeychainViewModel::class.java)) {
            return KeychainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

package com.pishi.termbridge.ui.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.data.BackupException
import com.pishi.termbridge.data.BackupService
import com.pishi.termbridge.domain.ImportPreview
import com.pishi.termbridge.domain.ImportResult
import com.pishi.termbridge.domain.MAX_BACKUP_BYTES
import com.pishi.termbridge.domain.MIN_EXPORT_PASSPHRASE_LENGTH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Prompt 11 backup ViewModel. File I/O goes through the SAF
 * ContentResolver the screen obtained — no filesystem path, no
 * storage permission. Passphrases are Compose Strings (the same
 * documented residual as prompt 04): they are converted to
 * CharArrays at use, consumed-and-wiped by the codec/crypto, and
 * the field strings are cleared immediately afterwards.
 */
class BackupViewModel(
    private val backupService: BackupService,
    private val contentResolver: ContentResolver
) : ViewModel {
    var exportEncrypted by mutableStateOf(false)
    var exportPassphrase by mutableStateOf("")
    var exportConfirmPassphrase by mutableStateOf("")
    var importPassphrase by mutableStateOf("")
    var replaceExisting by mutableStateOf(false)

    var preview by mutableStateOf<ImportPreview?>(null)
    var result by mutableStateOf<ImportResult?>(null)
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    var isWorking by mutableStateOf(false)

    /** Export text waiting for a SAF destination. The screen
     *  launches the CreateDocument picker when this becomes
     *  non-null, then calls [writeExport] or [clearPendingExport]. */
    var pendingExportText by mutableStateOf<String?>(null)

    fun prepareExport() {
        error = null
        info = null
        result = null
        if (exportEncrypted) {
            if (exportPassphrase.length < MIN_EXPORT_PASSPHRASE_LENGTH) {
                error = "Passphrase must be at least $MIN_EXPORT_PASSPHRASE_LENGTH characters."
                return
            }
            if (exportPassphrase != exportConfirmPassphrase) {
                error = "Passphrases do not match."
                return
            }
        }
        isWorking = true
        viewModelScope.launch {
            try {
                val text = backupService.buildExport(
                    includeSecrets = exportEncrypted,
                    passphrase = if (exportEncrypted) {
                        exportPassphrase.toCharArray()
                    } else {
                        null
                    }
                )
                exportPassphrase = ""
                exportConfirmPassphrase = ""
                pendingExportText = text
            } catch (e: Exception) {
                error = e.message ?: "Could not create the export."
            } finally {
                isWorking = false
            }
        }
    }

    fun writeExport(uri: Uri) {
        val text = pendingExportText ?: return
        pendingExportText = null
        isWorking = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw BackupException("Could not open the chosen file for writing.")
                }
                info = "Export saved."
            } catch (e: Exception) {
                error = e.message ?: "Could not save the export."
            } finally {
                isWorking = false
            }
        }
    }

    fun clearPendingExport() {
        pendingExportText = null
    }

    fun previewFrom(uri: Uri) {
        error = null
        info = null
        result = null
        preview = null
        isWorking = true
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { readTextCapped(uri) }
                val parsed = backupService.parseForPreview(
                    text,
                    if (importPassphrase.isEmpty()) null else importPassphrase.toCharArray()
                )
                importPassphrase = ""
                preview = parsed
            } catch (e: BackupException) {
                error = e.message
            } catch (e: Exception) {
                error = "Could not read the chosen file."
            } finally {
                isWorking = false
            }
        }
    }

    fun applyPreview() {
        val current = preview ?: return
        error = null
        isWorking = true
        viewModelScope.launch {
            try {
                result = backupService.applyImport(current, replaceExisting)
                preview = null
                replaceExisting = false
            } catch (e: Exception) {
                error = e.message ?: "Could not apply the import."
            } finally {
                isWorking = false
            }
        }
    }

    fun clearMessages() {
        error = null
        info = null
    }

    private fun readTextCapped(uri: Uri): String {
        val stream = contentResolver.openInputStream(uri)
            ?: throw BackupException("Could not open the chosen file.")
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BACKUP_BYTES) {
                    throw BackupException("Backup file is too large.")
                }
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }
}

class BackupViewModelFactory(
    private val backupService: BackupService,
    private val contentResolver: ContentResolver
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BackupViewModel::class.java)) {
            return BackupViewModel(backupService, contentResolver) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

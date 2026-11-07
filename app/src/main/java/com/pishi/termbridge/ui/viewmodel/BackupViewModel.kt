package com.pishi.termbridge.ui.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.R
import com.pishi.termbridge.data.BackupException
import com.pishi.termbridge.data.BackupService
import com.pishi.termbridge.domain.ImportPreview
import com.pishi.termbridge.domain.ImportResult
import com.pishi.termbridge.domain.MAX_BACKUP_BYTES
import com.pishi.termbridge.domain.MIN_EXPORT_PASSPHRASE_LENGTH
import com.pishi.termbridge.ui.UiMessage
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
) : ViewModel() {
    var exportEncrypted by mutableStateOf(false)
    var exportPassphrase by mutableStateOf("")
    var exportConfirmPassphrase by mutableStateOf("")
    var importPassphrase by mutableStateOf("")
    var replaceExisting by mutableStateOf(false)

    var preview by mutableStateOf<ImportPreview?>(null)
    var result by mutableStateOf<ImportResult?>(null)
    // Audit #3 (A-09): messages are UiMessage (resource id +
    // args, or ready-made lower-layer text); the screen resolves.
    var error by mutableStateOf<UiMessage?>(null)
    var info by mutableStateOf<UiMessage?>(null)
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
                error = UiMessage.ofPlural(
                    R.plurals.backup_error_passphrase_too_short,
                    MIN_EXPORT_PASSPHRASE_LENGTH,
                    MIN_EXPORT_PASSPHRASE_LENGTH
                )
                return
            }
            if (exportPassphrase != exportConfirmPassphrase) {
                error = UiMessage.of(R.string.backup_error_passphrase_mismatch)
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
                error = e.message?.let { UiMessage.ofText(it) }
                    ?: UiMessage.of(R.string.backup_error_create_failed)
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
                    } ?: throw BackupUiException(
                        UiMessage.of(R.string.backup_error_open_for_writing)
                    )
                }
                info = UiMessage.of(R.string.backup_info_export_saved)
            } catch (e: BackupUiException) {
                error = e.uiMessage
            } catch (e: Exception) {
                error = e.message?.let { UiMessage.ofText(it) }
                    ?: UiMessage.of(R.string.backup_error_save_failed)
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
            } catch (e: BackupUiException) {
                error = e.uiMessage
            } catch (e: BackupException) {
                error = e.message?.let { UiMessage.ofText(it) }
            } catch (e: Exception) {
                error = UiMessage.of(R.string.backup_error_read_failed)
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
                error = e.message?.let { UiMessage.ofText(it) }
                    ?: UiMessage.of(R.string.backup_error_apply_failed)
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
            ?: throw BackupUiException(
                UiMessage.of(R.string.backup_error_open_file)
            )
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BACKUP_BYTES) {
                    throw BackupUiException(
                        UiMessage.of(R.string.backup_error_file_too_large)
                    )
                }
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }
}

/**
 * A failure whose message is one of this ViewModel's own
 * resource-backed texts (Audit #3 A-09), carried out of the
 * throwing SAF helpers above. Failures from the backup service
 * keep arriving as [BackupException] with ready-made text and
 * are surfaced as [UiMessage.Raw].
 */
private class BackupUiException(val uiMessage: UiMessage) : Exception()

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

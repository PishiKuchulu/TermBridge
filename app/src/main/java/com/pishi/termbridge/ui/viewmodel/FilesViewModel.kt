package com.pishi.termbridge.ui.viewmodel

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pishi.termbridge.R
import com.pishi.termbridge.data.HostEnvironmentRepository
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.domain.HostProfile
import com.pishi.termbridge.domain.wipe
import com.pishi.termbridge.transport.HostKeyDecisionHandler
import com.pishi.termbridge.transport.PendingTrust
import com.pishi.termbridge.transport.RemoteEntry
import com.pishi.termbridge.transport.RemotePath
import com.pishi.termbridge.transport.SftpError
import com.pishi.termbridge.transport.SftpException
import com.pishi.termbridge.transport.SftpManager
import com.pishi.termbridge.transport.SshConnection
import com.pishi.termbridge.transport.SshConnectionRequest
import com.pishi.termbridge.transport.SshTransport
import com.pishi.termbridge.transport.SshTransportException
import com.pishi.termbridge.transport.SshjConnection
import com.pishi.termbridge.ui.UiMessage
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FilesStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    FAILED
}

data class TransferProgress(
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long?,
    val direction: String // "Upload" or "Download"
)

/** Item T-21: a download that stopped mid-way and can resume
 *  from [bytesDone] — the partial file is kept for exactly this. */
data class ResumableDownload(
    val entryName: String,
    val entryPath: String,
    val entrySize: Long,
    val uriString: String,
    val bytesDone: Long
)

/** Item T-21: the most recent completed download, for the
 *  Open / Share actions. */
data class DownloadedFile(
    val name: String,
    val uriString: String
)

/** Item T-21: one queued transfer. Tasks run strictly one at
 *  a time (the SFTP manager is a single channel); the queue is
 *  what makes multi-select and folder transfers possible. */
private sealed interface TransferTask {
    data class Download(
        val entry: RemoteEntry,
        val uri: Uri,
        val startOffset: Long = 0
    ) : TransferTask

    data class Upload(
        val uri: Uri,
        val remoteName: String,
        val remotePath: String,
        val overwrite: Boolean,
        val tempToken: String,
        val resume: SftpManager.UploadResume? = null,
        /** Only the single-file flow offers resume afterwards. */
        val trackForResume: Boolean = false
    ) : TransferTask
}

data class FilesUiState(
    val selectedHostId: Long? = null,
    val status: FilesStatus = FilesStatus.IDLE,
    val connectedHost: HostProfile? = null,
    val currentPath: String = "/",
    val entries: List<RemoteEntry> = emptyList(),
    // Audit #3 (A-09): UiMessage (resource id + args, or
    // ready-made lower-layer text); the screen resolves.
    val errorMessage: UiMessage? = null,
    val pendingTrust: PendingTrust? = null,
    val transfer: TransferProgress? = null,
    /** Name of an upload that conflicts with an existing entry. */
    val uploadConflictName: String? = null,
    /** Non-error note (e.g. working-directory fallback). Never a secret. */
    val infoMessage: UiMessage? = null,
    /** True when the connected host has a local SAF folder set:
     *  downloads then write straight into it (prompt 09). */
    val localFolderAvailable: Boolean = false,
    // Item T-21 presentation + queue state.
    val sort: com.pishi.termbridge.transport.RemoteSort =
        com.pishi.termbridge.transport.RemoteSort.NAME,
    val query: String = "",
    val showHidden: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    /** Transfers still waiting behind the running one. */
    val queueRemaining: Int = 0,
    val resumableDownload: ResumableDownload? = null,
    /** Name of a cancelled upload that can resume, or null. */
    val resumableUploadName: String? = null,
    val lastDownloaded: DownloadedFile? = null
)

/**
 * Prompt 07 file manager state.
 *
 * Connection model (explicit, documented): Files opens its OWN
 * SSH connection, separate from the terminal's connection.
 *
 * Prompt 15 decision — KEEP SEPARATE (see
 * docs/INTEGRATION_NOTES.md §1 for the full reasoning, and
 * don't re-open this without it): sharing one sshj client
 * across the activity-scoped terminal ViewModel and this
 * destination-scoped ViewModel would need a shared lifecycle
 * holder with reference counting, would only help when both
 * target the same host (the second-connection path must stay
 * anyway), and would couple failures — an SFTP disconnect
 * could kill the terminal and vice versa. The cost of
 * separation is one extra TCP connection and authentication
 * when both are used; the benefit is failure isolation and
 * the untouched prompt-05 trust/passphrase flow per
 * connection. Disconnect on leaving the Files screen is
 * therefore acceptable here (unlike the terminal, which is
 * activity-scoped by an explicit prompt-06 decision).
 *
 * Local access is SAF only: upload sources and download
 * destinations are user-picked URIs, never broad storage
 * permissions, never direct file paths. Transfers stream through
 * counting wrappers — no whole file is ever held in memory.
 */
class FilesViewModel(
    private val repository: HostRepository,
    private val environmentRepository: HostEnvironmentRepository,
    private val contentResolver: ContentResolver
) : ViewModel() {
    private val transport = SshTransport(repository)

    val hosts: StateFlow<List<HostProfile>> = repository.getHosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private var connection: SshConnection? = null
    private var sftpManager: SftpManager? = null
    private var connectJob: Job? = null
    private var transferJob: Job? = null
    private var transferCancelled = false
    private var decisionDeferred: CompletableDeferred<Boolean>? = null

    // Item T-21: the raw listing (presentation filters apply on
    // top), the transfer queue, and the resumable-upload record.
    private var rawEntries: List<RemoteEntry> = emptyList()
    private val transferQueue = ArrayDeque<TransferTask>()
    private var queueWorkerActive = false

    private data class ResumableUpload(
        val uri: Uri,
        val remoteName: String,
        val remotePath: String,
        val overwrite: Boolean,
        val tempToken: String
    )

    private var resumableUpload: ResumableUpload? = null

    // Upload conflict state, observed by the UI dialog.
    var pendingUploadUri: Uri? = null
        private set
    var pendingUploadName: String? = null
        private set
    var pendingDownloadEntry: RemoteEntry? = null
        private set

    private val decisionHandler = object : HostKeyDecisionHandler {
        override suspend fun decideUnknown(pending: PendingTrust): Boolean =
            awaitDecision(pending)

        override suspend fun decideChanged(pending: PendingTrust): Boolean =
            awaitDecision(pending)
    }

    private suspend fun awaitDecision(pending: PendingTrust): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        decisionDeferred = deferred
        _uiState.value = _uiState.value.copy(pendingTrust = pending)
        try {
            return deferred.await()
        } finally {
            decisionDeferred = null
            if (_uiState.value.pendingTrust == pending) {
                _uiState.value = _uiState.value.copy(pendingTrust = null)
            }
        }
    }

    fun selectHost(id: Long) {
        if (_uiState.value.status != FilesStatus.CONNECTING) {
            _uiState.value = _uiState.value.copy(selectedHostId = id, errorMessage = null)
        }
    }

    fun answerTrust(trust: Boolean) {
        decisionDeferred?.complete(trust)
    }

    fun connect(passphrase: String) {
        val hostId = _uiState.value.selectedHostId ?: return
        connectJob?.cancel()
        _uiState.value = _uiState.value.copy(
            status = FilesStatus.CONNECTING,
            errorMessage = null,
            pendingTrust = null
        )
        connectJob = viewModelScope.launch {
            var credentialBytes: ByteArray? = null
            var passphraseChars: CharArray? = null
            try {
                val profile = repository.getHost(hostId)
                if (profile == null) {
                    _uiState.value = _uiState.value.copy(
                        status = FilesStatus.FAILED,
                        errorMessage = UiMessage.of(R.string.vm_error_host_not_found)
                    )
                    return@launch
                }
                credentialBytes = repository.loadCredential(hostId)
                if (credentialBytes == null) {
                    _uiState.value = _uiState.value.copy(
                        status = FilesStatus.FAILED,
                        errorMessage = UiMessage.of(R.string.vm_error_no_credential)
                    )
                    return@launch
                }
                passphraseChars = if (profile.authType == AuthType.PRIVATE_KEY && passphrase.isNotEmpty()) {
                    passphrase.toCharArray()
                } else {
                    null
                }
                val conn = transport.connect(
                    SshConnectionRequest(
                        profile = profile,
                        credential = credentialBytes,
                        passphrase = passphraseChars
                    ),
                    decisionHandler
                )
                val sshjConn = conn as? SshjConnection
                if (sshjConn == null) {
                    conn.disconnect()
                    _uiState.value = _uiState.value.copy(
                        status = FilesStatus.FAILED,
                        errorMessage = UiMessage.of(R.string.files_error_sftp_start)
                    )
                    return@launch
                }
                connection = conn
                val manager = SftpManager(sshjConn)
                sftpManager = manager
                // SFTP availability is checked now, not on first
                // listing: a server without SFTP fails here with a
                // clear message, not later with a generic listing error.
                withContext(Dispatchers.IO) { manager.ensureSftp() }
                // Prompt 09: SFTP starts in the host's remote
                // working directory ONLY when it is a POSIX-style
                // absolute path (RemotePath normalises it). A
                // Windows-style directory cannot be a start path
                // here: the SFTP path dialect on Windows servers
                // is not standardised in this app, so guessing
                // would list the wrong place. Browsing then starts
                // at root, honestly, not at a guessed path.
                val envSettings = environmentRepository.getSettingsOnce(hostId)
                val startPath = envSettings?.remoteWorkingDirectory
                    ?.takeIf { it.isNotBlank() }
                    ?.let { RemotePath.normalize(it) }
                    ?: "/"
                _uiState.value = _uiState.value.copy(
                    status = FilesStatus.CONNECTED,
                    connectedHost = profile,
                    currentPath = startPath,
                    errorMessage = null,
                    infoMessage = null,
                    localFolderAvailable = !envSettings?.localDirectoryUri.isNullOrBlank()
                )
                refreshWithStartFallback()
            } catch (e: SshTransportException) {
                _uiState.value = _uiState.value.copy(
                    status = FilesStatus.FAILED,
                    errorMessage = UiMessage.ofText(e.userFacingMessage())
                )
            } catch (e: SftpException) {
                disconnect()
                _uiState.value = _uiState.value.copy(
                    status = FilesStatus.FAILED,
                    errorMessage = UiMessage.ofText(e.error.userMessage)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    status = FilesStatus.FAILED,
                    errorMessage = UiMessage.of(R.string.vm_error_connection_failed)
                )
            } finally {
                credentialBytes?.wipe()
                passphraseChars?.wipe()
            }
        }
    }

    fun disconnect() {
        transferJob?.cancel()
        transferCancelled = true
        // Item T-21: queued work and resume records belong to
        // this connection only.
        transferQueue.clear()
        queueWorkerActive = false
        rawEntries = emptyList()
        resumableUpload = null
        // Review #1 (C-02): SFTP/transport close sends socket
        // traffic, so it runs on IO, never on the calling thread.
        val managerToClose = sftpManager
        val connectionToClose = connection
        sftpManager = null
        connection = null
        decisionDeferred?.complete(false)
        pendingUploadUri = null
        pendingUploadName = null
        pendingDownloadEntry = null
        _uiState.value = FilesUiState(selectedHostId = _uiState.value.selectedHostId)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                managerToClose?.close()
            } catch (_: Exception) {
            }
            try {
                connectionToClose?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun cancel() {
        decisionDeferred?.complete(false)
        connectJob?.cancel()
        disconnect()
    }

    // ---- Item T-21: presentation state + transfer queue ----

    /** Republish the displayed listing from the raw one. */
    private fun publishEntries() {
        val state = _uiState.value
        _uiState.value = state.copy(
            entries = com.pishi.termbridge.transport.RemoteListLogic.displayed(
                rawEntries, state.query, state.showHidden, state.sort
            )
        )
    }

    fun setQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        publishEntries()
    }

    fun cycleSort() {
        val sorts = com.pishi.termbridge.transport.RemoteSort.entries
        val next = sorts[(_uiState.value.sort.ordinal + 1) % sorts.size]
        _uiState.value = _uiState.value.copy(sort = next)
        publishEntries()
    }

    fun setShowHidden(show: Boolean) {
        _uiState.value = _uiState.value.copy(showHidden = show)
        publishEntries()
    }

    fun toggleSelected(entry: RemoteEntry) {
        val current = _uiState.value.selectedPaths
        _uiState.value = _uiState.value.copy(
            selectedPaths = if (entry.path in current) {
                current - entry.path
            } else {
                current + entry.path
            }
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedPaths = emptySet())
    }

    private fun publishQueue() {
        _uiState.update { it.copy(queueRemaining = transferQueue.size) }
    }

    private fun enqueue(task: TransferTask) {
        transferQueue.addLast(task)
        publishQueue()
        pumpQueue()
    }

    private fun pumpQueue() {
        if (queueWorkerActive) return
        val task = transferQueue.removeFirstOrNull() ?: return
        queueWorkerActive = true
        publishQueue()
        transferCancelled = false
        transferJob = viewModelScope.launch {
            try {
                when (task) {
                    is TransferTask.Download -> executeDownload(task)
                    is TransferTask.Upload -> executeUpload(task)
                }
            } finally {
                queueWorkerActive = false
                publishQueue()
                pumpQueue()
            }
        }
    }

    private fun newTempToken(): String =
        java.util.UUID.randomUUID().toString().take(8)

    fun refresh() {
        val manager = sftpManager ?: return
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    manager.list(_uiState.value.currentPath)
                }
                rawEntries = entries
                _uiState.value = _uiState.value.copy(errorMessage = null)
                publishEntries()
            } catch (e: SftpException) {
                _uiState.value = _uiState.value.copy(errorMessage = UiMessage.ofText(e.error.userMessage))
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = UiMessage.of(R.string.files_error_list_directory))
            }
        }
    }

    /**
     * Prompt 09 start-path fallback: if the host's working
     * directory cannot be listed (deleted, no permission), fall
     * back to root once and say so, instead of leaving the user
     * on a dead path with a generic error. Root itself failing
     * is a real error and is reported as one.
     */
    private fun refreshWithStartFallback() {
        val manager = sftpManager ?: return
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    manager.list(_uiState.value.currentPath)
                }
                rawEntries = entries
                _uiState.value = _uiState.value.copy(errorMessage = null)
                publishEntries()
            } catch (_: Exception) {
                if (_uiState.value.currentPath != "/") {
                    _uiState.value = _uiState.value.copy(
                        currentPath = "/",
                        infoMessage = UiMessage.of(R.string.files_info_workdir_fallback)
                    )
                    refresh()
                } else {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = UiMessage.of(R.string.files_error_list_directory)
                    )
                }
            }
        }
    }

    fun navigateInto(entry: RemoteEntry) {
        if (!entry.isDirectory) return
        _uiState.value = _uiState.value.copy(currentPath = entry.path)
        refresh()
    }

    fun navigateUp() {
        val parent = RemotePath.parent(_uiState.value.currentPath)
        _uiState.value = _uiState.value.copy(currentPath = parent)
        refresh()
    }

    fun mkdir(name: String) {
        if (!RemotePath.isValidName(name)) {
            _uiState.value = _uiState.value.copy(errorMessage = UiMessage.of(R.string.files_error_invalid_directory_name))
            return
        }
        val manager = sftpManager ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { manager.mkdir(_uiState.value.currentPath, name) }
                refresh()
            } catch (e: SftpException) {
                _uiState.value = _uiState.value.copy(errorMessage = UiMessage.ofText(e.error.userMessage))
            }
        }
    }

    fun rename(entry: RemoteEntry, newName: String) {
        if (!RemotePath.isValidName(newName)) {
            _uiState.value = _uiState.value.copy(errorMessage = UiMessage.of(R.string.files_error_invalid_name))
            return
        }
        val manager = sftpManager ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { manager.rename(entry.path, newName) }
                refresh()
            } catch (e: SftpException) {
                _uiState.value = _uiState.value.copy(errorMessage = UiMessage.ofText(e.error.userMessage))
            }
        }
    }

    fun delete(entry: RemoteEntry) {
        val manager = sftpManager ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { manager.delete(entry.path, entry.isDirectory) }
                refresh()
            } catch (e: SftpException) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = if (entry.isDirectory) {
                        UiMessage.of(
                            R.string.files_error_delete_not_empty,
                            e.error.userMessage
                        )
                    } else {
                        UiMessage.ofText(e.error.userMessage)
                    }
                )
            }
        }
    }

    // ---- Item T-21: multi-select, folder transfers ----

    /** Delete every selected entry, one by one (the manager
     *  has no recursive delete, so a non-empty directory in
     *  the selection fails with the usual note and the rest
     *  still proceed). */
    fun deleteSelected() {
        val manager = sftpManager ?: return
        val targets = rawEntries.filter { it.path in _uiState.value.selectedPaths }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            for (entry in targets) {
                try {
                    withContext(Dispatchers.IO) { manager.delete(entry.path, entry.isDirectory) }
                } catch (_: Exception) {
                    failed++
                }
            }
            clearSelection()
            _uiState.value = _uiState.value.copy(
                infoMessage = if (failed == 0) {
                    UiMessage.ofPlural(
                        R.plurals.files_info_deleted, targets.size, targets.size
                    )
                } else {
                    UiMessage.ofPlural(
                        R.plurals.files_info_deleted_partial,
                        targets.size - failed,
                        targets.size - failed, failed
                    )
                }
            )
            refresh()
        }
    }

    /** The host's local SAF folder root document, or null (the
     *  error is already in uiState). */
    private suspend fun localTreeRootDoc(): Uri? {
        val hostId = _uiState.value.connectedHost?.id ?: return null
        val settings = environmentRepository.getSettingsOnce(hostId)
        val treeUriString = settings?.localDirectoryUri?.takeIf { it.isNotBlank() }
        if (treeUriString == null) {
            _uiState.value = _uiState.value.copy(
                errorMessage = UiMessage.of(R.string.files_error_no_local_folder)
            )
            return null
        }
        val treeUri = Uri.parse(treeUriString)
        return DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
    }

    private suspend fun createLocalDocument(
        parentDoc: Uri,
        mime: String,
        name: String
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            DocumentsContract.createDocument(contentResolver, parentDoc, mime, name)
        } catch (_: Exception) {
            null
        }
    }

    /** Recursively list [root] (excluding itself). */
    private suspend fun walkRemote(
        manager: SftpManager,
        root: RemoteEntry
    ): List<RemoteEntry> {
        val out = mutableListOf<RemoteEntry>()
        val stack = ArrayDeque<RemoteEntry>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = withContext(Dispatchers.IO) { manager.list(dir.path) }
            for (child in children) {
                out += child
                if (child.isDirectory) stack.addLast(child)
            }
        }
        return out
    }

    /** Queue a whole remote folder into the host's local
     *  folder, recreating the directory structure. */
    fun downloadFolderToLocalFolder(entry: RemoteEntry) {
        if (!entry.isDirectory) return
        val manager = sftpManager ?: return
        viewModelScope.launch {
            val rootDoc = localTreeRootDoc() ?: return@launch
            enqueueFolderDownload(manager, entry, rootDoc)
        }
    }

    /** Download every selected entry (files and folders) into
     *  the host's local folder. Bulk download needs the folder:
     *  a picker per file would defeat the point of selecting. */
    fun downloadSelectedToLocalFolder() {
        val manager = sftpManager ?: return
        val targets = rawEntries.filter { it.path in _uiState.value.selectedPaths }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val rootDoc = localTreeRootDoc() ?: return@launch
            var queued = 0
            for (entry in targets) {
                if (entry.isDirectory) {
                    queued += enqueueFolderDownload(manager, entry, rootDoc)
                } else if (RemotePath.isValidName(entry.name) &&
                    !com.pishi.termbridge.domain.ControlChars.containsControl(entry.name)
                ) {
                    val doc = createLocalDocument(
                        rootDoc, "application/octet-stream", entry.name
                    )
                    if (doc != null) {
                        enqueue(TransferTask.Download(entry, doc))
                        queued++
                    }
                }
            }
            clearSelection()
            _uiState.value = _uiState.value.copy(
                infoMessage = UiMessage.ofPlural(
                    R.plurals.files_info_queued_downloads, queued, queued
                )
            )
        }
    }

    /** Walk + create local dirs + enqueue files. Returns how
     *  many file downloads were queued. */
    private suspend fun enqueueFolderDownload(
        manager: SftpManager,
        root: RemoteEntry,
        rootDoc: Uri
    ): Int {
        val treeRoot = createLocalDocument(
            rootDoc, DocumentsContract.Document.MIME_TYPE_DIR, root.name
        ) ?: return 0
        val dirDocs = mutableMapOf<String, Uri>()
        dirDocs[root.path] = treeRoot
        var queued = 0
        // walkRemote returns parents before their children only
        // if the stack order cooperates — it does not guarantee
        // it, so create directories in path-depth order.
        val all = walkRemote(manager, root)
        for (dir in all.filter { it.isDirectory }
            .sortedBy { it.path.count { c -> c == '/' } }) {
            val parentDoc = dirDocs[RemotePath.parent(dir.path)] ?: continue
            val doc = createLocalDocument(
                parentDoc, DocumentsContract.Document.MIME_TYPE_DIR, dir.name
            )
            if (doc != null) dirDocs[dir.path] = doc
        }
        for (file in all.filter { !it.isDirectory }) {
            if (!RemotePath.isValidName(file.name) ||
                com.pishi.termbridge.domain.ControlChars.containsControl(file.name)
            ) {
                continue
            }
            val parentDoc = dirDocs[RemotePath.parent(file.path)] ?: continue
            val doc = createLocalDocument(
                parentDoc, "application/octet-stream", file.name
            )
            if (doc != null) {
                enqueue(TransferTask.Download(file, doc))
                queued++
            }
        }
        return queued
    }

    /** Bulk upload from a multi-document picker. Names that
     *  already exist remotely are SKIPPED and counted (the
     *  overwrite/rename dialog stays a single-file flow). */
    fun onUploadsPicked(uris: List<Uri>) {
        if (uris.size == 1) {
            onUploadPicked(uris[0])
            return
        }
        if (sftpManager == null) return
        val existing = rawEntries.map { it.name }.toSet()
        var queued = 0
        var skipped = 0
        for (uri in uris) {
            val (name, _) = queryDisplayNameAndSize(uri)
            val safeName = name ?: "upload"
            if (safeName in existing) {
                skipped++
                continue
            }
            val remotePath = RemotePath.join(_uiState.value.currentPath, safeName)
            if (remotePath == null) {
                skipped++
                continue
            }
            enqueue(
                TransferTask.Upload(
                    uri = uri,
                    remoteName = safeName,
                    remotePath = remotePath,
                    overwrite = false,
                    tempToken = newTempToken()
                )
            )
            queued++
        }
        _uiState.value = _uiState.value.copy(
            infoMessage = if (skipped > 0) {
                UiMessage.ofPlural(
                    R.plurals.files_info_queued_uploads_skipped, queued, queued, skipped
                )
            } else {
                UiMessage.ofPlural(
                    R.plurals.files_info_queued_uploads, queued, queued
                )
            }
        )
    }

    /** Upload a whole local folder (SAF tree) into the current
     *  remote directory, recreating its structure. */
    fun onUploadFolderPicked(treeUri: Uri) {
        val manager = sftpManager ?: return
        viewModelScope.launch {
            val rootDocId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (_: Exception) {
                null
            } ?: run {
                _uiState.value = _uiState.value.copy(
                    errorMessage = UiMessage.of(R.string.files_error_read_folder)
                )
                return@launch
            }
            // (docId, relative path, isDirectory)
            val found = mutableListOf<Triple<String, String, Boolean>>()
            val stack = ArrayDeque<Pair<String, String>>()
            stack.addLast(rootDocId to "")
            while (stack.isNotEmpty()) {
                val (parentId, parentRel) = stack.removeLast()
                for ((docId, name, isDir) in listLocalChildren(treeUri, parentId)) {
                    val rel = if (parentRel.isEmpty()) name else "$parentRel/$name"
                    found += Triple(docId, rel, isDir)
                    if (isDir) stack.addLast(docId to rel)
                }
            }
            // Create remote directories first (parents first),
            // then queue the files beneath them.
            val basePath = _uiState.value.currentPath
            for ((_, rel, isDir) in found.filter { it.third }
                .sortedBy { it.second.count { c -> c == '/' } }) {
                val parentPath = RemotePath.parent(
                    RemotePath.join(basePath, rel) ?: continue
                )
                try {
                    withContext(Dispatchers.IO) {
                        manager.mkdir(parentPath, rel.substringAfterLast('/'))
                    }
                } catch (_: Exception) {
                    // Already exists is the common case here.
                }
            }
            var queued = 0
            for ((docId, rel, isDir) in found) {
                if (isDir) continue
                val remotePath = RemotePath.join(basePath, rel) ?: continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                enqueue(
                    TransferTask.Upload(
                        uri = docUri,
                        remoteName = rel.substringAfterLast('/'),
                        remotePath = remotePath,
                        overwrite = false,
                        tempToken = newTempToken()
                    )
                )
                queued++
            }
            _uiState.value = _uiState.value.copy(
                infoMessage = UiMessage.ofPlural(
                    R.plurals.files_info_queued_uploads_folder, queued, queued
                )
            )
        }
    }

    /** Direct SAF children of one tree document:
     *  (docId, display name, isDirectory). */
    private fun listLocalChildren(
        treeUri: Uri,
        parentDocId: String
    ): List<Triple<String, String, Boolean>> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, parentDocId
        )
        val out = mutableListOf<Triple<String, String, Boolean>>()
        try {
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    out += Triple(
                        id, name,
                        mime == DocumentsContract.Document.MIME_TYPE_DIR
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Upload entry point from the SAF picker. Checks conflicts first. */
    fun onUploadPicked(uri: Uri) {
        val (name, _) = queryDisplayNameAndSize(uri)
        val safeName = name ?: "upload"
        pendingUploadUri = uri
        pendingUploadName = safeName
        // Conflict is resolved by the UI dialog when the name
        // already exists in the current listing; otherwise upload now.
        // (The RAW listing: a name hidden by the search filter
        // still conflicts — item T-21.)
        if (rawEntries.any { it.name == safeName }) {
            // UI observes uploadConflictName and shows the dialog;
            // the actual upload starts from resolveUploadConflict().
            _uiState.value = _uiState.value.copy(uploadConflictName = safeName)
        } else {
            // Not in the (possibly stale) listing: never overwrite. If
            // the name appeared meanwhile the upload fails with
            // AlreadyExists instead of truncating (review #2 F-11).
            startUpload(uri, safeName, overwrite = false)
        }
    }

    fun resolveUploadConflict(overwrite: Boolean, rename: Boolean) {
        val uri = pendingUploadUri ?: return
        val baseName = pendingUploadName ?: return
        pendingUploadUri = null
        pendingUploadName = null
        _uiState.value = _uiState.value.copy(uploadConflictName = null)
        if (!overwrite && !rename) return
        if (overwrite) {
            startUpload(uri, baseName, overwrite = true)
        } else {
            val existing = rawEntries.map { it.name }.toSet()
            var candidate = baseName
            var counter = 1
            while (candidate in existing) {
                val dot = baseName.lastIndexOf('.')
                candidate = if (dot > 0) {
                    baseName.substring(0, dot) + " ($counter)" + baseName.substring(dot)
                } else {
                    "$baseName ($counter)"
                }
                counter++
            }
            startUpload(uri, candidate, overwrite = false)
        }
    }

    private fun startUpload(uri: Uri, remoteName: String, overwrite: Boolean) {
        pendingUploadUri = null
        pendingUploadName = null
        if (sftpManager == null) return
        val remotePath = RemotePath.join(_uiState.value.currentPath, remoteName)
        if (remotePath == null) {
            _uiState.update { it.copy(errorMessage = UiMessage.of(R.string.files_error_name_unusable_on_server)) }
            return
        }
        enqueue(
            TransferTask.Upload(
                uri = uri,
                remoteName = remoteName,
                remotePath = remotePath,
                overwrite = overwrite,
                tempToken = newTempToken(),
                trackForResume = true
            )
        )
    }

    /** The upload worker (item T-21: runs inside the queue). */
    private suspend fun executeUpload(task: TransferTask.Upload) {
        val manager = sftpManager ?: return
        var lastBytes = task.resume?.bytesAlreadySent ?: 0L
        try {
            val input = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(task.uri)
            } ?: run {
                _uiState.value = _uiState.value.copy(errorMessage = UiMessage.of(R.string.files_error_open_selected_file))
                return
            }
            val total = queryDisplayNameAndSize(task.uri).second
            withContext(Dispatchers.IO) {
                input.use { stream ->
                    manager.upload(
                        stream,
                        task.remotePath,
                        onProgress = { bytes ->
                            lastBytes = bytes
                            _uiState.update {
                                it.copy(
                                    transfer = TransferProgress(
                                        task.remoteName, bytes, total, "Upload"
                                    )
                                )
                            }
                        },
                        isCancelled = { transferCancelled },
                        overwrite = task.overwrite,
                        tempToken = task.tempToken,
                        resume = task.resume,
                        keepTempOnCancel = task.trackForResume
                    )
                }
            }
            if (task.trackForResume) {
                resumableUpload = null
                _uiState.update { it.copy(resumableUploadName = null) }
            }
            _uiState.value = _uiState.value.copy(transfer = null, errorMessage = null)
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SftpException) {
            // Item T-21: a cancelled single-file upload keeps
            // its temp on the server and can resume.
            if (e.error == com.pishi.termbridge.transport.SftpError.Cancelled &&
                task.trackForResume && lastBytes > 0
            ) {
                resumableUpload = ResumableUpload(
                    uri = task.uri,
                    remoteName = task.remoteName,
                    remotePath = task.remotePath,
                    overwrite = task.overwrite,
                    tempToken = task.tempToken
                )
                _uiState.value = _uiState.value.copy(
                    transfer = null,
                    errorMessage = UiMessage.ofText(e.error.userMessage),
                    resumableUploadName = task.remoteName
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    transfer = null,
                    errorMessage = UiMessage.ofText(e.error.userMessage)
                )
            }
        } catch (_: Exception) {
            _uiState.value = _uiState.value.copy(
                transfer = null,
                errorMessage = UiMessage.of(R.string.files_error_upload_failed)
            )
        }
    }

    /** Item T-21: resume a cancelled upload from its temp. */
    fun resumeUpload() {
        val record = resumableUpload ?: return
        val manager = sftpManager ?: return
        resumableUpload = null
        _uiState.update { it.copy(resumableUploadName = null) }
        viewModelScope.launch {
            val tempPath = SftpManager.tempPathFor(record.remotePath, record.tempToken)
            val size = tempPath?.let {
                withContext(Dispatchers.IO) { manager.statSize(it) }
            }
            if (tempPath != null && size != null && size > 0) {
                enqueue(
                    TransferTask.Upload(
                        uri = record.uri,
                        remoteName = record.remoteName,
                        remotePath = record.remotePath,
                        overwrite = record.overwrite,
                        tempToken = record.tempToken,
                        resume = SftpManager.UploadResume(tempPath, size),
                        trackForResume = true
                    )
                )
            } else {
                // The temp is gone (server cleaned, new
                // session): start the upload fresh.
                enqueue(
                    TransferTask.Upload(
                        uri = record.uri,
                        remoteName = record.remoteName,
                        remotePath = record.remotePath,
                        overwrite = record.overwrite,
                        tempToken = newTempToken(),
                        trackForResume = true
                    )
                )
            }
        }
    }

    /** Item T-21: abandon a resumable upload; its temp file is
     *  removed best-effort. */
    fun discardResumableUpload() {
        val record = resumableUpload ?: return
        resumableUpload = null
        _uiState.update { it.copy(resumableUploadName = null) }
        val manager = sftpManager ?: return
        val tempPath = SftpManager.tempPathFor(record.remotePath, record.tempToken)
            ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    manager.delete(tempPath, isDirectory = false)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Download entry point: the UI opens a SAF CreateDocument picker. */
    fun prepareDownload(entry: RemoteEntry) {
        pendingDownloadEntry = entry
    }

    fun onDownloadDestinationPicked(uri: Uri) {
        val entry = pendingDownloadEntry ?: return
        pendingDownloadEntry = null
        startDownload(entry, uri)
    }

    /**
     * Prompt 09: download straight into the connected host's local
     * SAF folder, without a picker. The folder URI comes from the
     * host's environment settings (picked with OpenDocumentTree
     * and persistently permitted by the environment screen). If
     * the folder cannot be written (permission lost, provider
     * gone), the error names the folder problem and the user can
     * still download via the picker — the picker path is untouched.
     * Returns nothing to the UI; progress and errors flow through
     * uiState, like every other transfer here.
     */
    fun downloadToLocalFolder(entry: RemoteEntry) {
        val hostId = _uiState.value.connectedHost?.id ?: return
        // The name comes from the server: refuse anything that is not a
        // plain file name before it becomes a local document name.
        if (!RemotePath.isValidName(entry.name) || com.pishi.termbridge.domain.ControlChars.containsControl(entry.name)) {
            _uiState.update { it.copy(errorMessage = UiMessage.of(R.string.files_error_name_unsavable_locally)) }
            return
        }
        viewModelScope.launch {
            val settings = environmentRepository.getSettingsOnce(hostId)
            val treeUriString = settings?.localDirectoryUri?.takeIf { it.isNotBlank() }
            if (treeUriString == null) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = UiMessage.of(R.string.files_error_no_local_folder)
                )
                return@launch
            }
            val docUri = try {
                withContext(Dispatchers.IO) {
                    val treeUri = Uri.parse(treeUriString)
                    val rootDoc = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    DocumentsContract.createDocument(
                        contentResolver,
                        rootDoc,
                        "application/octet-stream",
                        entry.name
                    )
                }
            } catch (_: Exception) {
                null
            }
            if (docUri == null) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = UiMessage.of(R.string.files_error_write_local_folder)
                )
                return@launch
            }
            startDownload(entry, docUri)
        }
    }

    /** Shared download entry: picker destination and
     *  local-folder destination differ only in how the URI was
     *  obtained. Item T-21: both now enqueue. */
    private fun startDownload(entry: RemoteEntry, uri: Uri) {
        if (sftpManager == null) return
        enqueue(TransferTask.Download(entry, uri))
    }

    /** The download worker (item T-21: runs inside the queue). */
    private suspend fun executeDownload(task: TransferTask.Download) {
        val manager = sftpManager ?: return
        val entry = task.entry
        val uri = task.uri
        var lastBytes = task.startOffset
        var output: OutputStream? = null
        try {
            output = withContext(Dispatchers.IO) {
                if (task.startOffset > 0) {
                    // Resume appends; providers that cannot append
                    // return null or throw, handled below.
                    contentResolver.openOutputStream(uri, "wa")
                } else {
                    contentResolver.openOutputStream(uri)
                }
            } ?: run {
                _uiState.value = _uiState.value.copy(errorMessage = UiMessage.of(R.string.files_error_open_destination))
                return
            }
            withContext(Dispatchers.IO) {
                output.use { stream ->
                    manager.download(
                        entry.path,
                        stream,
                        expectedSize = if (entry.size >= 0) entry.size else null,
                        onProgress = { bytes ->
                            lastBytes = bytes
                            _uiState.update {
                                it.copy(transfer = TransferProgress(entry.name, bytes, entry.size, "Download"))
                            }
                        },
                        isCancelled = { transferCancelled },
                        startOffset = task.startOffset
                    )
                }
            }
            _uiState.value = _uiState.value.copy(
                transfer = null,
                errorMessage = null,
                resumableDownload = null,
                lastDownloaded = DownloadedFile(entry.name, uri.toString())
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SftpException) {
            // Item T-21: a cancelled or IO-failed download with
            // real progress keeps its partial file and offers
            // resume; every other failure cleans up as before.
            val canResume = (e.error == SftpError.Cancelled || e.error == SftpError.Generic) &&
                lastBytes > 0 && (entry.size < 0 || lastBytes < entry.size)
            if (canResume) {
                _uiState.value = _uiState.value.copy(
                    transfer = null,
                    errorMessage = UiMessage.ofText(e.error.userMessage),
                    resumableDownload = ResumableDownload(
                        entryName = entry.name,
                        entryPath = entry.path,
                        entrySize = entry.size,
                        uriString = uri.toString(),
                        bytesDone = lastBytes
                    )
                )
            } else {
                try {
                    DocumentsContract.deleteDocument(contentResolver, uri)
                } catch (_: Exception) {
                }
                _uiState.value = _uiState.value.copy(
                    transfer = null,
                    errorMessage = UiMessage.ofText(e.error.userMessage)
                )
            }
        } catch (_: Exception) {
            try {
                DocumentsContract.deleteDocument(contentResolver, uri)
            } catch (_: Exception) {
            }
            _uiState.value = _uiState.value.copy(
                transfer = null,
                errorMessage = UiMessage.of(R.string.files_error_download_failed)
            )
        } finally {
            try {
                output?.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Item T-21: resume the kept partial download. */
    fun resumeDownload() {
        val resumable = _uiState.value.resumableDownload ?: return
        if (sftpManager == null) return
        _uiState.update { it.copy(resumableDownload = null) }
        enqueue(
            TransferTask.Download(
                entry = RemoteEntry(
                    name = resumable.entryName,
                    path = resumable.entryPath,
                    isDirectory = false,
                    size = resumable.entrySize,
                    isRegularFile = true
                ),
                uri = Uri.parse(resumable.uriString),
                startOffset = resumable.bytesDone
            )
        )
    }

    /** Item T-21: abandon the partial download (deletes it). */
    fun discardResumableDownload() {
        val resumable = _uiState.value.resumableDownload ?: return
        _uiState.update { it.copy(resumableDownload = null) }
        try {
            DocumentsContract.deleteDocument(
                contentResolver, Uri.parse(resumable.uriString)
            )
        } catch (_: Exception) {
        }
    }

    fun cancelTransfer() {
        transferCancelled = true
        // Item T-21: cancel stops the queue too, not just the
        // running transfer.
        transferQueue.clear()
        publishQueue()
        transferJob?.cancel()
        _uiState.value = _uiState.value.copy(transfer = null)
    }

    private fun queryDisplayNameAndSize(uri: Uri): Pair<String?, Long?> {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return Pair(null, null)
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else {
                    null
                }
                Pair(name, size)
            } ?: Pair(null, null)
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    override fun onCleared() {
        // viewModelScope is already cancelled here, so teardown
        // network I/O uses a detached IO scope (best-effort).
        // Review #1 (C-02).
        val managerToClose = sftpManager
        val connectionToClose = connection
        sftpManager = null
        connection = null
        CoroutineScope(Dispatchers.IO).launch {
            try {
                managerToClose?.close()
            } catch (_: Exception) {
            }
            try {
                connectionToClose?.disconnect()
            } catch (_: Exception) {
            }
        }
        super.onCleared()
    }
}

class FilesViewModelFactory(
    private val repository: HostRepository,
    private val environmentRepository: HostEnvironmentRepository,
    private val contentResolver: ContentResolver
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FilesViewModel::class.java)) {
            return FilesViewModel(repository, environmentRepository, contentResolver) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

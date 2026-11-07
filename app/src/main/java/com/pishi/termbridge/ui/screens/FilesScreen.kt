package com.pishi.termbridge.ui.screens

import androidx.activity.compose.BackHandler
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.pishi.termbridge.R
import com.pishi.termbridge.ui.resolve
import com.pishi.termbridge.domain.AuthType
import com.pishi.termbridge.transport.RemoteEntry as TransportRemoteEntry
import com.pishi.termbridge.ui.components.EmptyState
import com.pishi.termbridge.ui.components.PrimaryAction
import com.pishi.termbridge.ui.components.SecondaryAction
import com.pishi.termbridge.ui.components.TrustDialog
import com.pishi.termbridge.ui.theme.Spacing
import com.pishi.termbridge.ui.viewmodel.FilesStatus
import com.pishi.termbridge.ui.viewmodel.FilesViewModel

/**
 * Prompt 07 SFTP file manager — real, not a shell.
 *
 * Connection: this screen opens its OWN SSH connection (separate
 * from the terminal's), with the same trust dialog. SFTP is only
 * available where the server offers the subsystem; a server
 * without SFTP fails at connect with a clear message.
 *
 * Local access is SAF only: upload sources and download
 * destinations are user-picked URIs. No broad storage permission
 * is requested. Transfers stream and report progress; cancel
 * stops them. Item T-21: transfers run through a queue
 * (multi-select, folder up/download), the listing can be
 * searched, sorted and shown with hidden files, an interrupted
 * download keeps its partial file and offers resume (as does a
 * cancelled single upload, from its server-side temp), and a
 * completed download offers Open / Share. Partial files from
 * non-resumable failures are still deleted best-effort.
 * Delete stays non-recursive — a non-empty directory cannot be
 * deleted here.
 */
@Composable
fun FilesScreen(
    viewModel: FilesViewModel,
    onAddHost: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hosts by viewModel.hosts.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    var passphrase by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<TransportRemoteEntry?>(null) }
    var renameName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<TransportRemoteEntry?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var mkdirName by remember { mutableStateOf("") }
    // Item T-21: multi-select mode + its delete confirmation.
    var selectMode by remember { mutableStateOf(false) }
    var showBulkDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Audit #3 (A-16, warning half): this screen's ViewModel is
    // destination-scoped, so leaving mid-transfer kills the
    // transfer in onCleared. Back while a transfer is running
    // asks first; only the dialog's Leave lets the press
    // through (the handler disables itself, then the press is
    // re-dispatched). The ViewModel's scope is unchanged.
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var leaveConfirmed by remember { mutableStateOf(false) }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    BackHandler(enabled = uiState.transfer != null && !leaveConfirmed) {
        showLeaveConfirm = true
    }
    LaunchedEffect(leaveConfirmed) {
        if (leaveConfirmed) backDispatcher?.onBackPressed()
    }
    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(stringResource(R.string.files_leave_title)) },
            text = { Text(stringResource(R.string.files_leave_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirm = false
                        leaveConfirmed = true
                    }
                ) { Text(stringResource(R.string.action_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.onUploadsPicked(uris)
    }
    val uploadFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.onUploadFolderPicked(uri)
    }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) viewModel.onDownloadDestinationPicked(uri)
    }

    val pendingTrust = uiState.pendingTrust
    if (pendingTrust != null) {
        TrustDialog(
            pending = pendingTrust,
            onTrust = { viewModel.answerTrust(true) },
            onReject = { viewModel.answerTrust(false) }
        )
    }
    val conflictName = uiState.uploadConflictName
    if (conflictName != null) {
        AlertDialog(
            onDismissRequest = { viewModel.resolveUploadConflict(false, false) },
            title = { Text(stringResource(R.string.conflict_dialog_title)) },
            text = { Text(stringResource(R.string.conflict_dialog_body, conflictName)) },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveUploadConflict(true, false) }) {
                    Text(stringResource(R.string.action_overwrite))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveUploadConflict(false, true) }) {
                    Text(stringResource(R.string.action_rename_auto))
                }
            }
        )
    }
    val renameEntry = renameTarget
    if (renameEntry != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.rename_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    label = { Text(stringResource(R.string.rename_dialog_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.rename(renameEntry, renameName)
                        renameTarget = null
                    }
                ) { Text(stringResource(R.string.action_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    if (showMkdir) {
        AlertDialog(
            onDismissRequest = { showMkdir = false },
            title = { Text(stringResource(R.string.mkdir_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = mkdirName,
                    onValueChange = { mkdirName = it },
                    label = { Text(stringResource(R.string.mkdir_dialog_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.mkdir(mkdirName)
                        mkdirName = ""
                        showMkdir = false
                    }
                ) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showMkdir = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    if (showBulkDelete) {
        AlertDialog(
            onDismissRequest = { showBulkDelete = false },
            title = { Text(stringResource(R.string.files_delete_selected_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.files_delete_selected_body,
                        uiState.selectedPaths.size,
                        uiState.selectedPaths.size
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected()
                        showBulkDelete = false
                        selectMode = false
                    }
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    val deleteEntry = deleteTarget
    if (deleteEntry != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_file_title)) },
            text = { Text(stringResource(R.string.delete_file_body, deleteEntry.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(deleteEntry)
                        deleteTarget = null
                    }
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (hosts.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(Spacing.medium)
        ) {
            EmptyState(
                title = stringResource(R.string.session_no_hosts_title),
                body = stringResource(R.string.session_no_hosts_body),
                actionLabel = stringResource(R.string.action_add_host),
                onAction = onAddHost
            )
        }
        return
    }

    if (uiState.status != FilesStatus.CONNECTED) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.medium)
        ) {
            Text(text = stringResource(R.string.files_sftp_note))
            Spacer(modifier = Modifier.height(Spacing.large))
            Text(text = stringResource(R.string.session_select_host))
            Spacer(modifier = Modifier.height(Spacing.small))
            hosts.forEach { host ->
                // Prompt 14: whole-row selection, 48dp minimum
                // (same pattern as SessionScreen's picker).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.minTouch)
                        .clickable { viewModel.selectHost(host.id) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = uiState.selectedHostId == host.id,
                        onClick = { viewModel.selectHost(host.id) }
                    )
                    Column {
                        Text(text = host.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "${host.username}@${host.hostname}:${host.port}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            val selected = hosts.firstOrNull { it.id == uiState.selectedHostId }
            if (selected?.authType == AuthType.PRIVATE_KEY) {
                Spacer(modifier = Modifier.height(Spacing.medium))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.session_passphrase_label)) },
                    placeholder = { Text(stringResource(R.string.session_passphrase_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(Spacing.large))
            if (uiState.status == FilesStatus.CONNECTING) {
                Text(text = stringResource(R.string.session_connecting))
                Spacer(modifier = Modifier.height(Spacing.medium))
                SecondaryAction(
                    label = stringResource(R.string.action_cancel),
                    onClick = { viewModel.cancel() }
                )
            } else {
                PrimaryAction(
                    label = stringResource(R.string.session_connect),
                    onClick = {
                        viewModel.connect(passphrase)
                        passphrase = ""
                    },
                    enabled = uiState.selectedHostId != null
                )
                uiState.errorMessage?.let {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Text(text = it.resolve(), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        return
    }

    // Connected: file browser.
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(Spacing.medium)) {
            Text(text = stringResource(R.string.files_current_path, uiState.currentPath))
            uiState.infoMessage?.let {
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(text = it.resolve(), style = MaterialTheme.typography.bodySmall)
            }
            // Prompt 09: when the host has a local folder, the
            // download button writes straight into it instead of
            // opening a picker. The user must know that before
            // tapping, so the note is here, not hidden.
            if (uiState.localFolderAvailable) {
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    text = stringResource(R.string.files_local_folder_note),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(Spacing.small))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryAction(
                    label = stringResource(R.string.files_up),
                    onClick = { viewModel.navigateUp() }
                )
                Spacer(modifier = Modifier.padding(end = Spacing.small))
                SecondaryAction(
                    label = stringResource(R.string.files_refresh),
                    onClick = { viewModel.refresh() }
                )
            }
            Spacer(modifier = Modifier.height(Spacing.small))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryAction(
                    label = stringResource(R.string.files_upload),
                    onClick = { uploadLauncher.launch(arrayOf("*/*")) }
                )
                Spacer(modifier = Modifier.padding(end = Spacing.small))
                SecondaryAction(
                    label = stringResource(R.string.files_new_folder),
                    onClick = { showMkdir = true }
                )
            }
            Spacer(modifier = Modifier.height(Spacing.small))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryAction(
                    label = stringResource(R.string.files_upload_folder),
                    onClick = { uploadFolderLauncher.launch(null) }
                )
                Spacer(modifier = Modifier.padding(end = Spacing.small))
                SecondaryAction(
                    label = stringResource(R.string.session_disconnect),
                    onClick = { viewModel.disconnect() }
                )
            }
            // Item T-21: search, sort, hidden files, select mode.
            Spacer(modifier = Modifier.height(Spacing.small))
            OutlinedTextField(
                value = uiState.query,
                onValueChange = { viewModel.setQuery(it) },
                label = { Text(stringResource(R.string.files_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(Spacing.small))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = false,
                    onClick = { viewModel.cycleSort() },
                    label = {
                        Text(
                            stringResource(
                                R.string.files_sort,
                                stringResource(
                                    when (uiState.sort) {
                                        com.pishi.termbridge.transport.RemoteSort.SIZE ->
                                            R.string.files_sort_size
                                        com.pishi.termbridge.transport.RemoteSort.TYPE ->
                                            R.string.files_sort_type
                                        else -> R.string.files_sort_name
                                    }
                                )
                            )
                        )
                    }
                )
                Spacer(modifier = Modifier.padding(end = Spacing.small))
                FilterChip(
                    selected = uiState.showHidden,
                    onClick = { viewModel.setShowHidden(!uiState.showHidden) },
                    label = { Text(stringResource(R.string.files_show_hidden)) }
                )
                Spacer(modifier = Modifier.padding(end = Spacing.small))
                FilterChip(
                    selected = selectMode,
                    onClick = {
                        selectMode = !selectMode
                        if (!selectMode) viewModel.clearSelection()
                    },
                    label = { Text(stringResource(R.string.files_action_select)) }
                )
            }
            if (selectMode) {
                Spacer(modifier = Modifier.height(Spacing.small))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.files_selected_count,
                            uiState.selectedPaths.size,
                            uiState.selectedPaths.size
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.padding(end = Spacing.small))
                    SecondaryAction(
                        label = stringResource(R.string.files_download_selected),
                        onClick = { viewModel.downloadSelectedToLocalFolder() },
                        enabled = uiState.selectedPaths.isNotEmpty() &&
                            uiState.localFolderAvailable
                    )
                    Spacer(modifier = Modifier.padding(end = Spacing.small))
                    SecondaryAction(
                        label = stringResource(R.string.action_delete),
                        onClick = { showBulkDelete = true },
                        enabled = uiState.selectedPaths.isNotEmpty()
                    )
                }
                if (!uiState.localFolderAvailable) {
                    Text(
                        text = stringResource(R.string.files_bulk_download_needs_folder),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            uiState.transfer?.let { transfer ->
                Spacer(modifier = Modifier.height(Spacing.medium))
                // Prompt 14: the direction token ("Upload" /
                // "Download") was a hardcoded ViewModel string
                // shown verbatim; it is now mapped to the
                // existing string resources (the ViewModel sets
                // exactly these two values — see FilesViewModel).
                val directionLabel = if (transfer.direction == "Download") {
                    stringResource(R.string.files_download)
                } else {
                    stringResource(R.string.files_upload)
                }
                Text(
                    text = "$directionLabel: ${transfer.fileName} — " +
                        "${formatSize(transfer.bytesTransferred)}" +
                        (transfer.totalBytes?.let { " / ${formatSize(it)}" } ?: "")
                )
                Spacer(modifier = Modifier.height(Spacing.small))
                if (transfer.totalBytes != null && transfer.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { transfer.bytesTransferred.toFloat() / transfer.totalBytes.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Spacer(modifier = Modifier.height(Spacing.small))
                SecondaryAction(
                    label = stringResource(R.string.action_cancel),
                    onClick = { viewModel.cancelTransfer() }
                )
                if (uiState.queueRemaining > 0) {
                    Spacer(modifier = Modifier.height(Spacing.small))
                    Text(
                        text = pluralStringResource(
                            R.plurals.files_queue_more,
                            uiState.queueRemaining,
                            uiState.queueRemaining
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            // Item T-21: resume offers for interrupted transfers.
            uiState.resumableDownload?.let { resumable ->
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    text = stringResource(
                        R.string.files_resume_download,
                        resumable.entryName,
                        formatSize(resumable.bytesDone)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryAction(
                        label = stringResource(R.string.files_resume),
                        onClick = { viewModel.resumeDownload() }
                    )
                    Spacer(modifier = Modifier.padding(end = Spacing.small))
                    SecondaryAction(
                        label = stringResource(R.string.files_resume_discard),
                        onClick = { viewModel.discardResumableDownload() }
                    )
                }
            }
            uiState.resumableUploadName?.let { name ->
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    text = stringResource(R.string.files_resume_upload, name),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryAction(
                        label = stringResource(R.string.files_resume),
                        onClick = { viewModel.resumeUpload() }
                    )
                    Spacer(modifier = Modifier.padding(end = Spacing.small))
                    SecondaryAction(
                        label = stringResource(R.string.files_resume_discard),
                        onClick = { viewModel.discardResumableUpload() }
                    )
                }
            }
            // Item T-21: open / share the last downloaded file.
            uiState.lastDownloaded?.let { downloaded ->
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(
                    text = stringResource(R.string.files_downloaded, downloaded.name),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryAction(
                        label = stringResource(R.string.files_open),
                        onClick = {
                            val uri = android.net.Uri.parse(downloaded.uriString)
                            val mime = android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(
                                    downloaded.name.substringAfterLast('.', "")
                                        .lowercase()
                                ) ?: "*/*"
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW
                            ).apply {
                                setDataAndType(uri, mime)
                                addFlags(
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) {
                            }
                        }
                    )
                    Spacer(modifier = Modifier.padding(end = Spacing.small))
                    SecondaryAction(
                        label = stringResource(R.string.files_share),
                        onClick = {
                            val uri = android.net.Uri.parse(downloaded.uriString)
                            val send = android.content.Intent(
                                android.content.Intent.ACTION_SEND
                            ).apply {
                                type = "*/*"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                            try {
                                context.startActivity(
                                    android.content.Intent.createChooser(send, null)
                                )
                            } catch (_: Exception) {
                            }
                        }
                    )
                }
            }
            uiState.errorMessage?.let {
                Spacer(modifier = Modifier.height(Spacing.small))
                Text(text = it.resolve(), color = MaterialTheme.colorScheme.error)
            }
        }
        if (uiState.entries.isEmpty()) {
            Column(modifier = Modifier.padding(Spacing.medium)) {
                Text(text = stringResource(R.string.files_empty_dir))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.entries, key = { it.path }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (selectMode) {
                                    viewModel.toggleSelected(entry)
                                } else if (entry.isDirectory) {
                                    viewModel.navigateInto(entry)
                                }
                            }
                            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectMode) {
                            Checkbox(
                                checked = entry.path in uiState.selectedPaths,
                                onCheckedChange = { viewModel.toggleSelected(entry) }
                            )
                        }
                        Icon(
                            imageVector = if (entry.isDirectory) {
                                Icons.Filled.Folder
                            } else {
                                Icons.Filled.InsertDriveFile
                            },
                            // Audit #3 (A-29): the type icon is
                            // the only place some rows announce
                            // their kind (files show a size, not
                            // a type), so it carries the label.
                            contentDescription = stringResource(
                                when {
                                    entry.isDirectory -> R.string.files_directory
                                    entry.isRegularFile -> R.string.files_file
                                    else -> R.string.files_special
                                }
                            )
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = Spacing.small)) {
                            Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (entry.isDirectory) {
                                    stringResource(R.string.files_directory)
                                } else {
                                    formatSize(entry.size)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (!selectMode) {
                            if (!entry.isDirectory) {
                                IconButton(
                                    onClick = {
                                        if (uiState.localFolderAvailable) {
                                            viewModel.downloadToLocalFolder(entry)
                                        } else {
                                            viewModel.prepareDownload(entry)
                                            downloadLauncher.launch(entry.name)
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Download,
                                        contentDescription = stringResource(R.string.files_download)
                                    )
                                }
                            } else if (uiState.localFolderAvailable) {
                                // Item T-21: whole-folder download
                                // into the host's local folder.
                                IconButton(
                                    onClick = {
                                        viewModel.downloadFolderToLocalFolder(entry)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Download,
                                        contentDescription = stringResource(R.string.files_download)
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    renameTarget = entry
                                    renameName = entry.name
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.files_rename)
                                )
                            }
                            IconButton(onClick = { deleteTarget = entry }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.files_delete)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    // Locale.ROOT (audit #7 AUD-007): sizes always render with
    // a dot decimal separator and Latin digits.
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

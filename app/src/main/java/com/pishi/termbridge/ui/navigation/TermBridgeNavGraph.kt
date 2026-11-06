package com.pishi.termbridge.ui.navigation

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pishi.termbridge.data.AdvancedRepository
import com.pishi.termbridge.data.BackupService
import com.pishi.termbridge.data.CommandRepository
import com.pishi.termbridge.data.HostEnvironmentRepository
import com.pishi.termbridge.data.HostRepository
import com.pishi.termbridge.data.KeychainRepository
import com.pishi.termbridge.ui.viewmodel.AdvancedViewModelFactory
import com.pishi.termbridge.ui.viewmodel.BackupViewModelFactory
import com.pishi.termbridge.ui.viewmodel.CommandsViewModelFactory
import com.pishi.termbridge.ui.viewmodel.FilesViewModelFactory
import com.pishi.termbridge.ui.viewmodel.HostEnvironmentViewModelFactory
import com.pishi.termbridge.ui.viewmodel.HostFormViewModelFactory
import com.pishi.termbridge.ui.viewmodel.HostsViewModelFactory
import com.pishi.termbridge.ui.viewmodel.SessionStatus
import com.pishi.termbridge.ui.viewmodel.SessionViewModel
import com.pishi.termbridge.ui.viewmodel.SessionViewModelFactory
import com.pishi.termbridge.R
import com.pishi.termbridge.ui.screens.AboutScreen
import com.pishi.termbridge.ui.screens.ConnectionsScreen
import com.pishi.termbridge.ui.screens.DiscoverScreen
import com.pishi.termbridge.ui.screens.KnownHostsScreen
import com.pishi.termbridge.ui.screens.LogsScreen
import com.pishi.termbridge.ui.screens.VaultScreen
import com.pishi.termbridge.ui.viewmodel.DiscoverViewModel
import com.pishi.termbridge.ui.viewmodel.KnownHostsViewModelFactory
import com.pishi.termbridge.ui.viewmodel.LogsViewModelFactory
import com.pishi.termbridge.ui.screens.BackupScreen
import com.pishi.termbridge.ui.screens.AdvancedScreen
import com.pishi.termbridge.ui.screens.KeychainScreen
import com.pishi.termbridge.ui.viewmodel.KeychainViewModelFactory
import com.pishi.termbridge.ui.screens.CommandsScreen
import com.pishi.termbridge.ui.screens.DiagnosticsScreen
import com.pishi.termbridge.ui.screens.FilesScreen
import com.pishi.termbridge.ui.screens.HostEnvironmentScreen
import com.pishi.termbridge.ui.screens.HostFormScreen
import com.pishi.termbridge.ui.screens.HostsScreen
import com.pishi.termbridge.ui.screens.RemoteDesktopScreen
import com.pishi.termbridge.ui.screens.SessionScreen
import com.pishi.termbridge.ui.screens.SettingsScreen

private data class BottomDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
)

private val bottomDestinations = listOf(
    BottomDestination(Routes.VAULT, R.string.nav_vaults, Icons.Filled.Lock),
    BottomDestination(Routes.CONNECTIONS, R.string.nav_connections, Icons.Filled.Terminal),
    BottomDestination(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings)
)

/** Which bottom tab a route belongs to, so sub-screens keep their tab highlighted. */
private fun tabFor(route: String?): String = when (route) {
    Routes.CONNECTIONS, Routes.SESSION, Routes.FILES, Routes.DESKTOP, Routes.DESKTOP_PREFILL,
    Routes.DISCOVER ->
        Routes.CONNECTIONS
    Routes.SETTINGS, Routes.BACKUP, Routes.DIAGNOSTICS, Routes.ABOUT -> Routes.SETTINGS
    else -> Routes.VAULT
}

private fun titleResFor(route: String?): Int = when (route) {
    Routes.VAULT -> R.string.vault_title
    Routes.CONNECTIONS -> R.string.connections_title
    Routes.KNOWN_HOSTS -> R.string.known_hosts_title
    Routes.KEYCHAIN -> R.string.keychain_title
    Routes.THEMES -> R.string.themes_title
    Routes.LOGS -> R.string.logs_title
    Routes.DISCOVER -> R.string.discover_title
    Routes.HOST_FORM_PREFILL -> R.string.host_form_title
    Routes.HOST_FORM -> R.string.host_form_title
    Routes.HOST_FORM_EDIT -> R.string.host_form_edit_title
    Routes.HOST_ENVIRONMENT -> R.string.env_title
    Routes.SESSION -> R.string.session_title
    Routes.FILES -> R.string.files_title
    Routes.COMMANDS -> R.string.commands_title
    Routes.ADVANCED -> R.string.advanced_title
    Routes.DESKTOP, Routes.DESKTOP_PREFILL -> R.string.desktop_title
    Routes.SETTINGS -> R.string.settings_title
    Routes.BACKUP -> R.string.backup_title
    Routes.DIAGNOSTICS -> R.string.diagnostics_title
    Routes.ABOUT -> R.string.about_title
    else -> R.string.hosts_title
}

/**
 * Prompt 03 app shell: one Scaffold, one NavHost, predictable back
 * behaviour. Back goes to the previous destination: the top-bar
 * arrow dispatches through the activity's OnBackPressedDispatcher
 * (audit #3, A-17) so a screen's BackHandler guard — a dirty
 * editor, an in-flight transfer — gets the first chance, and
 * when no handler consumes the press the NavController's own
 * dispatcher callback pops the stack exactly like navigateUp.
 *
 * Prompt 14 note on the prompt-06 "session-aware back" item: back
 * does NOT kill a live session, because the session ViewModel is
 * activity-scoped (prompt 06) — leaving the Session screen only
 * navigates away, and the session keeps running. A confirmation
 * dialog would therefore be wrong (there is nothing to confirm).
 * What was genuinely missing was a way back: a live session was
 * invisible from every other tab. Prompt 14 adds it — a terminal
 * action in the top bar, shown only while connected and only
 * when not already on the Session screen, that navigates back
 * to the live terminal. The same hoisted ViewModel feeds the
 * Diagnostics screen's live-session line, so that line can never
 * go stale again (it previously claimed no session feature
 * existed).
 *
 * Content uses Scaffold padding (safe insets) plus IME padding,
 * so an open software keyboard does not cover the input field
 * at the bottom of a screen; each screen scrolls vertically, so
 * portrait and landscape both show full content without
 * clipping. No screen here claims a transport works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermBridgeApp(
    repository: HostRepository,
    commandRepository: CommandRepository,
    environmentRepository: HostEnvironmentRepository,
    advancedRepository: AdvancedRepository,
    backupService: BackupService,
    keychainRepository: KeychainRepository,
    snippetRepository: com.pishi.termbridge.data.SnippetRepository,
    customThemeRepository: com.pishi.termbridge.settings.CustomThemeRepository,
    desktopProfileRepository: com.pishi.termbridge.settings.DesktopProfileRepository,
    transcriptStore: com.pishi.termbridge.settings.TranscriptStorage,
    commandHistoryStore: com.pishi.termbridge.settings.CommandHistoryStore,
    appSettings: com.pishi.termbridge.settings.AppSettingsStore,
    connectionLog: com.pishi.termbridge.settings.ConnectionLogStore,
    lockSettings: com.pishi.termbridge.ui.LockSettingsUi,
    onRegisterVolumeKeyHandler: (((com.pishi.termbridge.domain.VolumeKey, Boolean) -> Boolean)?) -> Unit = {},
    navController: NavHostController = rememberNavController()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val canGoBack = navController.previousBackStackEntry != null
    // Audit #3 (A-17): the top-bar arrow goes through the
    // activity back dispatcher (see the shell note above);
    // navigateUp remains the fallback if no dispatcher owner
    // is present (previews).
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    // Immersive terminal (item Q-01): the session screen reports
    // its expanded state; the chrome follows it, and leaving the
    // session route always restores the chrome.
    var sessionExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(currentRoute) {
        if (currentRoute != Routes.SESSION) sessionExpanded = false
    }

    // Prompt 14: the session ViewModel is hoisted here (same
    // activity-scoped instance the Session screen uses, so this
    // creates nothing extra) to drive the live-session top-bar
    // action and the Diagnostics live-session line. Creating it
    // at app start opens no connection — connect() is the only
    // thing that dials, and it is still an explicit tap.
    val activity = LocalContext.current as ComponentActivity
    val sessionViewModel: SessionViewModel = viewModel(
        factory = SessionViewModelFactory(
            repository,
            commandRepository,
            environmentRepository,
            appSettings,
            connectionLog,
            customThemeRepository,
            keychainRepository,
            snippetRepository,
            transcriptStore,
            commandHistoryStore
        ),
        viewModelStoreOwner = activity
    )
    val sessionUiState by sessionViewModel.uiState.collectAsState()
    val liveSession = sessionUiState.status == SessionStatus.CONNECTED
    // Hardware volume keys (item T-19): the activity forwards
    // them here only while the session route is showing; the
    // view model decides (per the user's setting) whether a
    // press is consumed or falls through to system volume.
    DisposableEffect(currentRoute) {
        if (currentRoute == Routes.SESSION) {
            onRegisterVolumeKeyHandler { key, isRepeat ->
                sessionViewModel.onVolumeKey(key, isRepeat)
            }
        } else {
            onRegisterVolumeKeyHandler(null)
        }
        onDispose {
            if (currentRoute == Routes.SESSION) onRegisterVolumeKeyHandler(null)
        }
    }

    // Item Q-06: first-run onboarding gate. Shown until the
    // user finishes or skips it (AppSettings.onboardingSeen);
    // "Add your first host" also lands on the host form once
    // the scaffold composes. All hook calls above this point
    // stay unconditional — the gate only swaps the content.
    val appPrefs by appSettings.settings.collectAsState()
    var startAtHostForm by rememberSaveable { mutableStateOf(false) }
    if (!appPrefs.onboardingSeen) {
        com.pishi.termbridge.ui.screens.OnboardingScreen(
            onFinish = {
                appSettings.update { it.copy(onboardingSeen = true) }
            },
            onAddHost = {
                appSettings.update { it.copy(onboardingSeen = true) }
                startAtHostForm = true
            }
        )
        return
    }
    LaunchedEffect(startAtHostForm) {
        if (startAtHostForm) {
            navController.navigate(Routes.HOST_FORM)
            startAtHostForm = false
        }
    }

    Scaffold(
        topBar = {
            if (!sessionExpanded) {
            TopAppBar(
                title = { Text(text = stringResource(titleResFor(currentRoute))) },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = {
                            backDispatcher?.onBackPressed()
                                ?: navController.navigateUp()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                },
                actions = {
                    // Prompt 14: visible only while a session is
                    // live and the user is not already looking at
                    // it. A real navigation action, not a badge.
                    if (liveSession && currentRoute != Routes.SESSION) {
                        IconButton(onClick = { navController.navigate(Routes.SESSION) }) {
                            Icon(
                                imageVector = Icons.Filled.Terminal,
                                contentDescription = stringResource(
                                    R.string.session_live_indicator
                                )
                            )
                        }
                    }
                }
            )
            }
        },
        bottomBar = {
            // The session is a live-work surface: the tab bar
            // yields its height to the terminal there (Q-01),
            // and in expanded mode all chrome is gone anyway.
            if (currentRoute != Routes.SESSION && !sessionExpanded) {
            NavigationBar {
                bottomDestinations.forEach { destination ->
                    val selected = tabFor(currentRoute) == destination.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(Routes.VAULT) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = null
                            )
                        },
                        label = { Text(text = stringResource(destination.labelRes)) }
                    )
                }
            }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.VAULT,
            modifier = Modifier.padding(innerPadding).imePadding()
        ) {
            composable(Routes.VAULT) {
                VaultScreen(
                    onHosts = { navController.navigate(Routes.HOSTS) },
                    onPortForwarding = { navController.navigate(Routes.ADVANCED) },
                    onSnippets = { navController.navigate(Routes.COMMANDS) },
                    onKnownHosts = { navController.navigate(Routes.KNOWN_HOSTS) },
                    onLogs = { navController.navigate(Routes.LOGS) },
                    onKeychain = { navController.navigate(Routes.KEYCHAIN) }
                )
            }
            composable(Routes.KEYCHAIN) {
                KeychainScreen(
                    viewModel = viewModel(factory = KeychainViewModelFactory(keychainRepository))
                )
            }
            composable(Routes.CONNECTIONS) {
                val recent by connectionLog.entries.collectAsState()
                val keychainEntries by keychainRepository.observeEntries()
                    .collectAsState(initial = emptyList())
                ConnectionsScreen(
                    recent = recent,
                    liveSession = liveSession,
                    keychainEntries = keychainEntries,
                    onQuickConnectPassword = { target, username, password ->
                        sessionViewModel.quickConnect(
                            target, username,
                            com.pishi.termbridge.domain.CredentialInput.Password(password)
                        )
                        navController.navigate(Routes.SESSION)
                    },
                    onQuickConnectKeychain = { target, username, entryId, passphrase ->
                        sessionViewModel.quickConnectWithKeychain(
                            target, username, entryId, passphrase
                        )
                        navController.navigate(Routes.SESSION)
                    },
                    onSaveAsHost = { target ->
                        val text = (target.username?.let { "$it@" } ?: "") +
                            target.host + ":" + target.port
                        navController.navigate(Routes.hostFormPrefillRoute(text))
                    },
                    onAddHost = { navController.navigate(Routes.HOST_FORM) },
                    onSftp = { navController.navigate(Routes.FILES) },
                    onDiscover = { navController.navigate(Routes.DISCOVER) },
                    onDesktop = { navController.navigate(Routes.DESKTOP) },
                    onTerminal = { navController.navigate(Routes.SESSION) }
                )
            }
            composable(Routes.KNOWN_HOSTS) {
                KnownHostsScreen(
                    viewModel = viewModel(factory = KnownHostsViewModelFactory(repository))
                )
            }
            composable(Routes.LOGS) {
                LogsScreen(viewModel = viewModel(factory = LogsViewModelFactory(connectionLog)))
            }
            composable(Routes.DISCOVER) {
                DiscoverScreen(
                    viewModel = viewModel<DiscoverViewModel>(),
                    onPick = { address, port ->
                        if (port == 5900) {
                            navController.navigate(Routes.desktopPrefillRoute(address))
                        } else {
                            navController.navigate(Routes.hostFormPrefillRoute("$address:$port"))
                        }
                    }
                )
            }
            composable(
                route = Routes.HOST_FORM_PREFILL,
                arguments = listOf(navArgument("prefill") { type = NavType.StringType })
            ) { backStackEntry ->
                val target = com.pishi.termbridge.domain.QuickConnect.parse(
                    backStackEntry.arguments?.getString("prefill") ?: ""
                )
                val formViewModel: com.pishi.termbridge.ui.viewmodel.HostFormViewModel =
                    viewModel(factory = HostFormViewModelFactory(repository, null, keychainRepository))
                androidx.compose.runtime.LaunchedEffect(target) {
                    if (target != null) {
                        formViewModel.applyPrefill(target.host, target.port, target.username)
                    }
                }
                HostFormScreen(
                    viewModel = formViewModel,
                    onSaved = { navController.navigateUp() }
                )
            }
            composable(Routes.HOSTS) {
                HostsScreen(
                    viewModel = viewModel(factory = HostsViewModelFactory(repository)),
                    onAddHost = { navController.navigate(Routes.HOST_FORM) },
                    onEditHost = { id -> navController.navigate(Routes.hostFormEditRoute(id)) },
                    onEnvironmentHost = { id ->
                        navController.navigate(Routes.hostEnvironmentRoute(id))
                    },
                    onAdvanced = { navController.navigate(Routes.ADVANCED) },
                    onSessionPreview = { navController.navigate(Routes.SESSION) }
                )
            }
            composable(
                route = Routes.HOST_ENVIRONMENT,
                arguments = listOf(navArgument("hostId") { type = NavType.LongType })
            ) { backStackEntry ->
                val hostId = backStackEntry.arguments?.getLong("hostId") ?: 0L
                HostEnvironmentScreen(
                    viewModel = viewModel(
                        factory = HostEnvironmentViewModelFactory(
                            environmentRepository,
                            repository,
                            hostId,
                            transcriptStore
                        )
                    )
                )
            }
            composable(Routes.HOST_FORM) {
                HostFormScreen(
                    viewModel = viewModel(factory = HostFormViewModelFactory(repository, null, keychainRepository)),
                    onSaved = { navController.navigateUp() }
                )
            }
            composable(
                route = Routes.HOST_FORM_EDIT,
                arguments = listOf(navArgument("hostId") { type = NavType.LongType })
            ) { backStackEntry ->
                val hostId = backStackEntry.arguments?.getLong("hostId") ?: 0L
                HostFormScreen(
                    viewModel = viewModel(factory = HostFormViewModelFactory(repository, hostId, keychainRepository)),
                    onSaved = { navController.navigateUp() }
                )
            }
            composable(Routes.SESSION) {
                // Prompt 06 decision: the session ViewModel is
                // activity-scoped, so a live terminal survives
                // navigating to another tab. Disconnect is explicit.
                // Prompt 14: the instance is hoisted above (same
                // activity owner + factory = same instance).
                SessionScreen(
                    viewModel = sessionViewModel,
                    onAddHost = { navController.navigate(Routes.HOST_FORM) },
                    onTerminalExpandedChange = { sessionExpanded = it }
                )
            }
            composable(Routes.FILES) {
                val context = LocalContext.current
                FilesScreen(
                    viewModel = viewModel(
                        factory = FilesViewModelFactory(
                            repository,
                            environmentRepository,
                            context.contentResolver
                        )
                    ),
                    onAddHost = { navController.navigate(Routes.HOST_FORM) }
                )
            }
            composable(Routes.COMMANDS) {
                CommandsScreen(
                    viewModel = viewModel(
                        factory = CommandsViewModelFactory(
                            repository, commandRepository, snippetRepository
                        )
                    ),
                    onAddHost = { navController.navigate(Routes.HOST_FORM) },
                    onOpenSession = { navController.navigate(Routes.SESSION) }
                )
            }
            composable(Routes.ADVANCED) {
                AdvancedScreen(
                    viewModel = viewModel(
                        factory = AdvancedViewModelFactory(repository, advancedRepository)
                    ),
                    onAddHost = { navController.navigate(Routes.HOST_FORM) }
                )
            }
            composable(Routes.DESKTOP) {
                // Activity-scoped so a running VNC session survives
                // navigating to another screen and back.
                RemoteDesktopScreen(
                    viewModel = viewModel(
                        viewModelStoreOwner = activity,
                        factory = com.pishi.termbridge.ui.viewmodel.RemoteDesktopViewModelFactory(
                            repository, desktopProfileRepository
                        )
                    )
                )
            }
            composable(
                route = Routes.DESKTOP_PREFILL,
                arguments = listOf(navArgument("host") { type = NavType.StringType })
            ) { entry ->
                RemoteDesktopScreen(
                    viewModel = viewModel(
                        viewModelStoreOwner = activity,
                        factory = com.pishi.termbridge.ui.viewmodel.RemoteDesktopViewModelFactory(
                            repository, desktopProfileRepository
                        )
                    ),
                    initialHost = entry.arguments?.getString("host") ?: ""
                )
            }
            composable(Routes.SETTINGS) {
                val currentSettings by appSettings.settings.collectAsState()
                val customs by customThemeRepository.themes.collectAsState()
                SettingsScreen(
                    settings = currentSettings,
                    lock = lockSettings,
                    onChange = { transform -> appSettings.update(transform) },
                    onDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onAbout = { navController.navigate(Routes.ABOUT) },
                    onBackup = { navController.navigate(Routes.BACKUP) },
                    customThemes = customs,
                    onEditCustomThemes = { navController.navigate(Routes.THEMES) },
                    onClearCommandHistory = { commandHistoryStore.clear() }
                )
            }
            composable(Routes.THEMES) {
                val customs by customThemeRepository.themes.collectAsState()
                com.pishi.termbridge.ui.screens.ThemeEditorScreen(
                    customThemes = customs,
                    onUpsert = { theme -> customThemeRepository.upsert(theme) },
                    onDelete = { id -> customThemeRepository.delete(id) }
                )
            }
            composable(Routes.BACKUP) {
                val context = LocalContext.current
                BackupScreen(
                    viewModel = viewModel(
                        factory = BackupViewModelFactory(
                            backupService,
                            context.contentResolver
                        )
                    )
                )
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(hasLiveSession = liveSession)
            }
            composable(Routes.ABOUT) { AboutScreen() }
        }
    }
}

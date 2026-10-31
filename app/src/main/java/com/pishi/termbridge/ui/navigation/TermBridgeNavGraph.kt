package com.pishi.termbridge.ui.navigation

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.activity.ComponentActivity
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
import com.pishi.termbridge.ui.screens.BackupScreen
import com.pishi.termbridge.ui.screens.AdvancedScreen
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
    BottomDestination(Routes.HOSTS, R.string.nav_hosts, Icons.Filled.Home),
    BottomDestination(Routes.FILES, R.string.nav_files, Icons.Filled.Folder),
    BottomDestination(Routes.COMMANDS, R.string.nav_commands, Icons.Filled.Terminal),
    BottomDestination(Routes.DESKTOP, R.string.nav_desktop, Icons.Filled.DesktopWindows),
    BottomDestination(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings)
)

private fun titleResFor(route: String?): Int = when (route) {
    Routes.HOST_FORM -> R.string.host_form_title
    Routes.HOST_FORM_EDIT -> R.string.host_form_edit_title
    Routes.HOST_ENVIRONMENT -> R.string.env_title
    Routes.SESSION -> R.string.session_title
    Routes.FILES -> R.string.files_title
    Routes.COMMANDS -> R.string.commands_title
    Routes.ADVANCED -> R.string.advanced_title
    Routes.DESKTOP -> R.string.desktop_title
    Routes.SETTINGS -> R.string.settings_title
    Routes.BACKUP -> R.string.backup_title
    Routes.DIAGNOSTICS -> R.string.diagnostics_title
    Routes.ABOUT -> R.string.about_title
    else -> R.string.hosts_title
}

/**
 * Prompt 03 app shell: one Scaffold, one NavHost, predictable back
 * behaviour. Back goes to the previous destination via navigateUp.
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
    appSettings: com.pishi.termbridge.settings.AppSettingsStore,
    navController: NavHostController = rememberNavController()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val canGoBack = navController.previousBackStackEntry != null

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
            appSettings
        ),
        viewModelStoreOwner = activity
    )
    val sessionUiState by sessionViewModel.uiState.collectAsState()
    val liveSession = sessionUiState.status == SessionStatus.CONNECTED

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(titleResFor(currentRoute))) },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = { navController.navigateUp() }) {
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
        },
        bottomBar = {
            NavigationBar {
                bottomDestinations.forEach { destination ->
                    val selected = currentRoute == destination.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(Routes.HOSTS) { saveState = true }
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
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOSTS,
            modifier = Modifier.padding(innerPadding).imePadding()
        ) {
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
                            hostId
                        )
                    )
                )
            }
            composable(Routes.HOST_FORM) {
                HostFormScreen(
                    viewModel = viewModel(factory = HostFormViewModelFactory(repository, null)),
                    onSaved = { navController.navigateUp() }
                )
            }
            composable(
                route = Routes.HOST_FORM_EDIT,
                arguments = listOf(navArgument("hostId") { type = NavType.LongType })
            ) { backStackEntry ->
                val hostId = backStackEntry.arguments?.getLong("hostId") ?: 0L
                HostFormScreen(
                    viewModel = viewModel(factory = HostFormViewModelFactory(repository, hostId)),
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
                    onAddHost = { navController.navigate(Routes.HOST_FORM) }
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
                        factory = CommandsViewModelFactory(repository, commandRepository)
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
            composable(Routes.DESKTOP) { RemoteDesktopScreen() }
            composable(Routes.SETTINGS) {
                val currentSettings by appSettings.settings.collectAsState()
                SettingsScreen(
                    settings = currentSettings,
                    onChange = { transform -> appSettings.update(transform) },
                    onDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                    onAbout = { navController.navigate(Routes.ABOUT) },
                    onBackup = { navController.navigate(Routes.BACKUP) }
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

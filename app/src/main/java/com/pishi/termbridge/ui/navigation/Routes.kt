package com.pishi.termbridge.ui.navigation

/**
 * Prompt 03 routes, extended in prompt 04 with the edit route.
 * One string per destination, no duplicates — [RoutesTest] asserts
 * uniqueness and that the start destination is registered. Adding
 * a destination means adding its route here AND its composable in
 * TermBridgeNavGraph; a route without a composable is broken.
 *
 * Edit route note: "host_form/{hostId}" is a distinct pattern from
 * "host_form" (add). Navigation matches the full pattern, so add
 * and edit do not collide.
 */
object Routes {
    const val VAULT = "vault"
    const val CONNECTIONS = "connections"
    const val KNOWN_HOSTS = "known_hosts"
    const val KEYCHAIN = "keychain"
    const val THEMES = "themes"
    const val LOGS = "logs"
    const val DISCOVER = "discover"
    const val HOST_FORM_PREFILL = "host_form_prefill/{prefill}"
    const val HOSTS = "hosts"
    const val HOST_FORM = "host_form"
    const val HOST_FORM_EDIT = "host_form/{hostId}"
    const val HOST_ENVIRONMENT = "host_environment/{hostId}"
    const val SESSION = "session"
    const val FILES = "files"
    const val COMMANDS = "commands"
    const val ADVANCED = "advanced"
    const val DESKTOP = "desktop"
    const val DESKTOP_PREFILL = "desktop_prefill/{host}"
    const val SETTINGS = "settings"
    const val BACKUP = "backup"
    const val DIAGNOSTICS = "diagnostics"
    const val ABOUT = "about"

    fun hostFormPrefillRoute(prefill: String): String =
        "host_form_prefill/" + android.net.Uri.encode(prefill)

    fun desktopPrefillRoute(host: String): String =
        "desktop_prefill/" + android.net.Uri.encode(host)

    fun hostFormEditRoute(hostId: Long): String = "host_form/$hostId"
    fun hostEnvironmentRoute(hostId: Long): String = "host_environment/$hostId"

    val all: List<String> = listOf(
        VAULT, CONNECTIONS, KNOWN_HOSTS, DESKTOP_PREFILL, LOGS, DISCOVER, HOST_FORM_PREFILL,
        HOSTS, HOST_FORM, HOST_FORM_EDIT, HOST_ENVIRONMENT, SESSION, FILES, COMMANDS,
        ADVANCED, DESKTOP, SETTINGS, BACKUP, DIAGNOSTICS, ABOUT, KEYCHAIN, THEMES
    )

    /** Bottom-bar destinations, in display order. */
    val bottomBar: List<String> = listOf(VAULT, CONNECTIONS, SETTINGS)
}

package dev.easyide.app.ui.navigation

/**
 * Single source of truth for nav routes - see docs/ui-shell/arch.md
 * "Navigation flow". Screens reference these constants rather than
 * inlining route strings.
 */
sealed class Destination(val route: String) {
    data object Onboarding : Destination("onboarding")
    data object Home : Destination("home")
    data object Workspace : Destination("workspace/{projectId}?terminal={terminal}") {
        const val ARG_PROJECT_ID = "projectId"

        /** Boolean: reveal the terminal panel on arrival (Home's "Open with terminal"). */
        const val ARG_OPEN_TERMINAL = "terminal"
        fun routeFor(projectId: String, openTerminal: Boolean = false) = "workspace/$projectId?terminal=$openTerminal"
    }
    data object Settings : Destination("settings")
    data object Extensions : Destination("extensions")
    data object Diagnostics : Destination("diagnostics")
    data object CredentialVault : Destination("credential_vault")
    data object NewProject : Destination("new_project")

    /** Install Linux outside first-run: what Home's prompt opens. */
    data object InstallLinux : Destination("install_linux")
}

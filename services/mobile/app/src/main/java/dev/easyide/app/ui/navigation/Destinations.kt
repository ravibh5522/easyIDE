package dev.easyide.app.ui.navigation

/**
 * Single source of truth for nav routes - see docs/ui-shell/arch.md
 * "Navigation flow". Screens reference these constants rather than
 * inlining route strings.
 */
sealed class Destination(val route: String) {
    data object Onboarding : Destination("onboarding")
    data object Home : Destination("home")
    data object Workspace : Destination("workspace/{projectId}") {
        const val ARG_PROJECT_ID = "projectId"
        fun routeFor(projectId: String) = "workspace/$projectId"
    }
    data object Settings : Destination("settings")
    data object CredentialVault : Destination("credential_vault")
    data object NewProject : Destination("new_project")
}

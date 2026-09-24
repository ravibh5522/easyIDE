package dev.easyide.app.ui.devtools

import androidx.compose.foundation.lazy.LazyListScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder

/**
 * Release twin of src/devtools: developer routes and entry points do not exist in a shipped
 * build, so there is nothing for R8 to keep and nothing a user can reach.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.devToolsRoutes(nav: NavController) = Unit

@Suppress("UNUSED_PARAMETER")
fun LazyListScope.devToolsEntries(nav: NavController) = Unit

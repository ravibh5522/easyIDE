package dev.easyide.app.ui.screens.home

/**
 * Row shape for the Home project list. Populated from the Room DB project
 * table described in docs/sandbox-runtime/arch.md "Multi-project session
 * mapping" - this is just the read-side view model shape, not the DB entity.
 */
data class ProjectSummary(
    val id: String,
    val displayName: String,
    val path: String,
    val gitBranch: String?,
    val hasUncommittedChanges: Boolean,
)

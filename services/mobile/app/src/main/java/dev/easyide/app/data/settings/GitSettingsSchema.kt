package dev.easyide.app.data.settings

import dev.easyide.app.R
import dev.easyide.sandbox.git.PullStrategy

/**
 * The `git.*` settings the source-control panel reads. Identity and pull
 * strategy are project-overridable (scope P): a work repository and a personal
 * one legitimately commit as different people. Auto-fetch is a device
 * preference, so global only.
 */
object GitSettingsSchema {

    /** Blank means "not set": the first commit then asks, see `WorkspaceGitController.commit`. */
    val userName = Setting.Str(
        key = "git.userName",
        category = SettingCategory.GIT,
        title = R.string.setting_git_user_name_title,
        description = R.string.setting_git_user_name_desc,
        default = "",
        scope = SettingScope.P,
    )

    val userEmail = Setting.Str(
        key = "git.userEmail",
        category = SettingCategory.GIT,
        title = R.string.setting_git_user_email_title,
        description = R.string.setting_git_user_email_desc,
        default = "",
        scope = SettingScope.P,
    )

    val pullStrategy = Setting.Enum(
        key = "git.pullStrategy",
        category = SettingCategory.GIT,
        title = R.string.setting_git_pull_strategy_title,
        description = R.string.setting_git_pull_strategy_desc,
        default = PullStrategy.FF_ONLY,
        scope = SettingScope.P,
        values = PullStrategy.entries,
        label = ::pullStrategyLabel,
    )

    /** Off by default: a background fetch spends the user's data and battery. Foreground only. */
    val autoFetch = Setting.Bool(
        key = "git.autoFetch",
        category = SettingCategory.GIT,
        title = R.string.setting_git_auto_fetch_title,
        description = R.string.setting_git_auto_fetch_desc,
        default = false,
        scope = SettingScope.G,
    )

    val all: List<Setting<*>> = listOf(userName, userEmail, pullStrategy, autoFetch)

    private fun pullStrategyLabel(strategy: PullStrategy): Int = when (strategy) {
        PullStrategy.MERGE -> R.string.git_pull_strategy_merge
        PullStrategy.REBASE -> R.string.git_pull_strategy_rebase
        PullStrategy.FF_ONLY -> R.string.git_pull_strategy_ff_only
    }
}

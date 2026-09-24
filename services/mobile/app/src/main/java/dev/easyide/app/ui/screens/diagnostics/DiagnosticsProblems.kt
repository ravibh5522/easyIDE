package dev.easyide.app.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.easyide.app.R
import dev.easyide.app.diagnostics.CrashSummary
import dev.easyide.app.diagnostics.LogFormat
import dev.easyide.app.diagnostics.LogLevel
import dev.easyide.app.diagnostics.LogLine
import dev.easyide.app.ui.components.MonoText
import dev.easyide.app.ui.components.ProseText
import dev.easyide.app.ui.kit.Kit
import dev.easyide.app.ui.kit.KitButton
import dev.easyide.app.ui.kit.KitButtonStyle
import dev.easyide.app.ui.kit.KitRow
import dev.easyide.app.ui.kit.KitSection
import dev.easyide.app.ui.kit.KitTag
import dev.easyide.app.ui.kit.Tone
import java.text.DateFormat
import java.util.Date

/** A log line is one entry; a long one shows this many lines and the rest is in the exported log. */
private const val LOG_LINE_MAX_LINES = 3

internal fun LazyListScope.problemsSection(crash: CrashSummary?, problems: List<LogLine>) {
    item(key = "problems") {
        KitSection(stringResource(R.string.diag_section_problems)) {
            if (problems.isEmpty() && crash == null) KitRow(stringResource(R.string.diag_problems_none))
            crash?.let { CrashRow(it) }
            if (problems.isNotEmpty()) {
                KitRow(stringResource(R.string.diag_last_errors))
                Column(Modifier.padding(horizontal = Kit.space.l, vertical = Kit.space.s), verticalArrangement = Arrangement.spacedBy(Kit.space.xs)) {
                    problems.forEach { ProblemLine(it) }
                }
            }
        }
    }
}

@Composable
private fun CrashRow(crash: CrashSummary) {
    val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(crash.atMs))
    KitRow(
        title = stringResource(R.string.diag_last_crash),
        subtitle = whenText,
        trailing = if (crash.pending) {
            { KitTag(stringResource(R.string.diag_last_crash_new), tone = Tone.Warning) }
        } else null,
    )
    MonoText(crash.headline, Modifier.padding(horizontal = Kit.space.l, vertical = Kit.space.s), tone = Tone.Danger, maxLines = LOG_LINE_MAX_LINES)
}

@Composable
private fun ProblemLine(line: LogLine) {
    MonoText(LogFormat.encode(line), tone = if (line.level == LogLevel.ERROR) Tone.Danger else Tone.Warning, maxLines = LOG_LINE_MAX_LINES)
}

internal fun LazyListScope.logsSection(enabled: Boolean, actions: DiagnosticsActions) {
    item(key = "logs") {
        KitSection(stringResource(R.string.diag_section_logs)) {
            ProseText(stringResource(R.string.diag_logs_body), Modifier.padding(Kit.space.l), muted = true)
            ActionRow {
                KitButton(stringResource(R.string.diag_share), actions.onShare, enabled = enabled)
                KitButton(stringResource(R.string.diag_export), actions.onExport, style = KitButtonStyle.Secondary, enabled = enabled)
            }
        }
    }
}

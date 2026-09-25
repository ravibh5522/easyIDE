package dev.easyide.app.ui.shell.workspace

import dev.easyide.app.R
import dev.easyide.app.ui.commands.Command
import dev.easyide.app.ui.commands.CommandIds
import dev.easyide.app.ui.shell.CoreShell
import dev.easyide.app.ui.shell.OpenBeside
import dev.easyide.app.ui.shell.ShellAction
import dev.easyide.app.ui.shell.ShellState

/** Palette commands the workspace shell adds; ids of the two workspace exits are the navigation items' targets. */
object WorkspaceCommandIds {
    const val LAYOUT_PRESET = "easyide.layout.choosePreset"
}

/**
 * The workspace's own commands: the two ways out of the project (which the rail's actions run), the
 * layout picker, and the stage's (open to the side, split, close a group, move a document to the next
 * group). They only route to the model or the screen, as every command does.
 */
fun workspaceShellCommands(
    state: ShellState?,
    model: WorkspaceShellModel,
    stageActions: WorkspaceStageActions,
    back: () -> Unit,
    close: () -> Unit,
    choosePreset: () -> Unit,
): List<Command> {
    val stage = state?.current?.stage
    val room = stage != null && state.groupCapacity > stage.groups.size
    return listOf(
        Command(CoreShell.BACK_TO_PROJECTS_COMMAND, R.string.wshell_command_back, run = back),
        Command(CoreShell.CLOSE_PROJECT_COMMAND, R.string.wshell_command_close, run = close),
        Command(WorkspaceCommandIds.LAYOUT_PRESET, R.string.wshell_command_layout, run = choosePreset),
        Command(CommandIds.OPEN_TO_SIDE, R.string.wshell_command_open_side, enabled = stage?.activeGroup?.active != null, run = stageActions::openActiveBeside),
        Command(CommandIds.SPLIT_EDITOR, R.string.wshell_command_split, enabled = room) { model.dispatch(ShellAction.Split) },
        Command(CommandIds.CLOSE_GROUP, R.string.wshell_command_unsplit, enabled = (stage?.groups?.size ?: 1) > 1) {
            model.dispatch(ShellAction.Unsplit(model.state.value?.current?.stage?.active ?: 0))
        },
        Command(
            CommandIds.MOVE_EDITOR_TO_NEXT_GROUP, R.string.wshell_command_move_next,
            enabled = stage != null && OpenBeside.canMoveToNext(stage), run = stageActions::moveActiveToNextGroup,
        ),
    )
}

package dev.easyide.sandbox.shell

/**
 * Everything needed to start a *real*, pty-backed interactive shell:
 * `com.termux.terminal.TerminalSession`'s constructor takes exactly these
 * four things. Kept separate from [dev.easyide.sandbox.backend.LaunchSpec]
 * so this module has no dependency on the terminal-emulator library - the UI
 * layer is what actually constructs the `TerminalSession`, since that is
 * where a `TerminalSessionClient` (an inherently UI-facing callback
 * interface) has to come from.
 */
data class PtyShellParams(
    val shellPath: String,
    val args: List<String>,
    val env: Map<String, String>,
    val cwd: String,
)

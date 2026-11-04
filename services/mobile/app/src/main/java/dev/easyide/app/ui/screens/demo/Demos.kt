package dev.easyide.app.ui.screens.demo

import androidx.annotation.StringRes
import dev.easyide.app.R
import java.util.concurrent.ConcurrentHashMap

/**
 * The first-run demo programs: one declarative list, so adding a language is an entry here and nothing
 * else. Each is a one-file project that runs in the terminal. [command] installs the interpreter first
 * when the chosen Linux preset did not ship it (the demo runs in a root shell, see
 * `WorkspaceTerminals.newShell`), so it works on any preset.
 */
data class DemoSpec(
    val id: String,
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    val projectName: String,
    val file: String,
    val source: String,
    val command: String,
)

object Demos {
    val ALL: List<DemoSpec> = listOf(
        DemoSpec(
            id = "python",
            title = R.string.demo_python_title,
            subtitle = R.string.demo_python_subtitle,
            projectName = "Hello Python",
            file = "hello.py",
            source = """
                def greet(name):
                    return f"Hello, {name}! Python is running on your tablet."


                for who in ["easyIDE", "world"]:
                    print(greet(who))
            """.trimIndent() + "\n",
            command = "command -v python3 >/dev/null || { apt-get update && apt-get install -y python3; }; python3 hello.py",
        ),
        DemoSpec(
            id = "node",
            title = R.string.demo_node_title,
            subtitle = R.string.demo_node_subtitle,
            projectName = "Hello Node",
            file = "hello.js",
            source = """
                const greet = (name) => `Hello, ${'$'}{name}! Node.js is running on your tablet.`;

                for (const who of ["easyIDE", "world"]) {
                  console.log(greet(who));
                }
            """.trimIndent() + "\n",
            command = "command -v node >/dev/null || easyide-install-node; node hello.js",
        ),
    )
}

/** What the workspace does once on arrival from the demo: show [file], then run [command] in a terminal. */
data class DemoRun(val file: String, val command: String)

/** Hands a [DemoRun] from the demo screen to the workspace it opens; taken once, so reopening the project later does not rerun it. */
class DemoHandoff {
    private val pending = ConcurrentHashMap<String, DemoRun>()

    fun put(projectId: String, run: DemoRun) {
        pending[projectId] = run
    }

    fun take(projectId: String): DemoRun? = pending.remove(projectId)
}

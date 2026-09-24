package dev.easyide.app

import android.app.Application
import dev.easyide.app.diagnostics.CrashHandler
import dev.easyide.app.ui.screens.workspace.syntax.TextMateHighlighter

class EasyIdeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Before anything that can crash: an uncaught exception is written to disk, then handled as usual.
        CrashHandler.install(container.crashReports, container.appLog, container.buildInfo)
        TextMateHighlighter.init(this)
        // Before any activity: the crash-journal verdict must hold before contributions register.
        container.extensions.start()
        SafeModeShortcut.publish(this)
    }

    /** Language servers shed memory before Android kills the app (lsp-lifecycle.md 3.2). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        container.lsp.onTrimMemory(level)
        container.workspaces.onTrimMemory(level)
    }
}

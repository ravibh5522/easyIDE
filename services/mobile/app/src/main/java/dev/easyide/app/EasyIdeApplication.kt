package dev.easyide.app

import android.app.Application
import dev.easyide.app.ui.screens.workspace.syntax.TextMateHighlighter

class EasyIdeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        TextMateHighlighter.init(this)
        SafeModeShortcut.publish(this)
    }

    /** Language servers shed memory before Android kills the app (lsp-lifecycle.md 3.2). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        container.lsp.onTrimMemory(level)
    }
}

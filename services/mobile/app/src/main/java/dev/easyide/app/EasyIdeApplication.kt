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
    }
}

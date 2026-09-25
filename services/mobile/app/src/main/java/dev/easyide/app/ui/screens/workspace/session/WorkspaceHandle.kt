package dev.easyide.app.ui.screens.workspace.session

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.easyide.app.session.HeldSession
import dev.easyide.app.ui.screens.workspace.WorkspaceViewModel

/**
 * A [WorkspaceViewModel] kept by the app-scoped registry instead of a navigation entry: it
 * lives in a [ViewModelStore] of its own, so it stays alive when the screen goes away and
 * ends (`onCleared`: shells killed, watchers stopped, servers released) when the store is cleared.
 */
class WorkspaceHandle private constructor(
    private val store: ViewModelStore,
    val viewModel: WorkspaceViewModel,
) : HeldSession {

    override suspend fun saveBackup() = viewModel.saveSession()

    override fun onParked() = viewModel.onParked()

    override fun onResumed() = viewModel.onResumed()

    override fun end(discardStored: Boolean) {
        viewModel.endSession(discardStored)
        store.clear()
    }

    companion object {
        fun open(factory: ViewModelProvider.Factory): WorkspaceHandle {
            val store = ViewModelStore()
            return WorkspaceHandle(store, ViewModelProvider(store, factory)[WorkspaceViewModel::class.java])
        }
    }
}

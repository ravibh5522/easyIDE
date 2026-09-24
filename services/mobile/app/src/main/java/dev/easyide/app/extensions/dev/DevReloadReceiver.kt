package dev.easyide.app.extensions.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.easyide.app.EasyIdeApplication

/**
 * `adb shell am broadcast -a dev.easyide.app.action.DEV_RELOAD -n <app>/<this> --es id <id>`
 * from `easyide-ext dev`. The manifest guards it with `android.permission.DUMP`, which the
 * shell user holds and no installable app can, so only adb can send it; [DevInstaller] acts
 * only while `extensions.developerMode` is on.
 */
class DevReloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DevReload.ACTION) return
        val app = context.applicationContext as? EasyIdeApplication ?: return
        val result = goAsync()
        app.container.extensions.dev.onReload(intent.getStringExtra(DevReload.EXTRA_ID)) { result.finish() }
    }
}

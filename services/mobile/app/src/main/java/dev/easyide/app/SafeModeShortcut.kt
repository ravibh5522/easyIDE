package dev.easyide.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * The launcher long-press "Start in safe mode" (docs/extension-sdk/lld/customization.md
 * sec 11): the way back in when an extension or a setting makes a normal launch
 * unusable. Dynamic rather than a static shortcuts.xml because a static intent
 * must name the package, which differs between the debug/release and canary builds.
 */
object SafeModeShortcut {

    private const val ID = "safe_mode"

    fun publish(context: Context) {
        val intent = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SAFE_MODE)
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(context.getString(R.string.shortcut_safe_mode_short))
            .setLongLabel(context.getString(R.string.shortcut_safe_mode_long))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }
}

package dev.easyide.app.ui.kit

import androidx.compose.runtime.Immutable

/** A labelled command: the one action of an empty state, a banner or a dialog button. */
@Immutable
class KitAction(val label: String, val onClick: () -> Unit)

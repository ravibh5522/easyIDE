package dev.easyide.app.ui.kit

import dev.easyide.app.ui.props.Motif

/**
 * What `appearance.motif` allows (identity.md 2.2). The functional cursor states are not gated
 * here: they mean state, so they draw at every level.
 */
internal val Motif.drawsSupporting: Boolean get() = this != Motif.OFF

/** ASCII empty-state art (and ticks) draw only at the full level. */
internal val Motif.drawsArt: Boolean get() = this == Motif.FULL

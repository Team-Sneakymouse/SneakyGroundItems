package com.danidipp.sneakygrounditems.display

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

internal object GroundItemDisplayText {
    fun stolenItem(): Component = Component.text("Stolen Item", NamedTextColor.DARK_RED)
}

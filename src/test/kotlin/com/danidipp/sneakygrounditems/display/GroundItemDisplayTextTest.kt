package com.danidipp.sneakygrounditems.display

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import kotlin.test.Test
import kotlin.test.assertEquals

class GroundItemDisplayTextTest {
    @Test
    fun stolenItemUsesFixedDarkRedLabel() {
        assertEquals(
            Component.text("Stolen Item", NamedTextColor.DARK_RED),
            GroundItemDisplayText.stolenItem(),
        )
    }
}

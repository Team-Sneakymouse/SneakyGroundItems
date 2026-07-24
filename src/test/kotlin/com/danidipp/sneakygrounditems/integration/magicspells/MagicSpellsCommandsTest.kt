package com.danidipp.sneakygrounditems.integration.magicspells

import kotlin.test.Test
import kotlin.test.assertEquals

class MagicSpellsCommandsTest {
    @Test
    fun shopliftCostTargetsTheThief() {
        assertEquals(
            "ms cast as SneakyThief item-goo-shopliftcost",
            MagicSpellsCommands.shopliftCost("SneakyThief"),
        )
    }
}

package com.danidipp.sneakygrounditems.integration.magicspells

import com.danidipp.sneakygrounditems.command.Command
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PriceGunDialogTest {
    @Test
    fun `parses plain and separated numbers`() {
        assertEquals(250, PriceGunDialog.parsePrice("250"))
        assertEquals(1_250_000, PriceGunDialog.parsePrice(" 1,250,000 "))
        assertEquals(1500, PriceGunDialog.parsePrice("1 500"))
        assertEquals(0, PriceGunDialog.parsePrice("000"))
    }

    @Test
    fun `caps at the sell limit`() {
        assertEquals(Command.MAX_SELL_PRICE, PriceGunDialog.parsePrice("9999999999999"))
    }

    @Test
    fun `rejects non numbers`() {
        assertNull(PriceGunDialog.parsePrice(""))
        assertNull(PriceGunDialog.parsePrice("-5"))
        assertNull(PriceGunDialog.parsePrice("12g"))
    }
}

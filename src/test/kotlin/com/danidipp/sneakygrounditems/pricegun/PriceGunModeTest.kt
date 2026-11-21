package com.danidipp.sneakygrounditems.pricegun

import com.danidipp.sneakygrounditems.command.Command
import com.danidipp.sneakygrounditems.commerce.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PriceGunModeTest {
    @Test
    fun `stored mode ids round-trip`() {
        for (mode in PriceGunMode.entries) assertEquals(mode, PriceGunMode.fromId(mode.id))
        assertNull(PriceGunMode.fromId("diamond"))
    }

    @Test
    fun `currency modes resolve from a sale currency`() {
        assertEquals(PriceGunMode.SILVER, PriceGunMode.forCurrency(Currency.SILVER))
        assertNull(PriceGunMode.forCurrency(Currency.DIAMOND))
    }

    @Test
    fun `a gun without settings starts on gold at the default price`() {
        val settings = PriceGunSettings()
        assertEquals(PriceGunMode.GOLD, settings.mode)
        for (currency in listOf(Currency.GOLD, Currency.SILVER, Currency.PENNY)) {
            assertEquals(PriceGunSettings.DEFAULT_PRICE, settings.price(currency))
        }
    }

    @Test
    fun `prices are set per currency and clamped`() {
        val settings = PriceGunSettings()
            .withPrice(Currency.SILVER, 250)
            .withPrice(Currency.PENNY, 0)
            .withPrice(Currency.GOLD, Long.MAX_VALUE)
        assertEquals(250, settings.price(Currency.SILVER))
        assertEquals(PriceGunSettings.MIN_PRICE, settings.price(Currency.PENNY))
        assertEquals(Command.MAX_SELL_PRICE, settings.price(Currency.GOLD))
    }
}

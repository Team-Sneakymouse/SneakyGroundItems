package com.danidipp.sneakygrounditems.commerce

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CurrencyTest {
    @Test
    fun fromIdResolvesKnownCurrency() {
        assertEquals(Currency.GOLD, Currency.fromId("gold"))
    }

    @Test
    fun fromIdReturnsNullForUnknownCurrency() {
        assertNull(Currency.fromId("credits"))
    }

    @Test
    fun toStringCapitalizesId() {
        assertEquals("Mythril", Currency.MYTHRIL.toString())
    }
}

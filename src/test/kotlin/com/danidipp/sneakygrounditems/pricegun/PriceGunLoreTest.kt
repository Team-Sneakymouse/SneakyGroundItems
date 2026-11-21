package com.danidipp.sneakygrounditems.pricegun

import com.danidipp.sneakygrounditems.commerce.Currency
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class PriceGunLoreTest {
    private val definition = listOf("Value: 1000 Gold", "One gun for pricing", "", "Mode: Gold", "Price: 5 Gold")
        .map(Component::text)

    private fun plain(lore: List<Component>) = lore.map(PlainTextComponentSerializer.plainText()::serialize)

    @Test
    fun `replaces the settings block after the last blank line`() {
        val settings = PriceGunSettings().withMode(PriceGunMode.SILVER).withPrice(Currency.SILVER, 250)
        assertEquals(
            listOf("Value: 1000 Gold", "One gun for pricing", "", "Mode: Silver", "Price: 250 Silver"),
            plain(PriceGunLore.build(definition, settings)),
        )
    }

    @Test
    fun `lock modes show no price`() {
        assertEquals(
            listOf("Value: 1000 Gold", "One gun for pricing", "", "Mode: Lock"),
            plain(PriceGunLore.build(definition, PriceGunSettings(PriceGunMode.LOCK))),
        )
    }

    @Test
    fun `defaults reproduce the definition's lore`() {
        assertEquals(plain(definition), plain(PriceGunLore.build(definition, PriceGunSettings())))
    }

    @Test
    fun `a definition without a blank line keeps all its lore`() {
        val bare = listOf(Component.text("Value: 1000 Gold"))
        assertEquals(
            listOf("Value: 1000 Gold", "", "Mode: Gold", "Price: 5 Gold"),
            plain(PriceGunLore.build(bare, PriceGunSettings())),
        )
    }
}

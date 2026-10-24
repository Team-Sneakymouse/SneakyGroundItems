package com.danidipp.sneakygrounditems.display.labels

import com.danidipp.sneakygrounditems.commerce.Currency
import com.danidipp.sneakygrounditems.model.GroundItemState
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.block.BlockFace
import kotlin.test.*

class GroundItemLabelTest {
    @Test fun stolenNoticeDoesNotRequireSaleRestorationData() {
        val label = GroundItemLabel.derive(GroundItemState.STOLEN, Component.empty(), 1, null, null, BlockFace.DOWN)
        assertEquals(GroundItemLabel(Component.text("Stolen Item", NamedTextColor.DARK_RED), BlockFace.DOWN), label)
    }

    @Test fun normalAndMalformedSaleDataDoNotProduceLabels() {
        assertNull(GroundItemLabel.derive(GroundItemState.NORMAL, Component.empty(), 1, null, null, BlockFace.UP))
        assertNull(GroundItemLabel.derive(GroundItemState.SALE, Component.empty(), 1, null, 10, BlockFace.UP))
        assertNull(GroundItemLabel.derive(GroundItemState.PAYMENT, Component.empty(), 1, Currency.entries.first(), -1, BlockFace.UP))
    }

    @Test fun saleKeepsQuantityNameAndRoundedEachPrice() {
        val currency = Currency.entries.first()
        val label = GroundItemLabel.derive(GroundItemState.SALE, Component.text("Bread"), 3, currency, 10, BlockFace.NORTH)!!
        val cost = Component.text("10 ", NamedTextColor.GOLD)
            .append(Component.text(currency.toString(), NamedTextColor.GOLD))
            .append(Component.text(" (", NamedTextColor.YELLOW))
            .append(Component.text("3.3", NamedTextColor.GOLD))
            .append(Component.text(" each)", NamedTextColor.YELLOW))
        assertEquals(Component.text("3x ").append(Component.text("Bread")).append(Component.newline()).append(cost), label.text)
        assertEquals(BlockFace.NORTH, label.face)
    }
}

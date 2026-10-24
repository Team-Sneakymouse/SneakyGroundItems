package com.danidipp.sneakygrounditems.display.labels

import com.danidipp.sneakygrounditems.commerce.Currency
import com.danidipp.sneakygrounditems.model.GroundItemState
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.block.BlockFace
import java.math.BigDecimal
import java.math.RoundingMode

/** An immutable projection, with no live entity or persistence ownership. */
internal data class GroundItemLabel(val text: Component, val face: BlockFace) {
    companion object {
        fun derive(
            state: GroundItemState,
            name: Component,
            amount: Int,
            currency: Currency?,
            price: Int?,
            face: BlockFace,
        ): GroundItemLabel? {
            if (state == GroundItemState.NORMAL) return null
            if (state == GroundItemState.STOLEN) {
                return GroundItemLabel(Component.text("Stolen Item", NamedTextColor.DARK_RED), face)
            }
            if (currency == null || price == null || price < 0 || amount <= 0) return null
            if (state == GroundItemState.PAYMENT) return GroundItemLabel(Component.text("$price $currency"), face)

            var cost = Component.text("$price ", NamedTextColor.GOLD)
                .append(Component.text(currency.toString(), NamedTextColor.GOLD))
            if (amount > 1) {
                val each = BigDecimal.valueOf(price.toLong()).divide(BigDecimal.valueOf(amount.toLong()), 1, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString()
                cost = cost.append(Component.text(" (", NamedTextColor.YELLOW))
                    .append(Component.text(each, NamedTextColor.GOLD))
                    .append(Component.text(" each)", NamedTextColor.YELLOW))
            }
            return GroundItemLabel(Component.text("${amount}x ").append(name).append(Component.newline()).append(cost), face)
        }
    }
}

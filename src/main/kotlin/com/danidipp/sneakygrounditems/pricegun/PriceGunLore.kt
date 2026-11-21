package com.danidipp.sneakygrounditems.pricegun

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * A gun's lore: the definition's lore up to its last blank line, then a block showing the gun's settings. The
 * definition ends with that block filled in with the defaults, and `ignored-attributes: [lore]` keeps each gun's copy.
 */
internal object PriceGunLore {
    /** [definitionLore] with everything after its last blank line replaced by [settings]. */
    fun build(definitionLore: List<Component>, settings: PriceGunSettings): List<Component> {
        val lastBlank = definitionLore.indexOfLast { PlainTextComponentSerializer.plainText().serialize(it).isBlank() }
        val description = if (lastBlank >= 0) definitionLore.subList(0, lastBlank) else definitionLore
        return description + Component.empty() + settingsLines(settings)
    }

    fun settingsLines(settings: PriceGunSettings): List<Component> {
        val mode = settings.mode
        return buildList {
            add(Component.text("Mode: ", NamedTextColor.GRAY).append(Component.text(mode.label, mode.color)))
            mode.currency?.let { currency ->
                add(
                    Component.text("Price: ", NamedTextColor.GRAY)
                        .append(Component.text("${settings.price(currency)} $currency", NamedTextColor.YELLOW)),
                )
            }
        }.map { it.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE) }
    }
}

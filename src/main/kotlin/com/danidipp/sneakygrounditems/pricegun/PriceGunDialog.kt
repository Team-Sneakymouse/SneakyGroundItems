package com.danidipp.sneakygrounditems.pricegun

import com.danidipp.sneakygrounditems.command.Command
import com.danidipp.sneakygrounditems.commerce.Currency
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * The Price Gun menu's "type an exact price" dialog for the gun in [gunSlot]. Either button returns to the menu
 * through [back].
 */
internal object PriceGunDialog {
    /** Digits only, ignoring spaces, commas, dots and underscores used as separators; capped at the sell limit. */
    fun parsePrice(raw: String): Int? {
        val digits = raw.trim().filterNot { it == ' ' || it == ',' || it == '.' || it == '_' }
        if (digits.isEmpty() || !digits.all(Char::isDigit)) return null
        return digits.trimStart('0').ifEmpty { "0" }
            .let { if (it.length > 10) Command.MAX_SELL_PRICE.toLong() else it.toLong() }
            .coerceAtMost(Command.MAX_SELL_PRICE.toLong())
            .toInt()
    }

    fun show(player: Player, gunSlot: Int, currency: Currency, back: (Player) -> Unit, error: Component? = null) {
        val gun = PriceGunSettings.gunAt(player, gunSlot) ?: return
        val current = PriceGunSettings.of(gun).price(currency)
        val body = buildList {
            add(DialogBody.plainMessage(Component.text("Type the $currency price this gun sets.")))
            if (error != null) add(DialogBody.plainMessage(error))
        }
        val confirm = ActionButton.create(
            Component.text("Set price", NamedTextColor.GREEN), Component.text("Use this $currency price"), 100,
            DialogAction.customClick({ response, audience ->
                val who = audience as? Player ?: return@customClick
                val price = parsePrice(response.getText("price").orEmpty())
                if (price == null || price < PriceGunSettings.MIN_PRICE) {
                    show(who, gunSlot, currency, back, Component.text("Type a whole number of at least 1.", NamedTextColor.RED))
                    return@customClick
                }
                if (PriceGunSettings.update(who, gunSlot) { it.withPrice(currency, price.toLong()) } != null) {
                    PriceGunEffects.playPriceSet(who)
                    back(who)
                }
            }, ClickCallback.Options.builder().uses(1).build()),
        )
        val cancel = ActionButton.create(
            Component.text("Cancel"), null, 100,
            DialogAction.customClick({ _, audience ->
                (audience as? Player)?.let(back)
            }, ClickCallback.Options.builder().uses(1).build()),
        )
        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(Component.text("Set $currency price", NamedTextColor.GOLD))
                        .canCloseWithEscape(true)
                        .body(body)
                        .inputs(
                            listOf(
                                DialogInput.text("price", Component.text("Price ($currency)"))
                                    .initial(current.toString())
                                    .maxLength(13)
                                    .build(),
                            ),
                        )
                        .build(),
                )
                .type(DialogType.confirmation(confirm, cancel))
        }
        player.showDialog(dialog)
    }
}

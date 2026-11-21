package com.danidipp.sneakygrounditems.pricegun

import com.danidipp.sneakygrounditems.command.Command
import com.danidipp.sneakygrounditems.commerce.Currency
import com.sneakyitems.api.SneakyItemsAPI
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * A Price Gun's mode and price per currency. Stored on the gun itself (per-copy PDC, kept by SneakyItems rebuilds),
 * so handing a gun to someone hands over its settings. A gun without them uses the defaults.
 */
internal data class PriceGunSettings(
    val mode: PriceGunMode = PriceGunMode.GOLD,
    val prices: Map<Currency, Int> = emptyMap(),
) {
    fun price(currency: Currency): Int = prices[currency] ?: DEFAULT_PRICE

    fun withMode(mode: PriceGunMode) = copy(mode = mode)

    fun withPrice(currency: Currency, price: Long) = copy(prices = prices + (currency to clampPrice(price)))

    /** Writes these settings onto [gun]; returns false when the stack can't hold data. */
    fun writeTo(gun: ItemStack): Boolean = gun.editPersistentDataContainer { pdc ->
        pdc.set(MODE_KEY, PersistentDataType.STRING, mode.id)
        for ((currency, price) in prices) pdc.set(priceKey(currency), PersistentDataType.INTEGER, price)
    }

    companion object {
        const val DEFAULT_PRICE = 5
        const val MIN_PRICE = 1
        val MODE_KEY = NamespacedKey("sneakygrounditems", "pricegun_mode")

        fun priceKey(currency: Currency) = NamespacedKey("sneakygrounditems", "pricegun_price_${currency.id}")

        fun of(gun: ItemStack): PriceGunSettings {
            val pdc = gun.persistentDataContainer
            val mode = pdc.get(MODE_KEY, PersistentDataType.STRING)?.let(PriceGunMode::fromId) ?: PriceGunMode.GOLD
            val prices = PriceGunMode.entries.mapNotNull { it.currency }.mapNotNull { currency ->
                pdc.get(priceKey(currency), PersistentDataType.INTEGER)?.let { currency to clampPrice(it.toLong()) }
            }.toMap()
            return PriceGunSettings(mode, prices)
        }

        /** The Price Gun in [slot] of the player's inventory, or null when that slot holds something else now. */
        fun gunAt(player: Player, slot: Int): ItemStack? =
            player.inventory.getItem(slot)?.takeIf { SneakyItemsAPI.isItem(it, PriceGunService.ITEM_ID) }

        /** Applies [change] to the settings of the gun in [slot]; null when the gun is no longer there. */
        fun update(player: Player, slot: Int, change: (PriceGunSettings) -> PriceGunSettings): PriceGunSettings? {
            val gun = gunAt(player, slot)?.clone() ?: return null
            val settings = change(of(gun))
            settings.writeTo(gun)
            refreshLore(gun, settings)
            player.inventory.setItem(slot, gun)
            return settings
        }

        /**
         * Splits an over-stacked gun in [slot] into single guns. Old single-mode guns stacked to 99; converted stacks
         * keep their amount although the Price Gun stacks to 1. Extras go to free slots or drop at the player's feet.
         */
        fun unstack(player: Player, slot: Int) {
            val gun = gunAt(player, slot) ?: return
            if (gun.amount <= 1) return
            val extra = gun.clone().apply { amount = 1 }
            val count = gun.amount - 1
            player.inventory.setItem(slot, gun.clone().apply { amount = 1 })
            repeat(count) {
                player.inventory.addItem(extra.clone()).values.forEach { player.world.dropItem(player.location, it) }
            }
        }

        /** Rewrites [gun]'s lore from the current definition and [settings]; leaves it alone if the definition is gone. */
        private fun refreshLore(gun: ItemStack, settings: PriceGunSettings) {
            val definitionLore = SneakyItemsAPI.getItemStack(PriceGunService.ITEM_ID)?.lore() ?: return
            gun.lore(PriceGunLore.build(definitionLore, settings))
        }

        /** Sale prices start at 1, as `/grounditem sell` requires, and stop at the sell limit. */
        fun clampPrice(price: Long): Int = price.coerceIn(MIN_PRICE.toLong(), Command.MAX_SELL_PRICE.toLong()).toInt()
    }
}

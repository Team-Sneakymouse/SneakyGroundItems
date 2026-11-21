package com.danidipp.sneakygrounditems.pricegun

import com.danidipp.sneakygrounditems.commerce.Currency
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.NamespacedKey

/** What a Price Gun shot does. [id] is the value stored on the gun; don't rename it. */
internal enum class PriceGunMode(
    val id: String,
    val label: String,
    val color: TextColor,
    val currency: Currency?,
    /** Beam colours, cycled along the beam. */
    val beam: List<Color>,
    val firePitch: Float,
    /** The menu button icon (`item_model`). */
    val icon: NamespacedKey,
    val description: String,
) {
    GOLD(
        "gold", "Gold", NamedTextColor.GOLD, Currency.GOLD,
        listOf(Color.fromRGB(0xFFC83D), Color.fromRGB(0xFFF2B0)), 0.6f,
        NamespacedKey("lom", "coin_gold"), "Price items in Gold.",
    ),
    SILVER(
        "silver", "Silver", NamedTextColor.WHITE, Currency.SILVER,
        listOf(Color.fromRGB(0xD9DDE3), Color.fromRGB(0x9AA3AE)), 0.7f,
        NamespacedKey("lom", "coin_silver"), "Price items in Silver.",
    ),
    PENNY(
        "penny", "Penny", NamedTextColor.RED, Currency.PENNY,
        listOf(Color.fromRGB(0xC8723C), Color.fromRGB(0xE9A26B)), 0.8f,
        NamespacedKey("lom", "coin_copper"), "Price items in Pennies.",
    ),
    LOCK(
        "lock", "Lock", NamedTextColor.AQUA, null,
        listOf(Color.fromRGB(0xFF3B30)), 1.0f,
        NamespacedKey("lom", "price_gun_lock"), "Bind items in place.",
    ),
    UNLOCK(
        "unlock", "Unlock", NamedTextColor.AQUA, null,
        listOf(Color.fromRGB(0x2C88D9)), 0.75f,
        NamespacedKey("lom", "price_gun_unlock"), "Free items you bound.",
    ),
    SHOWOFF(
        "showoff", "Showoff", NamedTextColor.AQUA, null,
        listOf(Color.fromRGB(0xFFD34D), Color.fromRGB(0xFFF2B0), Color.WHITE), 1.5f,
        NamespacedKey("lom", "price_gun_showoff"), "Bind items and show their name.",
    ),
    ;

    /** The menu background art drawn for this mode (an `oversized_in_gui` item). */
    val menuArt: NamespacedKey get() = NamespacedKey("lom", "price_gun_menu_$id")

    companion object {
        fun fromId(id: String): PriceGunMode? = entries.firstOrNull { it.id == id }

        fun forCurrency(currency: Currency): PriceGunMode? = entries.firstOrNull { it.currency == currency }
    }
}

package com.danidipp.sneakygrounditems.commerce

import org.bukkit.NamespacedKey

internal enum class Currency(
    val id: String,
    val variableName: String,
    val itemModel: NamespacedKey,
) {
    PENNY("penny", "bankPenny", CurrencyModels.COPPER_COIN),
    SILVER("silver", "bankSilver", CurrencyModels.SILVER_COIN),
    // DIAMOND and MYTHRIL intentionally keep the gold coin look they had before the item_model migration.
    GOLD("gold", "bankGold", CurrencyModels.GOLD_COIN),
    DIAMOND("diamond", "bankDiamond", CurrencyModels.GOLD_COIN),
    MYTHRIL("mythril", "bankMythril", CurrencyModels.GOLD_COIN),
    ;

    companion object {
        fun fromId(id: String): Currency? = entries.firstOrNull { it.id == id }
    }

    override fun toString(): String {
        return id.replaceFirstChar { it.uppercase() }
    }
}

private object CurrencyModels {
    val COPPER_COIN = NamespacedKey("lom", "items/copper_coin")
    val SILVER_COIN = NamespacedKey("lom", "items/silver_coin")
    val GOLD_COIN = NamespacedKey("lom", "items/gold_coin")
}

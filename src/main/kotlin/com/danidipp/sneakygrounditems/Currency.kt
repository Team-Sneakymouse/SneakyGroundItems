package com.danidipp.sneakygrounditems

internal enum class Currency(
    val id: String,
    val variableName: String,
    val customModelData: Int,
) {
    PENNY("penny", "bankPenny", 8),
    SILVER("silver", "bankSilver", 17),
    GOLD("gold", "bankGold", 10),
    DIAMOND("diamond", "bankDiamond", 10),
    MYTHRIL("mythril", "bankMythril", 10),
    ;

    companion object {
        fun fromId(id: String): Currency? = entries.firstOrNull { it.id == id }
    }

    override fun toString(): String {
        return id.replaceFirstChar { it.uppercase() }
    }
}

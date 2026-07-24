package com.danidipp.sneakygrounditems.integration.magicspells

internal object MagicSpellsCommands {
    fun shopliftCost(playerName: String): String {
        return "ms cast as $playerName item-goo-shopliftcost"
    }

    fun shopliftingNotice(playerName: String): String {
        return "ms cast as $playerName crime-shoplifting-gponotice"
    }
}

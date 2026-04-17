package com.danidipp.sneakygrounditems

import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay

enum class LockMode {
    NONE,
    TEMPORARY,
    PERMANENT,
}

data class GroundItemTarget(
    val display: ItemDisplay,
    val interaction: Interaction,
    val pairId: String,
)

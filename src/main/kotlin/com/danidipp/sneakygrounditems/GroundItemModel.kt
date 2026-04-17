package com.danidipp.sneakygrounditems

import org.bukkit.entity.ItemDisplay

enum class LockMode {
    NONE,
    TEMPORARY,
    PERMANENT,
}

data class GroundItemTarget(
    val display: ItemDisplay,
)

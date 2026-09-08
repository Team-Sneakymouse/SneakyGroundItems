package com.danidipp.sneakygrounditems.integration.coreprotect

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

internal interface GroundItemAuditLogger {
    fun logDrop(player: Player, location: Location, stack: ItemStack)

    fun logPickup(player: Player, location: Location, stack: ItemStack)
}

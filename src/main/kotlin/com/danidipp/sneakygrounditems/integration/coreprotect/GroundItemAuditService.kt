package com.danidipp.sneakygrounditems.integration.coreprotect

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

internal class GroundItemAuditService {
    private val coreProtectAuditLogger: GroundItemAuditLogger? by lazy {
        val plugin = Bukkit.getPluginManager().getPlugin("CoreProtect")
            ?.takeIf { it.isEnabled }
            ?: return@lazy null

        CoreProtectGroundItemAuditLogger.create(plugin)
    }

    fun logDrop(player: Player, location: Location, stack: ItemStack) {
        coreProtectAuditLogger?.logDrop(player, location, stack)
    }

    fun logPickup(player: Player, location: Location, stack: ItemStack) {
        coreProtectAuditLogger?.logPickup(player, location, stack)
    }
}

package com.danidipp.sneakygrounditems.integration.coreprotect

import net.coreprotect.CoreProtect
import net.coreprotect.CoreProtectAPI
import net.coreprotect.listener.entity.EntityPickupItemListener
import net.coreprotect.listener.player.PlayerDropItemListener
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

internal class GroundItemAuditService {
    private val coreProtectApi: CoreProtectAPI? by lazy {
        val coreProtectPlugin = Bukkit.getPluginManager().getPlugin("CoreProtect") as? CoreProtect ?: return@lazy null
        coreProtectPlugin.api?.takeIf { it.isEnabled }
    }

    fun logDrop(player: Player, location: Location, stack: ItemStack) {
        if (coreProtectApi == null) return
        PlayerDropItemListener.playerDropItem(location, player.name, stack)
    }

    fun logPickup(player: Player, location: Location, stack: ItemStack) {
        if (coreProtectApi == null) return
        EntityPickupItemListener.onItemPickup(player, location, stack)
    }
}

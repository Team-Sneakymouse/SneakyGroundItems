package com.danidipp.sneakygrounditems.integration.coreprotect

import net.coreprotect.CoreProtect
import net.coreprotect.listener.entity.EntityPickupItemListener
import net.coreprotect.listener.player.PlayerDropItemListener
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

internal class CoreProtectGroundItemAuditLogger private constructor() : GroundItemAuditLogger {
    override fun logDrop(player: Player, location: Location, stack: ItemStack) {
        PlayerDropItemListener.playerDropItem(location, player.name, stack)
    }

    override fun logPickup(player: Player, location: Location, stack: ItemStack) {
        EntityPickupItemListener.onItemPickup(player, location, stack)
    }

    companion object {
        fun create(plugin: Plugin): GroundItemAuditLogger? {
            val coreProtect = plugin as? CoreProtect ?: return null
            val api = coreProtect.api ?: return null
            return CoreProtectGroundItemAuditLogger().takeIf { api.isEnabled }
        }
    }
}

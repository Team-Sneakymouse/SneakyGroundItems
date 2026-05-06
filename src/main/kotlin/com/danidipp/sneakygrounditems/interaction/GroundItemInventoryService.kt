package com.danidipp.sneakygrounditems.interaction

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

internal class GroundItemInventoryService {
    fun giveCollectedItem(player: Player, stack: ItemStack): Collection<ItemStack> {
        if (player.inventory.itemInMainHand.type.isAir) {
            player.inventory.setItem(player.inventory.heldItemSlot, stack)
            return emptyList()
        }

        return player.inventory.addItem(stack).values
    }
}

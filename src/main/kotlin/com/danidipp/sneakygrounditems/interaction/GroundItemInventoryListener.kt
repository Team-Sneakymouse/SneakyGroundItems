package com.danidipp.sneakygrounditems.interaction

import com.danidipp.sneakygrounditems.commerce.GroundItemCommerceService
import com.danidipp.sneakygrounditems.commerce.PurchaseMenuHolder
import com.danidipp.sneakygrounditems.commerce.PurchaseMenuSpec
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent

internal class GroundItemInventoryListener(
    private val commerceService: GroundItemCommerceService,
) : Listener {
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? PurchaseMenuHolder ?: return
        event.isCancelled = true
        if (event.rawSlot !in 0 until event.view.topInventory.size) return

        when (event.rawSlot) {
            PurchaseMenuSpec.purchaseSlot -> {
                val player = event.whoClicked as? Player ?: return
                commerceService.handlePurchaseConfirmation(player, holder.displayUuid)
            }

            PurchaseMenuSpec.noSlot -> event.whoClicked.closeInventory()
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder !is PurchaseMenuHolder) return
        if (event.rawSlots.any { it in 0 until event.view.topInventory.size }) {
            event.isCancelled = true
        }
    }

}

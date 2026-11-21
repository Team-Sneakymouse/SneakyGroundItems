package com.danidipp.sneakygrounditems.pricegun

import com.sneakyitems.api.SneakyItemsAPI
import com.sneakyitems.events.MagicItemClickEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin

internal class PriceGunListener(
    private val plugin: Plugin,
    private val service: PriceGunService,
) : Listener {
    @EventHandler
    fun onMagicItemClick(event: MagicItemClickEvent) {
        if (event.itemKey != PriceGunService.ITEM_ID) return
        if (!event.isRightClick || event.hand != EquipmentSlot.HAND) return
        // Right-clicking an entity (an NPC, a mount) keeps its normal interaction.
        if (event.trigger == MagicItemClickEvent.Trigger.ENTITY) return
        event.isCancelled = true
        service.fire(event.player)
    }

    /** F with the gun in the main hand opens the menu instead of swapping hands. */
    @EventHandler(ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        if (!SneakyItemsAPI.isItem(event.player.inventory.itemInMainHand, PriceGunService.ITEM_ID)) return
        event.isCancelled = true
        service.openMenu(event.player)
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        if (event.view.topInventory.holder !is PriceGunMenu.Holder) return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        val view = event.view
        val rawSlot = event.rawSlot
        val shift = event.isShiftClick
        Bukkit.getScheduler().runTask(plugin, Runnable { service.menu.handleClick(player, view, rawSlot, shift) })
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder !is PriceGunMenu.Holder) return
        if (event.rawSlots.any { it in 0 until event.view.topInventory.size }) event.isCancelled = true
    }
}

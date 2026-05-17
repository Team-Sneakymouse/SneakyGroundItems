package com.danidipp.sneakygrounditems.interaction

import io.papermc.paper.event.player.PlayerPickItemEvent
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent

internal class GroundItemInteractionListener(
    private val interactionService: GroundItemInteractionService,
) : Listener {
    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        interactionService.handlePlayerDropItem(event)
    }

    @EventHandler(priority = EventPriority.LOW)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        interactionService.handlePlayerInteract(event)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        interactionService.handlePlayerQuit(event.player)
    }

    @EventHandler
    fun onPlayerPickItem(event: PlayerPickItemEvent) {
        interactionService.handlePlayerPickItem(event)
    }
}

package com.danidipp.sneakygrounditems

import io.papermc.paper.event.player.PlayerPickItemEvent
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.plugin.java.JavaPlugin

class SneakyGroundItems : JavaPlugin(), Listener {
    private lateinit var groundItemManager: GroundItemManager

    override fun onEnable() {
        val persistence = GroundItemPersistence(this)
        val lockService = GroundItemLockService(persistence)
        groundItemManager = GroundItemManager(this, persistence, lockService)
        groundItemManager.processLoadedChunks()

        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                GroundItemCommand(groundItemManager, lockService).build(),
                "Lock, unlock, pop, or migrate placed ground items",
            )
        }

        Bukkit.getPluginManager().registerEvents(this, this)
    }

    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        groundItemManager.handlePlayerDropItem(event)
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        groundItemManager.handlePlayerInteract(event)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        groundItemManager.handlePlayerQuit(event.player)
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        groundItemManager.handleChunkLoad(event.chunk)
    }

    @EventHandler
    fun onPlayerPickItem(event: PlayerPickItemEvent) {
        groundItemManager.handlePlayerPickItem(event)
    }
}

package com.danidipp.sneakygrounditems.interaction

import com.danidipp.sneakygrounditems.display.GroundItemDisplayService
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent

internal class GroundItemChunkListener(
    private val displayService: GroundItemDisplayService,
) : Listener {
    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        displayService.processChunk(event.chunk)
    }
}

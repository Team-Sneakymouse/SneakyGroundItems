package com.danidipp.sneakygrounditems.bootstrap

import com.danidipp.sneakygrounditems.SneakyGroundItems
import com.danidipp.sneakygrounditems.command.Command
import com.danidipp.sneakygrounditems.interaction.GroundItemChunkListener
import com.danidipp.sneakygrounditems.interaction.GroundItemInteractionListener
import com.danidipp.sneakygrounditems.interaction.GroundItemInventoryListener
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit

internal class GroundItemPluginBootstrap(
    private val plugin: SneakyGroundItems,
) {
    fun enable() {
        val context = GroundItemPluginContext.create(plugin)

        context.displayService.processLoadedChunks()

        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                Command(
                    persistence = context.persistence,
                    lookupService = context.lookupService,
                    interactionService = context.interactionService,
                    commerceService = context.commerceService,
                    theftService = context.theftService,
                    migrationService = context.migrationService,
                    lockService = context.lockService,
                ).build(),
                "Manage placed ground items",
            )
        }

        Bukkit.getPluginManager().registerEvents(GroundItemInteractionListener(context.interactionService), plugin)
        Bukkit.getPluginManager().registerEvents(GroundItemInventoryListener(context.commerceService), plugin)
        Bukkit.getPluginManager().registerEvents(GroundItemChunkListener(context.displayService), plugin)
    }
}

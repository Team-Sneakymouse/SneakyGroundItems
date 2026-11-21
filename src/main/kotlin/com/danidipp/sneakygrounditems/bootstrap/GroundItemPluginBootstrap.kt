package com.danidipp.sneakygrounditems.bootstrap

import com.danidipp.sneakygrounditems.SneakyGroundItems
import com.danidipp.sneakygrounditems.command.Command
import com.danidipp.sneakygrounditems.display.labels.GroundItemLabels
import com.danidipp.sneakygrounditems.interaction.GroundItemChunkListener
import com.danidipp.sneakygrounditems.interaction.GroundItemInteractionListener
import com.danidipp.sneakygrounditems.interaction.GroundItemInventoryListener
import com.danidipp.sneakygrounditems.pricegun.PriceGunListener
import com.danidipp.sneakygrounditems.pricegun.PriceGunService
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit

internal class GroundItemPluginBootstrap(
    private val plugin: SneakyGroundItems,
) {
    fun enable(): AutoCloseable {
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
        val labels = GroundItemLabels.create(plugin, context.persistence, context.placementService, plugin.probe)
        labels.start()

        val priceGun = PriceGunService(
            plugin = plugin,
            persistence = context.persistence,
            lookupService = context.lookupService,
            commerceService = context.commerceService,
            lockService = context.lockService,
        )
        Bukkit.getPluginManager().registerEvents(PriceGunListener(plugin, priceGun), plugin)
        priceGun.start()

        return AutoCloseable {
            priceGun.close()
            labels.close()
        }
    }
}

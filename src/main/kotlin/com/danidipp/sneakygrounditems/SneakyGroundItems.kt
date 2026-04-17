package com.danidipp.sneakygrounditems

import com.danidipp.sneakygrounditems.grounditems.GroundItemCommand
import com.danidipp.sneakygrounditems.grounditems.GroundItemsModule
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

class SneakyGroundItems : JavaPlugin() {
    override fun onEnable() {
        val groundItemsModule = GroundItemsModule(this)

        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(
                GroundItemCommand(groundItemsModule).build(),
                "Lock, unlock, or pop placed ground items",
            )
        }

        Bukkit.getPluginManager().registerEvents(groundItemsModule, this)
    }
}

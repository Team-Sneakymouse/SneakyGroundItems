package com.danidipp.sneakygrounditems

import com.danidipp.sneakygrounditems.bootstrap.GroundItemPluginBootstrap
import org.bukkit.plugin.java.JavaPlugin

class SneakyGroundItems : JavaPlugin() {
    override fun onEnable() {
        GroundItemPluginBootstrap(this).enable()
    }
}

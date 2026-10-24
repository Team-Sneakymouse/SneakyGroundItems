package com.danidipp.sneakygrounditems

import com.danidipp.sneakygrounditems.bootstrap.GroundItemPluginBootstrap
import org.bukkit.plugin.java.JavaPlugin

class SneakyGroundItems : JavaPlugin() {
    private var runtime: AutoCloseable? = null

    override fun onEnable() {
        runtime = GroundItemPluginBootstrap(this).enable()
    }

    override fun onDisable() {
        runtime?.close()
        runtime = null
    }
}

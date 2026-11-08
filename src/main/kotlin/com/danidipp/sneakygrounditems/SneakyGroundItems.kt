package com.danidipp.sneakygrounditems

import com.danidipp.sneakygrounditems.bootstrap.GroundItemPluginBootstrap
import com.sneakyitems.api.SneakyItemsAPI
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class SneakyGroundItems : JavaPlugin() {
    private var runtime: AutoCloseable? = null

    override fun onEnable() {
        registerMagicItems()
        runtime = GroundItemPluginBootstrap(this).enable()
    }

    override fun onDisable() {
        SneakyItemsAPI.clearOwner(this)
        runtime?.close()
        runtime = null
    }

    /** Own MagicsSpells-style stolen-goods def under resources/magic-items.yml; PDC namespace stays magicspells. */
    private fun registerMagicItems() {
        saveResource("magic-items.yml", false)
        SneakyItemsAPI.clearOwner(this)
        val n = SneakyItemsAPI.registerMagicItemsFile(this, "magicspells", File(dataFolder, "magic-items.yml"))
        logger.info("Registered $n magic item(s) with SneakyItems.")
    }
}

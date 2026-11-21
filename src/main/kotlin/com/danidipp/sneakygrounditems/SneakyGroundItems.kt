package com.danidipp.sneakygrounditems

import com.danidipp.sneakygrounditems.bootstrap.GroundItemPluginBootstrap
import com.danidipp.sneakygrounditems.integration.Probe
import com.danidipp.sneakygrounditems.pricegun.PriceGunService
import com.sneakyitems.api.SneakyItemsAPI
import org.bukkit.plugin.java.JavaPlugin
class SneakyGroundItems : JavaPlugin() {
    private var runtime: AutoCloseable? = null

    /** SneakyDiagnostics measurements (optional); names are listed in the README. */
    internal val probe = Probe(this, "sneakygrounditems-ticks-2")

    override fun onEnable() {
        registerMagicItems()
        runtime = GroundItemPluginBootstrap(this).enable()
        probe.hook()
    }

    override fun onDisable() {
        probe.unhook()
        SneakyItemsAPI.clearOwner(this)
        runtime?.close()
        runtime = null
    }

    /** Own MagicsSpells-style stolen-goods def under resources/magic-items.yml; PDC namespace stays magicspells. */
    private fun registerMagicItems() {
        SneakyItemsAPI.clearOwner(this)
        var n = 0
        getResource("magic-items.yml")?.use { stream ->
            val yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))
            n = SneakyItemsAPI.registerFromRoot(this, "magicspells", yaml)
        } ?: logger.warning("Missing bundled resource: magic-items.yml")
        logger.info("Registered $n magic item(s) with SneakyItems.")

        // The single-mode Price Guns (Gold/Silver/Penny/Lock) that predate the unified gun: once no plugin registers
        // their old names, SneakyItems re-stamps copies by look and rebuilds them as the Price Gun.
        for (model in LEGACY_PRICE_GUN_MODELS) {
            SneakyItemsAPI.registerLegacyPattern(this, PriceGunService.ITEM_ID, "rabbit_foot{\"item-model\":\"$model\"}")
        }
    }

    private companion object {
        val LEGACY_PRICE_GUN_MODELS = (218..221).map { "lom:legacy/rabbit_foot/$it" }
    }
}

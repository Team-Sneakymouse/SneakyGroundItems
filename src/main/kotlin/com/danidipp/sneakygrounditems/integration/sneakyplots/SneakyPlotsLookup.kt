package com.danidipp.sneakygrounditems.integration.sneakyplots

import com.danidipp.sneakygrounditems.lock.NoPlots
import com.danidipp.sneakygrounditems.lock.PlotLookup
import com.sneakyplots.api.SneakyPlotsApi
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player

/** Plots and plot membership from SneakyPlots. Only this file names SneakyPlots classes. */
internal class SneakyPlotsLookup private constructor() : PlotLookup {
    // Looked up on each call: the service appears when SneakyPlots enables and stays valid across its reloads.
    private val api: SneakyPlotsApi?
        get() = Bukkit.getServicesManager().load(SneakyPlotsApi::class.java)

    override val isReady: Boolean
        get() = api?.isReady == true

    override fun plotIdAt(location: Location): String? = api?.plotAt(location)?.areaId

    override fun isMember(player: Player, plotId: String): Boolean = api?.isMember(player, plotId) == true

    companion object {
        /** SneakyPlots' lookup when the plugin is installed, otherwise [NoPlots]. */
        fun createOrNone(): PlotLookup =
            if (Bukkit.getPluginManager().getPlugin("SneakyPlots") != null) SneakyPlotsLookup() else NoPlots
    }
}

package com.danidipp.sneakygrounditems.lock

import org.bukkit.Location
import org.bukkit.entity.Player

/** Which plot a location is in and who belongs to it. SneakyPlots answers it on the server. Main thread only. */
internal interface PlotLookup {
    /** False while plots aren't known (no SneakyPlots, or its first snapshot hasn't loaded); nothing is in a plot then. */
    val isReady: Boolean

    fun plotIdAt(location: Location): String?

    fun isMember(player: Player, plotId: String): Boolean
}

/** Used without SneakyPlots: there are no plots and no plot members. */
internal object NoPlots : PlotLookup {
    override val isReady: Boolean get() = false

    override fun plotIdAt(location: Location): String? = null

    override fun isMember(player: Player, plotId: String): Boolean = false
}

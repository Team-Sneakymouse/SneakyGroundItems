package com.danidipp.sneakygrounditems.lock

import com.danidipp.sneakygrounditems.persistence.PersistenceService
import com.danidipp.sneakygrounditems.testutil.fakeItemDisplay
import com.danidipp.sneakygrounditems.testutil.testPlugin
import org.bukkit.Location
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals

class LockServiceTest {
    private val persistence = PersistenceService(testPlugin())

    /** Plots are loaded and every location is in [plotId] (null: in no plot). */
    private class LoadedPlots(private val plotId: String?) : PlotLookup {
        override val isReady: Boolean get() = true

        override fun plotIdAt(location: Location): String? = plotId

        override fun isMember(player: Player, plotId: String): Boolean = false
    }

    private fun display(): ItemDisplay = fakeItemDisplay(
        Transformation(
            Vector3f(),
            Quaternionf(),
            Vector3f(1f, 1f, 1f),
            Quaternionf(),
        ),
    )

    @Test
    fun clearInvalidPlotLockUnlocksDisplayWhenNoPlotExistsAtLocation() {
        val display = display()
        persistence.setPlotLock(display)

        LockService(persistence, LoadedPlots(null)).clearInvalidPlotLock(display)

        assertEquals(LockService.LockMode.NONE, persistence.getLockMode(display))
    }

    @Test
    fun clearInvalidPlotLockKeepsLockInsideAPlot() {
        val display = display()
        persistence.setPlotLock(display)

        LockService(persistence, LoadedPlots("plot_12")).clearInvalidPlotLock(display)

        assertEquals(LockService.LockMode.PLOT, persistence.getLockMode(display))
    }

    @Test
    fun clearInvalidPlotLockKeepsLockWhilePlotsAreUnknown() {
        val display = display()
        persistence.setPlotLock(display)

        LockService(persistence, NoPlots).clearInvalidPlotLock(display)

        assertEquals(LockService.LockMode.PLOT, persistence.getLockMode(display))
    }

    @Test
    fun clearInvalidPlotLockLeavesPermanentLocksAlone() {
        val display = display()
        persistence.setPermanentLock(display)

        LockService(persistence, LoadedPlots(null)).clearInvalidPlotLock(display)

        assertEquals(LockService.LockMode.PERMANENT, persistence.getLockMode(display))
    }

    @Test
    fun clearingALockAlsoEndsShowoff() {
        val display = display()
        persistence.setPlotLock(display)
        val service = LockService(persistence, LoadedPlots(null))
        service.showOff(display)

        service.clearInvalidPlotLock(display)

        assertEquals(false, persistence.isShowoff(display))
    }
}

package com.danidipp.sneakygrounditems.lock

import com.danidipp.sneakygrounditems.persistence.PersistenceService
import com.danidipp.sneakygrounditems.testutil.fakeItemDisplay
import com.danidipp.sneakygrounditems.testutil.testPlugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals

class LockServiceTest {
    private val persistence = PersistenceService(testPlugin())
    private val service = LockService(persistence)

    @Test
    fun clearInvalidPlotLockUnlocksDisplayWhenNoPlotExistsAtLocation() {
        val display = fakeItemDisplay(
            Transformation(
                Vector3f(),
                Quaternionf(),
                Vector3f(1f, 1f, 1f),
                Quaternionf(),
            ),
        )
        persistence.setPlotLock(display)

        service.clearInvalidPlotLock(display)

        assertEquals(LockService.LockMode.NONE, persistence.getLockMode(display))
    }

    @Test
    fun clearInvalidPlotLockLeavesPermanentLocksAlone() {
        val display = fakeItemDisplay(
            Transformation(
                Vector3f(),
                Quaternionf(),
                Vector3f(1f, 1f, 1f),
                Quaternionf(),
            ),
        )
        persistence.setPermanentLock(display)

        service.clearInvalidPlotLock(display)

        assertEquals(LockService.LockMode.PERMANENT, persistence.getLockMode(display))
    }
}

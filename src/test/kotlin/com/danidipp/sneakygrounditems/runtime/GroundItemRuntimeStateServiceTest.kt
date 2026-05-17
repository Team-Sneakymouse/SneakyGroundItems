package com.danidipp.sneakygrounditems.runtime

import com.danidipp.sneakygrounditems.config.Constants
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroundItemRuntimeStateServiceTest {
    @Test
    fun `pickup guard is inactive by default`() {
        val service = GroundItemRuntimeStateService()
        val playerId = UUID.randomUUID()

        assertFalse(service.shouldCancelAndRefreshPickupLeftClick(playerId, nowMillis = 1_000L))
    }

    @Test
    fun `pickup guard cancels before timeout`() {
        val service = GroundItemRuntimeStateService()
        val playerId = UUID.randomUUID()
        val startedAt = 1_000L

        service.markPickupLeftClickGuard(playerId, nowMillis = startedAt)

        assertTrue(
            service.shouldCancelAndRefreshPickupLeftClick(
                playerId,
                nowMillis = startedAt + Constants.PICKUP_LEFT_CLICK_GUARD_MS - 1L,
            ),
        )
    }

    @Test
    fun `pickup guard expires at timeout`() {
        val service = GroundItemRuntimeStateService()
        val playerId = UUID.randomUUID()
        val startedAt = 1_000L

        service.markPickupLeftClickGuard(playerId, nowMillis = startedAt)

        assertFalse(
            service.shouldCancelAndRefreshPickupLeftClick(
                playerId,
                nowMillis = startedAt + Constants.PICKUP_LEFT_CLICK_GUARD_MS,
            ),
        )
    }

    @Test
    fun `cancelled left click refreshes timeout`() {
        val service = GroundItemRuntimeStateService()
        val playerId = UUID.randomUUID()
        val startedAt = 1_000L
        val firstClickAt = startedAt + Constants.PICKUP_LEFT_CLICK_GUARD_MS - 1L
        val secondClickAt = firstClickAt + Constants.PICKUP_LEFT_CLICK_GUARD_MS - 1L

        service.markPickupLeftClickGuard(playerId, nowMillis = startedAt)

        assertTrue(service.shouldCancelAndRefreshPickupLeftClick(playerId, nowMillis = firstClickAt))
        assertTrue(service.shouldCancelAndRefreshPickupLeftClick(playerId, nowMillis = secondClickAt))
        assertFalse(
            service.shouldCancelAndRefreshPickupLeftClick(
                playerId,
                nowMillis = secondClickAt + Constants.PICKUP_LEFT_CLICK_GUARD_MS,
            ),
        )
    }

    @Test
    fun `clearPlayerState removes pickup guard`() {
        val service = GroundItemRuntimeStateService()
        val playerId = UUID.randomUUID()

        service.markPickupLeftClickGuard(playerId, nowMillis = 1_000L)
        service.clearPlayerState(playerId)

        assertFalse(service.shouldCancelAndRefreshPickupLeftClick(playerId, nowMillis = 1_001L))
    }
}

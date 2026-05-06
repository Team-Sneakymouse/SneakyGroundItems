package com.danidipp.sneakygrounditems.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroundItemStateTest {
    @Test
    fun fromIdResolvesKnownState() {
        assertEquals(GroundItemState.SALE, GroundItemState.fromId("sale"))
    }

    @Test
    fun fromIdReturnsNullForUnknownState() {
        assertNull(GroundItemState.fromId("broken"))
    }

    @Test
    fun lockedLikeMatchesCommerceStates() {
        assertFalse(GroundItemState.NORMAL.isLockedLike)
        assertTrue(GroundItemState.SALE.isLockedLike)
        assertTrue(GroundItemState.PAYMENT.isLockedLike)
    }
}

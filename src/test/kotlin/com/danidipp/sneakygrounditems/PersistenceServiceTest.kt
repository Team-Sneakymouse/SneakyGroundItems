package com.danidipp.sneakygrounditems

import org.bukkit.block.BlockFace
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PersistenceServiceTest {
    private val persistence = PersistenceService(testPlugin())

    @Test
    fun commerceDataRoundTripsThroughHolder() {
        val holder = FakePersistentDataHolder()
        val partnerUuid = UUID.randomUUID()

        persistence.setGroundItemState(holder, GroundItemState.SALE)
        persistence.setCurrency(holder, Currency.SILVER)
        persistence.setPrice(holder, 42)
        persistence.setPartnerUuid(holder, partnerUuid)
        persistence.setFace(holder, BlockFace.WEST)

        assertEquals(GroundItemState.SALE, persistence.getGroundItemState(holder))
        assertEquals(Currency.SILVER, persistence.getCurrency(holder))
        assertEquals(42, persistence.getPrice(holder))
        assertEquals(partnerUuid, persistence.getPartnerUuid(holder))
        assertEquals(BlockFace.WEST, persistence.getFace(holder))
    }

    @Test
    fun clearCommerceDataResetsOptionalMetadata() {
        val holder = FakePersistentDataHolder()
        persistence.setGroundItemState(holder, GroundItemState.PAYMENT)
        persistence.setCurrency(holder, Currency.DIAMOND)
        persistence.setPrice(holder, 99)
        persistence.setPartnerUuid(holder, UUID.randomUUID())

        persistence.clearCommerceData(holder)

        assertEquals(GroundItemState.NORMAL, persistence.getGroundItemState(holder))
        assertNull(persistence.getCurrency(holder))
        assertNull(persistence.getPrice(holder))
        assertNull(persistence.getPartnerUuid(holder))
    }
}

package com.danidipp.sneakygrounditems.persistence

import com.danidipp.sneakygrounditems.commerce.Currency
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.testutil.FakePersistentDataHolder
import com.danidipp.sneakygrounditems.testutil.testPlugin
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
        persistence.setPaymentMagicItemId(holder, "copper_ring")
        persistence.setPaymentItemAmount(holder, 3)
        persistence.setPartnerUuid(holder, partnerUuid)
        persistence.setFace(holder, BlockFace.WEST)

        assertEquals(GroundItemState.SALE, persistence.getGroundItemState(holder))
        assertEquals(Currency.SILVER, persistence.getCurrency(holder))
        assertEquals(42, persistence.getPrice(holder))
        assertEquals("copper_ring", persistence.getPaymentMagicItemId(holder))
        assertEquals(3, persistence.getPaymentItemAmount(holder))
        assertEquals(partnerUuid, persistence.getPartnerUuid(holder))
        assertEquals(BlockFace.WEST, persistence.getFace(holder))
    }

    @Test
    fun clearCommerceDataResetsOptionalMetadata() {
        val holder = FakePersistentDataHolder()
        persistence.setGroundItemState(holder, GroundItemState.PAYMENT)
        persistence.setCurrency(holder, Currency.DIAMOND)
        persistence.setPrice(holder, 99)
        persistence.setPaymentMagicItemId(holder, "copper_ring")
        persistence.setPaymentItemAmount(holder, 3)
        persistence.setPartnerUuid(holder, UUID.randomUUID())

        persistence.clearCommerceData(holder)

        assertEquals(GroundItemState.NORMAL, persistence.getGroundItemState(holder))
        assertNull(persistence.getCurrency(holder))
        assertNull(persistence.getPrice(holder))
        assertNull(persistence.getPaymentMagicItemId(holder))
        assertNull(persistence.getPaymentItemAmount(holder))
        assertNull(persistence.getPartnerUuid(holder))
    }

    @Test
    fun creatorNameRoundTripsThroughHolder() {
        val holder = FakePersistentDataHolder()

        persistence.setCreatorName(holder, "DaniDipp")

        assertEquals("DaniDipp", persistence.getCreatorName(holder))
    }

    @Test
    fun creatorNameCanBeCleared() {
        val holder = FakePersistentDataHolder()
        persistence.setCreatorName(holder, "DaniDipp")

        persistence.setCreatorName(holder, null)

        assertNull(persistence.getCreatorName(holder))
    }
}

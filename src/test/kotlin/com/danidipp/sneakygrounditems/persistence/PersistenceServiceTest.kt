package com.danidipp.sneakygrounditems.persistence

import com.danidipp.sneakygrounditems.commerce.Currency
import com.danidipp.sneakygrounditems.lock.LockService
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.model.SaleSnapshot
import com.danidipp.sneakygrounditems.testutil.FakePersistentDataHolder
import com.danidipp.sneakygrounditems.testutil.fakeItemDisplay
import com.danidipp.sneakygrounditems.testutil.testPlugin
import org.bukkit.block.BlockFace
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
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
        persistence.setSaleMagicItemId(holder, "copper_ring")
        persistence.setSaleItemAmount(holder, 3)
        persistence.setPartnerUuid(holder, partnerUuid)
        persistence.setFace(holder, BlockFace.WEST)

        assertEquals(GroundItemState.SALE, persistence.getGroundItemState(holder))
        assertEquals(Currency.SILVER, persistence.getCurrency(holder))
        assertEquals(42, persistence.getPrice(holder))
        assertEquals("copper_ring", persistence.getSaleMagicItemId(holder))
        assertEquals(3, persistence.getSaleItemAmount(holder))
        assertEquals(partnerUuid, persistence.getPartnerUuid(holder))
        assertEquals(BlockFace.WEST, persistence.getFace(holder))
    }

    @Test
    fun clearCommerceDataResetsOptionalMetadata() {
        val holder = FakePersistentDataHolder()
        persistence.setGroundItemState(holder, GroundItemState.PAYMENT)
        persistence.setCurrency(holder, Currency.DIAMOND)
        persistence.setPrice(holder, 99)
        persistence.setSaleMagicItemId(holder, "copper_ring")
        persistence.setSaleItemAmount(holder, 3)
        persistence.setPartnerUuid(holder, UUID.randomUUID())

        persistence.clearCommerceData(holder)

        assertEquals(GroundItemState.NORMAL, persistence.getGroundItemState(holder))
        assertNull(persistence.getCurrency(holder))
        assertNull(persistence.getPrice(holder))
        assertNull(persistence.getSaleMagicItemId(holder))
        assertNull(persistence.getSaleItemAmount(holder))
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

    @Test
    fun stolenSaleSnapshotRoundTripsThroughHolder() {
        val holder = FakePersistentDataHolder()
        val snapshot = SaleSnapshot(
            magicItemId = "copper_ring",
            itemAmount = 3,
            currency = Currency.SILVER,
            price = 42,
            originalScale = Vector3f(0.75f, 0.75f, 0.75f),
        )

        persistence.setSaleSnapshot(holder, snapshot)

        assertEquals(snapshot, persistence.getSaleSnapshot(holder))
    }

    @Test
    fun incompleteStolenSaleSnapshotIsPreservedButNotResolved() {
        val holder = FakePersistentDataHolder()
        persistence.setGroundItemState(holder, GroundItemState.STOLEN)
        persistence.setSaleMagicItemId(holder, "copper_ring")

        assertNull(persistence.getSaleSnapshot(holder))
        assertEquals(GroundItemState.STOLEN, persistence.getGroundItemState(holder))
        assertEquals("copper_ring", persistence.getSaleMagicItemId(holder))
    }

    @Test
    fun plotLockRoundTripsAsDistinctLockMode() {
        val display = fakeItemDisplay(
            Transformation(
                Vector3f(),
                Quaternionf(),
                Vector3f(1f, 1f, 1f),
                Quaternionf(),
            ),
        )

        persistence.setPlotLock(display)

        assertEquals(LockService.LockMode.PLOT, persistence.getLockMode(display))
        assertNull(persistence.getTemporaryLockDate(display))
    }
}

package com.danidipp.sneakygrounditems.display

import com.danidipp.sneakygrounditems.commerce.Currency
import com.danidipp.sneakygrounditems.integration.magicspells.GroundItemMetadataService
import com.danidipp.sneakygrounditems.lock.LockService
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.model.SaleSnapshot
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import com.danidipp.sneakygrounditems.testutil.FakePersistentDataContainer
import com.danidipp.sneakygrounditems.testutil.testPlugin
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.joml.Vector3f
import org.mockito.Mockito.*
import java.util.UUID
import kotlin.test.*

class GroundItemDisplayLifecycleTest {
    @Test fun legacyCleanupRemovesOnlyOldTextAndPreservesTheItemAsSourceOfTruth() {
        val persistence = PersistenceService(testPlugin())
        val item = mock(ItemDisplay::class.java)
        val text = mock(TextDisplay::class.java)
        val stack = mock(ItemStack::class.java)
        doReturn(FakePersistentDataContainer()).`when`(item).persistentDataContainer
        doReturn(FakePersistentDataContainer()).`when`(text).persistentDataContainer
        doReturn(UUID(0, 1)).`when`(item).uniqueId
        doReturn(UUID(0, 2)).`when`(text).uniqueId
        doReturn(stack).`when`(item).itemStack
        persistence.tagDisplay(item)
        persistence.tagText(text)
        persistence.setPartnerUuid(item, text.uniqueId)
        persistence.setPartnerUuid(text, item.uniqueId)
        persistence.setGroundItemState(item, GroundItemState.STOLEN)
        persistence.setSaleSnapshot(item, SaleSnapshot("bread", 3, Currency.GOLD, 10, Vector3f(0.8f)))
        persistence.setPermanentLock(item)
        persistence.setCreatorName(item, "Dani")
        persistence.setFace(item, BlockFace.NORTH)
        val before = persistence.getSaleSnapshot(item)
        val service = GroundItemDisplayService(
            persistence, mock(LockService::class.java), mock(PlacementService::class.java), mock(GroundItemMetadataService::class.java),
        )
        service.processEntities(listOf(item, text))
        service.processEntities(listOf(item))
        verify(text, times(1)).remove()
        verify(item, never()).remove()
        assertNull(persistence.getPartnerUuid(item))
        assertEquals(before, persistence.getSaleSnapshot(item))
        assertEquals(GroundItemState.STOLEN, persistence.getGroundItemState(item))
        assertEquals(LockService.LockMode.PERMANENT, persistence.getLockMode(item))
        assertEquals("Dani", persistence.getCreatorName(item))
        assertEquals(BlockFace.NORTH, persistence.getFace(item))
    }
}

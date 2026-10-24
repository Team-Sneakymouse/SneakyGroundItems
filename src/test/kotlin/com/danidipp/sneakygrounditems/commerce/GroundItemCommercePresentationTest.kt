package com.danidipp.sneakygrounditems.commerce

import com.danidipp.sneakygrounditems.display.GroundItemDisplayService
import com.danidipp.sneakygrounditems.display.PlacementService
import com.danidipp.sneakygrounditems.integration.coreprotect.GroundItemAuditService
import com.danidipp.sneakygrounditems.integration.magicspells.GroundItemMetadataService
import com.danidipp.sneakygrounditems.interaction.*
import com.danidipp.sneakygrounditems.lock.LockService
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import com.danidipp.sneakygrounditems.runtime.GroundItemRuntimeStateService
import com.danidipp.sneakygrounditems.testutil.FakePersistentDataContainer
import com.danidipp.sneakygrounditems.testutil.testPlugin
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import org.mockito.Mockito.*
import java.util.UUID
import kotlin.test.*

/** Real transaction entry points and real PDC, with no label module running at all. */
class GroundItemCommercePresentationTest {
    @Test fun purchaseCreatesPaymentAndDeliversOnceWithoutPresentationOrRefund() {
        val f = Fixture()
        mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<org.bukkit.entity.Entity> { Bukkit.getEntity(f.id) }.thenReturn(f.display)
            f.commerce.handlePurchaseConfirmation(f.player, f.id)
        }
        assertEquals(GroundItemState.PAYMENT, f.persistence.getGroundItemState(f.display))
        assertEquals(10, f.persistence.getPrice(f.display))
        assertEquals(Currency.GOLD, f.persistence.getCurrency(f.display))
        assertEquals("bread", f.persistence.getSaleMagicItemId(f.display))
        assertEquals(3, f.persistence.getSaleItemAmount(f.display))
        assertSame(f.payment, f.stack)
        assertEquals(1, f.paymentCreations)
        verify(f.inventory, times(1)).giveCollectedItem(f.player, f.original)
        verify(f.bank, times(1)).withdraw(f.player, Currency.GOLD, 10)
        verify(f.bank, never()).deposit(f.player, Currency.GOLD, 10)
        verify(f.display, never()).remove()
        assertFalse(f.runtime.isResolutionPending(f.id))
    }

    @Test fun insufficientFundsStillRejectThePurchaseWithoutChangingTheItem() {
        val f = Fixture()
        doReturn(0.0).`when`(f.bank).getBalance(f.player, Currency.GOLD)
        mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<org.bukkit.entity.Entity> { Bukkit.getEntity(f.id) }.thenReturn(f.display)
            f.commerce.handlePurchaseConfirmation(f.player, f.id)
        }
        assertEquals(GroundItemState.SALE, f.persistence.getGroundItemState(f.display))
        assertSame(f.original, f.stack)
        assertEquals(0, f.paymentCreations)
        verifyNoInteractions(f.inventory)
        verify(f.bank, never()).withdraw(f.player, Currency.GOLD, 10)
        assertFalse(f.runtime.isResolutionPending(f.id))
    }

    @Test fun invalidSaleDataStillFailsBusinessValidation() {
        val f = Fixture()
        f.persistence.setPrice(f.display, null)
        mockStatic(Bukkit::class.java).use { bukkit ->
            bukkit.`when`<org.bukkit.entity.Entity> { Bukkit.getEntity(f.id) }.thenReturn(f.display)
            f.commerce.handlePurchaseConfirmation(f.player, f.id)
        }
        assertEquals(GroundItemState.NORMAL, f.persistence.getGroundItemState(f.display))
        assertEquals(0, f.paymentCreations)
        verifyNoInteractions(f.bank, f.inventory)
        assertFalse(f.runtime.isResolutionPending(f.id))
    }

    @Test fun saleCanBeCommittedWithoutAnyWorldTextEntity() {
        val f = Fixture()
        f.commerce.markGroundItemForSale(f.display, 25, Currency.SILVER)
        assertEquals(GroundItemState.SALE, f.persistence.getGroundItemState(f.display))
        assertEquals(25, f.persistence.getPrice(f.display))
        assertEquals(Currency.SILVER, f.persistence.getCurrency(f.display))
        verifyNoInteractions(f.world) // In particular, no text spawn or entity lookup.
    }

    private class Fixture {
        val id = UUID(0, 50)
        val player = mock(Player::class.java)
        val display = mock(ItemDisplay::class.java)
        val world = mock(World::class.java)
        val original = mock(ItemStack::class.java)
        val payment = mock(ItemStack::class.java)
        var stack = original
        val persistence = PersistenceService(testPlugin())
        val runtime = GroundItemRuntimeStateService()
        val bank = mock(BankService::class.java)
        val inventory = mock(GroundItemInventoryService::class.java)
        var paymentCreations = 0
        val commerce: GroundItemCommerceService

        init {
            val placement = mock(PlacementService::class.java)
            val locks = mock(LockService::class.java)
            val metadata = mock(GroundItemMetadataService::class.java)
            val transform = Transformation(Vector3f(), Quaternionf(), Vector3f(0.5f), Quaternionf())
            doReturn(FakePersistentDataContainer()).`when`(display).persistentDataContainer
            doReturn(id).`when`(display).uniqueId
            doReturn(Location(world, 0.0, 0.0, 0.0)).`when`(display).location
            doReturn(transform).`when`(display).transformation
            doAnswer { stack }.`when`(display).itemStack
            doAnswer { stack = it.getArgument(0); null }.`when`(display).setItemStack(any(ItemStack::class.java))
            doReturn(original).`when`(original).clone()
            doReturn(3).`when`(original).amount
            doReturn(Component.text("Bread")).`when`(original).displayName()
            doReturn("bread").`when`(metadata).getMagicItemId(original)
            doReturn(0.5f).`when`(placement).displayScale(display)
            doReturn(0.5f).`when`(placement).scaleForStack(original)
            doReturn(0.5f).`when`(placement).minimumScaleForStack(original)
            doReturn(transform).`when`(placement).rescaleTransformation(transform, 0.5f)
            doReturn(100.0).`when`(bank).getBalance(player, Currency.GOLD)
            doReturn(true).`when`(bank).withdraw(player, Currency.GOLD, 10)
            doReturn(emptyList<ItemStack>()).`when`(inventory).giveCollectedItem(player, original)
            persistence.tagDisplay(display)
            persistence.setGroundItemState(display, GroundItemState.SALE)
            persistence.setCurrency(display, Currency.GOLD)
            persistence.setPrice(display, 10)
            persistence.setFace(display, BlockFace.UP)
            commerce = GroundItemCommerceService(
                testPlugin(), persistence, locks, placement,
                GroundItemDisplayService(persistence, locks, placement, metadata), bank, metadata,
                mock(GroundItemLookupService::class.java), runtime, inventory,
                mock(GroundItemEffectsService::class.java), mock(GroundItemAuditService::class.java),
                { paymentCreations++; payment },
            )
        }
    }
}

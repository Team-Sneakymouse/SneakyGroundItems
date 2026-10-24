package com.danidipp.sneakygrounditems.commerce

import com.danidipp.sneakygrounditems.SneakyGroundItems
import com.danidipp.sneakygrounditems.display.GroundItemDisplayService
import com.danidipp.sneakygrounditems.display.PlacementService
import com.danidipp.sneakygrounditems.integration.magicspells.GroundItemMetadataService
import com.danidipp.sneakygrounditems.integration.magicspells.MagicSpellsCommands
import com.danidipp.sneakygrounditems.interaction.GroundItemLookupService
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.model.SaleSnapshot
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import com.danidipp.sneakygrounditems.runtime.GroundItemRuntimeStateService
import com.danidipp.sneakygrounditems.testutil.FakePersistentDataContainer
import com.danidipp.sneakygrounditems.testutil.testPlugin
import com.nisovin.magicspells.util.magicitems.MagicItem
import com.nisovin.magicspells.util.magicitems.MagicItems
import org.bukkit.Bukkit
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import org.mockito.Mockito.*
import java.util.UUID
import kotlin.test.*

class GroundItemTheftPresentationTest {
    @Test fun restorationCommitsSaleDataWithoutAnyPresentation() {
        val f = Fixture()
        val definition = mock(MagicItem::class.java)
        val restored = mock(ItemStack::class.java)
        doReturn(restored).`when`(definition).itemStack
        doReturn(restored).`when`(restored).clone()
        val console = mock(ConsoleCommandSender::class.java)
        mockStatic(MagicItems::class.java).use { items ->
            items.`when`<MagicItem> { MagicItems.getMagicItemByInternalName("bread") }.thenReturn(definition)
            mockStatic(Bukkit::class.java).use { bukkit ->
                bukkit.`when`<ConsoleCommandSender> { Bukkit.getConsoleSender() }.thenReturn(console)
                bukkit.`when`<Boolean> { Bukkit.dispatchCommand(console, MagicSpellsCommands.shopliftingNotice("Dani")) }.thenReturn(true)
                f.theft.restoreStolenGroundItem(f.player, f.display)
            }
        }
        assertEquals(GroundItemState.SALE, f.persistence.getGroundItemState(f.display))
        assertEquals(Currency.GOLD, f.persistence.getCurrency(f.display))
        assertEquals(10, f.persistence.getPrice(f.display))
        assertNull(f.persistence.getSaleSnapshot(f.display))
        verify(f.display, times(1)).setItemStack(restored)
        verify(restored).amount = 3
        assertFalse(f.runtime.isResolutionPending(f.id))
    }

    @Test fun incompleteRestorationDataStillRejectsRestoration() {
        val f = Fixture()
        f.persistence.setSaleSnapshot(f.display, null)
        f.theft.restoreStolenGroundItem(f.player, f.display)
        assertEquals(GroundItemState.STOLEN, f.persistence.getGroundItemState(f.display))
        verify(f.display, never()).setItemStack(any(ItemStack::class.java))
        verify(f.player).sendMessage("This stolen item has incomplete data")
        assertFalse(f.runtime.isResolutionPending(f.id))
    }

    private class Fixture {
        val id = UUID(0, 60)
        val display = mock(ItemDisplay::class.java)
        val player = mock(Player::class.java)
        val persistence = PersistenceService(testPlugin())
        val runtime = GroundItemRuntimeStateService()
        val theft: GroundItemTheftService
        init {
            doReturn(FakePersistentDataContainer()).`when`(display).persistentDataContainer
            doReturn(id).`when`(display).uniqueId
            doReturn(Transformation(Vector3f(), Quaternionf(), Vector3f(0.5f), Quaternionf())).`when`(display).transformation
            doReturn("Dani").`when`(player).name
            persistence.setGroundItemState(display, GroundItemState.STOLEN)
            persistence.setSaleSnapshot(display, SaleSnapshot("bread", 3, Currency.GOLD, 10, Vector3f(0.8f)))
            theft = GroundItemTheftService(
                mock(SneakyGroundItems::class.java), persistence, mock(PlacementService::class.java),
                mock(GroundItemDisplayService::class.java), mock(GroundItemMetadataService::class.java),
                mock(GroundItemLookupService::class.java), runtime,
            )
        }
    }
}

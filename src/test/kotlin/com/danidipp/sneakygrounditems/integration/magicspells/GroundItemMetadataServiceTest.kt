package com.danidipp.sneakygrounditems.integration.magicspells

import com.danidipp.sneakygrounditems.testutil.FakePersistentDataHolder
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroundItemMetadataServiceTest {
    private val service = GroundItemMetadataService()
    private val placeableKey = NamespacedKey("magicspells", "magicspellpermanentdata_grounditem_placeable")

    @Test
    fun missingPlaceableTagAllowsPlacementByDefault() {
        assertFalse(service.isExplicitlyUnplaceable(FakePersistentDataHolder()))
    }

    @Test
    fun falsePlaceableTagDisablesPlacementCaseInsensitively() {
        assertTrue(service.isExplicitlyUnplaceable(holderWithPlaceableValue("false")))
        assertTrue(service.isExplicitlyUnplaceable(holderWithPlaceableValue("False")))
        assertTrue(service.isExplicitlyUnplaceable(holderWithPlaceableValue(" FALSE ")))
    }

    @Test
    fun nonFalsePlaceableTagAllowsPlacement() {
        assertFalse(service.isExplicitlyUnplaceable(holderWithPlaceableValue("true")))
        assertFalse(service.isExplicitlyUnplaceable(holderWithPlaceableValue("anything-else")))
    }

    private fun holderWithPlaceableValue(value: String): FakePersistentDataHolder {
        return FakePersistentDataHolder().apply {
            persistentDataContainer.set(placeableKey, PersistentDataType.STRING, value)
        }
    }
}

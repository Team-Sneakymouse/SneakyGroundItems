package com.danidipp.sneakygrounditems.integration.magicspells

import com.danidipp.sneakygrounditems.testutil.FakePersistentDataHolder
import com.sneakyitems.util.items.ItemBehaviors
import org.bukkit.persistence.PersistentDataType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemExpiryTest {
    private val expiresAtKey = ItemBehaviors.EXPIRES_AT

    @Test
    fun missingExpiryIsNotExpired() {
        assertFalse(ItemExpiry.isExpired(FakePersistentDataHolder(), nowMillis = 1_000L))
    }

    @Test
    fun unixSecondsExpiryIsRecognized() {
        val holder = FakePersistentDataHolder()
        holder.persistentDataContainer.set(expiresAtKey, PersistentDataType.LONG, 1_700_000_000L)

        assertTrue(ItemExpiry.isExpired(holder, nowMillis = 1_700_000_001_000L))
        assertFalse(ItemExpiry.isExpired(holder, nowMillis = 1_699_999_999_000L))
    }

    @Test
    fun epochMillisExpiryIsRecognized() {
        val holder = FakePersistentDataHolder()
        holder.persistentDataContainer.set(expiresAtKey, PersistentDataType.LONG, 1_700_000_000_000L)

        assertTrue(ItemExpiry.isExpired(holder, nowMillis = 1_700_000_000_001L))
        assertFalse(ItemExpiry.isExpired(holder, nowMillis = 1_699_999_999_999L))
    }
}

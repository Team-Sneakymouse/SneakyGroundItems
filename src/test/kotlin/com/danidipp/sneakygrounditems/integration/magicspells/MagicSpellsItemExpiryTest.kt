package com.danidipp.sneakygrounditems.integration.magicspells

import com.danidipp.sneakygrounditems.testutil.FakePersistentDataHolder
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MagicSpellsItemExpiryTest {
    private val expiresAtKey = NamespacedKey("magicspells", "expires_at")

    @Test
    fun missingExpiryIsNotExpired() {
        assertFalse(MagicSpellsItemExpiry.isExpired(FakePersistentDataHolder(), nowMillis = 1_000L))
    }

    @Test
    fun unixSecondsExpiryIsRecognized() {
        val holder = FakePersistentDataHolder()
        holder.persistentDataContainer.set(expiresAtKey, PersistentDataType.LONG, 1_700_000_000L)

        assertTrue(MagicSpellsItemExpiry.isExpired(holder, nowMillis = 1_700_000_001_000L))
        assertFalse(MagicSpellsItemExpiry.isExpired(holder, nowMillis = 1_699_999_999_000L))
    }

    @Test
    fun epochMillisExpiryIsRecognized() {
        val holder = FakePersistentDataHolder()
        holder.persistentDataContainer.set(expiresAtKey, PersistentDataType.LONG, 1_700_000_000_000L)

        assertTrue(MagicSpellsItemExpiry.isExpired(holder, nowMillis = 1_700_000_000_001L))
        assertFalse(MagicSpellsItemExpiry.isExpired(holder, nowMillis = 1_699_999_999_999L))
    }
}

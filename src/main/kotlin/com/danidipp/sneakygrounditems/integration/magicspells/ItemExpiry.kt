package com.danidipp.sneakygrounditems.integration.magicspells

import com.sneakyitems.util.items.ItemBehaviors
import org.bukkit.persistence.PersistentDataHolder
import org.bukkit.persistence.PersistentDataType

/**
 * Reads the expiry SneakyItems stamps on items (`ItemBehaviors.EXPIRES_AT`). SneakyItems has no public
 * "already expired?" check, and older items may carry epoch seconds instead of millis, so that part stays here.
 */
internal object ItemExpiry {
    fun isExpired(holder: PersistentDataHolder, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val expiresAtMillis = getExpiresAtMillis(holder) ?: return false
        return expiresAtMillis <= nowMillis
    }

    private fun getExpiresAtMillis(holder: PersistentDataHolder): Long? {
        val rawValue = holder.persistentDataContainer.get(ItemBehaviors.EXPIRES_AT, PersistentDataType.LONG) ?: return null
        return rawValue.toEpochMillis()
    }

    // Accept epoch seconds as well as epoch millis.
    private fun Long.toEpochMillis(): Long {
        return if (this in 0 until EPOCH_MILLIS_THRESHOLD) this * 1000 else this
    }

    private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L
}

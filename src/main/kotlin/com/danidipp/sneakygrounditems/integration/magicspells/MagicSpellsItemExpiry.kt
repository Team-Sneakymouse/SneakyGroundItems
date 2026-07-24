package com.danidipp.sneakygrounditems.integration.magicspells

import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataHolder
import org.bukkit.persistence.PersistentDataType

internal object MagicSpellsItemExpiry {
    private val expiresAtKey = NamespacedKey("magicspells", "expires_at")

    fun hasExpiry(holder: PersistentDataHolder): Boolean {
        return holder.persistentDataContainer.has(expiresAtKey, PersistentDataType.LONG)
    }

    fun isExpired(holder: PersistentDataHolder, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val expiresAtMillis = getExpiresAtMillis(holder) ?: return false
        return expiresAtMillis <= nowMillis
    }

    private fun getExpiresAtMillis(holder: PersistentDataHolder): Long? {
        val rawValue = holder.persistentDataContainer.get(expiresAtKey, PersistentDataType.LONG) ?: return null
        return rawValue.toEpochMillis()
    }

    // MagicSpells data is expected to be a unix timestamp. Accept epoch seconds as well as epoch millis.
    private fun Long.toEpochMillis(): Long {
        return if (this in 0 until EPOCH_MILLIS_THRESHOLD) this * 1000 else this
    }

    private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L
}

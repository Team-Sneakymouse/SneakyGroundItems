package com.danidipp.sneakygrounditems.integration.magicspells

import com.sneakyitems.api.SneakyItemsAPI
import com.sneakyitems.util.items.ItemBehaviors
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataHolder
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

internal class GroundItemMetadataService {
    private val soulboundOwnerKey = NamespacedKey("magicspells", "soulbound_owner")
    private val groundItemSellableKey = NamespacedKey("magicspells", "magicspellpermanentdata_grounditem_sellable")
    private val groundItemPlaceableKey = NamespacedKey("magicspells", "magicspellpermanentdata_grounditem_placeable")

    fun isSoulbound(stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return false
        return meta.persistentDataContainer.has(soulboundOwnerKey, PersistentDataType.STRING)
    }

    fun isExpired(stack: ItemStack, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val meta = stack.itemMeta ?: return false
        return ItemExpiry.isExpired(meta, nowMillis)
    }

    fun hasExpiry(stack: ItemStack): Boolean = ItemBehaviors.hasExpiration(stack)

    fun isExplicitlyUnsellable(stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return false
        val rawValue = meta.persistentDataContainer.get(groundItemSellableKey, PersistentDataType.STRING) ?: return false
        return rawValue.trim().equals("false", ignoreCase = true)
    }

    fun isExplicitlyUnplaceable(stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return false
        return isExplicitlyUnplaceable(meta)
    }

    fun isExplicitlyUnplaceable(holder: PersistentDataHolder): Boolean {
        val rawValue = holder.persistentDataContainer.get(groundItemPlaceableKey, PersistentDataType.STRING) ?: return false
        return rawValue.trim().equals("false", ignoreCase = true)
    }

    fun isSoulboundToAnotherPlayer(playerUuid: UUID, stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return false
        val owner = meta.persistentDataContainer.get(soulboundOwnerKey, PersistentDataType.STRING) ?: return false
        val ownerUuid = runCatching { UUID.fromString(owner) }.getOrNull() ?: return true
        return ownerUuid != playerUuid
    }

    fun getMagicItemId(stack: ItemStack): String? = SneakyItemsAPI.keyOf(stack)

    fun hasFullDurability(stack: ItemStack): Boolean {
        val damageable = stack.itemMeta as? Damageable ?: return true
        return !damageable.hasDamage() || damageable.damage == 0
    }
}

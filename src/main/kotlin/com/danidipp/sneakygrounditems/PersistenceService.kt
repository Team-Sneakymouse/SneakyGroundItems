package com.danidipp.sneakygrounditems

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.persistence.PersistentDataHolder
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

internal class PersistenceService(plugin: SneakyGroundItems) {
    private val kindKey = NamespacedKey(plugin, "ground_item_kind")
    private val legacyKindKey = legacyKey("ground_item_kind")
    private val pairIdKey = NamespacedKey(plugin, "ground_item_pair_id")
    private val legacyPairIdKey = legacyKey("ground_item_pair_id")
    private val partnerUuidKey = NamespacedKey(plugin, "ground_item_partner_uuid")
    private val legacyPartnerUuidKey = legacyKey("ground_item_partner_uuid")
    private val faceKey = NamespacedKey(plugin, "ground_item_face")
    private val legacyFaceKey = legacyKey("ground_item_face")
    private val permanentLockModeKey = NamespacedKey(plugin, "ground_item_lock_mode")
    private val legacyPermanentLockModeKey = legacyKey("ground_item_lock_mode")
    private val temporaryLockDateKey = NamespacedKey(plugin, "ground_item_temporary_lock_date")
    private val legacyTemporaryLockDateKey = legacyKey("ground_item_temporary_lock_date")

    fun isGroundItem(entity: Entity): Boolean {
        return hasString(entity, kindKey, legacyKindKey)
    }

    fun isGroundItemDisplay(entity: Entity): Boolean {
        return entity is ItemDisplay && hasKind(entity, KIND_DISPLAY)
    }

    fun isLegacyInteraction(entity: Entity): Boolean {
        return entity is Interaction && hasKind(entity, KIND_INTERACTION)
    }

    fun tagDisplay(display: ItemDisplay) {
        writeString(display, kindKey, KIND_DISPLAY)
        removeString(display, legacyKindKey)
        display.addScoreboardTag(GROUND_ITEM_TAG)
    }

    fun getLockMode(entity: PersistentDataHolder): LockService.LockMode {
        return when {
            getString(entity, permanentLockModeKey, legacyPermanentLockModeKey) == PERMANENT_LOCK_VALUE -> LockService.LockMode.PERMANENT
            getString(entity, temporaryLockDateKey, legacyTemporaryLockDateKey) != null -> LockService.LockMode.TEMPORARY
            else -> LockService.LockMode.NONE
        }
    }

    fun getTemporaryLockDate(entity: PersistentDataHolder): String? {
        return getString(entity, temporaryLockDateKey, legacyTemporaryLockDateKey)
    }

    fun setPermanentLock(display: ItemDisplay) {
        writeString(display, permanentLockModeKey, PERMANENT_LOCK_VALUE)
        removeString(display, legacyPermanentLockModeKey)
        clearTemporaryLock(display)
    }

    fun setTemporaryLock(display: ItemDisplay, date: String) {
        clearPermanentLock(display)
        writeString(display, temporaryLockDateKey, date)
        removeString(display, legacyTemporaryLockDateKey)
    }

    fun clearTemporaryLock(display: ItemDisplay) {
        removeString(display, temporaryLockDateKey, legacyTemporaryLockDateKey)
    }

    fun clearPermanentLock(display: ItemDisplay) {
        removeString(display, permanentLockModeKey, legacyPermanentLockModeKey)
    }

    fun clearLock(display: ItemDisplay) {
        clearPermanentLock(display)
        clearTemporaryLock(display)
    }

    fun resolveDisplay(uuid: UUID): ItemDisplay? {
        return Bukkit.getEntity(uuid) as? ItemDisplay
    }

    fun removeDisplay(display: ItemDisplay) {
        display.remove()
    }

    fun cleanupLegacyDisplay(display: ItemDisplay): Boolean {
        var changed = false
        changed = migrateString(display, kindKey, legacyKindKey) || changed
        changed = migrateString(display, permanentLockModeKey, legacyPermanentLockModeKey) || changed
        changed = migrateString(display, temporaryLockDateKey, legacyTemporaryLockDateKey) || changed
        if (!hasString(display, kindKey, legacyKindKey)) {
            writeString(display, kindKey, KIND_DISPLAY)
            changed = true
        }
        if (display.scoreboardTags.none { it == GROUND_ITEM_TAG }) {
            display.addScoreboardTag(GROUND_ITEM_TAG)
            changed = true
        }
        changed = removeString(display, legacyKindKey) || changed
        changed = removeString(display, pairIdKey, legacyPairIdKey) || changed
        changed = removeString(display, partnerUuidKey, legacyPartnerUuidKey) || changed
        changed = removeString(display, faceKey, legacyFaceKey) || changed
        return changed
    }

    fun needsLegacyDisplayCleanup(display: ItemDisplay): Boolean {
        val data = display.persistentDataContainer
        return (!data.has(kindKey, PersistentDataType.STRING) && data.has(legacyKindKey, PersistentDataType.STRING)) ||
            (!data.has(permanentLockModeKey, PersistentDataType.STRING) && data.has(legacyPermanentLockModeKey, PersistentDataType.STRING)) ||
            (!data.has(temporaryLockDateKey, PersistentDataType.STRING) && data.has(legacyTemporaryLockDateKey, PersistentDataType.STRING)) ||
            !hasString(display, kindKey, legacyKindKey) ||
            display.scoreboardTags.none { it == GROUND_ITEM_TAG } ||
            data.has(legacyKindKey, PersistentDataType.STRING) ||
            data.has(pairIdKey, PersistentDataType.STRING) ||
            data.has(legacyPairIdKey, PersistentDataType.STRING) ||
            data.has(partnerUuidKey, PersistentDataType.STRING) ||
            data.has(legacyPartnerUuidKey, PersistentDataType.STRING) ||
            data.has(faceKey, PersistentDataType.STRING) ||
            data.has(legacyFaceKey, PersistentDataType.STRING)
    }

    fun getLegacyPartnerUuid(entity: PersistentDataHolder): UUID? {
        val partnerId = getString(entity, partnerUuidKey, legacyPartnerUuidKey) ?: return null
        return runCatching { UUID.fromString(partnerId) }.getOrNull()
    }

    private fun hasKind(entity: Entity, expectedKind: String): Boolean {
        return getString(entity, kindKey, legacyKindKey) == expectedKind
    }

    private fun legacyKey(key: String): NamespacedKey {
        return NamespacedKey.fromString("sneakymisc:$key")
            ?: throw IllegalStateException("Failed to create legacy key sneakymisc:$key")
    }

    private fun getString(holder: PersistentDataHolder, key: NamespacedKey, legacyKey: NamespacedKey): String? {
        val data = holder.persistentDataContainer
        return data.get(key, PersistentDataType.STRING) ?: data.get(legacyKey, PersistentDataType.STRING)
    }

    private fun writeString(holder: PersistentDataHolder, key: NamespacedKey, value: String) {
        holder.persistentDataContainer.set(key, PersistentDataType.STRING, value)
    }

    private fun migrateString(holder: PersistentDataHolder, key: NamespacedKey, legacyKey: NamespacedKey): Boolean {
        val data = holder.persistentDataContainer
        if (data.has(key, PersistentDataType.STRING)) return false

        val legacyValue = data.get(legacyKey, PersistentDataType.STRING) ?: return false
        data.set(key, PersistentDataType.STRING, legacyValue)
        return true
    }

    private fun removeString(holder: PersistentDataHolder, key: NamespacedKey): Boolean {
        val data = holder.persistentDataContainer
        val hadValue = data.has(key, PersistentDataType.STRING)
        if (hadValue) {
            data.remove(key)
        }
        return hadValue
    }

    private fun removeString(holder: PersistentDataHolder, key: NamespacedKey, legacyKey: NamespacedKey): Boolean {
        val data = holder.persistentDataContainer
        val hadCurrentValue = data.has(key, PersistentDataType.STRING)
        val hadLegacyValue = data.has(legacyKey, PersistentDataType.STRING)
        if (hadCurrentValue) {
            data.remove(key)
        }
        if (hadLegacyValue) {
            data.remove(legacyKey)
        }
        return hadCurrentValue || hadLegacyValue
    }

    private fun hasString(holder: PersistentDataHolder, key: NamespacedKey, legacyKey: NamespacedKey): Boolean {
        val data = holder.persistentDataContainer
        return data.has(key, PersistentDataType.STRING) || data.has(legacyKey, PersistentDataType.STRING)
    }

    private companion object {
        const val KIND_DISPLAY = "display"
        const val KIND_INTERACTION = "interaction"
        const val PERMANENT_LOCK_VALUE = "permanent"
        const val GROUND_ITEM_TAG = "grounditem"
    }
}

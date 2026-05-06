package com.danidipp.sneakygrounditems.persistence

import com.danidipp.sneakygrounditems.commerce.Currency
import com.danidipp.sneakygrounditems.lock.LockService
import com.danidipp.sneakygrounditems.model.GroundItemState
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.block.BlockFace
import org.bukkit.persistence.PersistentDataHolder
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID

internal class PersistenceService(plugin: Plugin) {
    private val kindKey = NamespacedKey(plugin, "ground_item_kind")
    private val legacyKindKey = legacyKey("ground_item_kind")
    private val pairIdKey = NamespacedKey(plugin, "ground_item_pair_id")
    private val legacyPairIdKey = legacyKey("ground_item_pair_id")
    private val partnerUuidKey = NamespacedKey(plugin, "ground_item_partner_uuid")
    private val legacyPartnerUuidKey = legacyKey("ground_item_partner_uuid")
    private val faceKey = NamespacedKey(plugin, "ground_item_face")
    private val legacyFaceKey = legacyKey("ground_item_face")
    private val stateKey = NamespacedKey(plugin, "ground_item_state")
    private val currencyKey = NamespacedKey(plugin, "ground_item_currency")
    private val priceKey = NamespacedKey(plugin, "ground_item_price")
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

    fun isGroundItemText(entity: Entity): Boolean {
        return entity is TextDisplay && hasKind(entity, KIND_TEXT)
    }

    fun isLegacyInteraction(entity: Entity): Boolean {
        return entity is Interaction && hasKind(entity, KIND_INTERACTION)
    }

    fun tagDisplay(display: ItemDisplay) {
        writeString(display, kindKey, KIND_DISPLAY)
        removeString(display, legacyKindKey)
        display.addScoreboardTag(GROUND_ITEM_TAG)
    }

    fun tagText(display: TextDisplay) {
        writeString(display, kindKey, KIND_TEXT)
        removeString(display, legacyKindKey)
        display.addScoreboardTag(GROUND_ITEM_TAG)
    }

    fun getGroundItemState(entity: PersistentDataHolder): GroundItemState {
        val rawValue = entity.persistentDataContainer.get(stateKey, PersistentDataType.STRING) ?: return GroundItemState.NORMAL
        return GroundItemState.fromId(rawValue) ?: GroundItemState.NORMAL
    }

    fun setGroundItemState(entity: PersistentDataHolder, state: GroundItemState) {
        if (state == GroundItemState.NORMAL) {
            entity.persistentDataContainer.remove(stateKey)
            return
        }

        entity.persistentDataContainer.set(stateKey, PersistentDataType.STRING, state.id)
    }

    fun getCurrency(entity: PersistentDataHolder): Currency? {
        val rawValue = entity.persistentDataContainer.get(currencyKey, PersistentDataType.STRING) ?: return null
        return Currency.fromId(rawValue)
    }

    fun setCurrency(entity: PersistentDataHolder, currency: Currency?) {
        val data = entity.persistentDataContainer
        if (currency == null) {
            data.remove(currencyKey)
            return
        }

        data.set(currencyKey, PersistentDataType.STRING, currency.id)
    }

    fun getPrice(entity: PersistentDataHolder): Int? {
        val rawValue = entity.persistentDataContainer.get(priceKey, PersistentDataType.STRING) ?: return null
        return rawValue.toIntOrNull()?.takeIf { it >= 0 }
    }

    fun setPrice(entity: PersistentDataHolder, price: Int?) {
        val data = entity.persistentDataContainer
        if (price == null) {
            data.remove(priceKey)
            return
        }

        data.set(priceKey, PersistentDataType.STRING, price.toString())
    }

    fun getPartnerUuid(entity: PersistentDataHolder): UUID? {
        val partnerId = getString(entity, partnerUuidKey, legacyPartnerUuidKey) ?: return null
        return runCatching { UUID.fromString(partnerId) }.getOrNull()
    }

    fun setPartnerUuid(entity: PersistentDataHolder, partnerUuid: UUID?) {
        if (partnerUuid == null) {
            removeString(entity, partnerUuidKey, legacyPartnerUuidKey)
            return
        }

        writeString(entity, partnerUuidKey, partnerUuid.toString())
        removeString(entity, legacyPartnerUuidKey)
    }

    fun getFace(entity: PersistentDataHolder): BlockFace? {
        val rawValue = getString(entity, faceKey, legacyFaceKey) ?: return null
        return runCatching { BlockFace.valueOf(rawValue) }.getOrNull()
    }

    fun setFace(entity: PersistentDataHolder, face: BlockFace?) {
        if (face == null) {
            removeString(entity, faceKey, legacyFaceKey)
            return
        }

        writeString(entity, faceKey, face.name)
        removeString(entity, legacyFaceKey)
    }

    fun clearCommerceData(entity: PersistentDataHolder) {
        setGroundItemState(entity, GroundItemState.NORMAL)
        setCurrency(entity, null)
        setPrice(entity, null)
        setPartnerUuid(entity, null)
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

    fun resolveTextDisplay(uuid: UUID): TextDisplay? {
        return Bukkit.getEntity(uuid) as? TextDisplay
    }

    fun removeDisplay(display: ItemDisplay) {
        display.remove()
    }

    fun removeTextDisplay(display: TextDisplay) {
        display.remove()
    }

    fun cleanupLegacyDisplay(display: ItemDisplay): Boolean {
        var changed = false
        changed = migrateString(display, kindKey, legacyKindKey) || changed
        changed = migrateString(display, permanentLockModeKey, legacyPermanentLockModeKey) || changed
        changed = migrateString(display, temporaryLockDateKey, legacyTemporaryLockDateKey) || changed
        changed = migrateString(display, faceKey, legacyFaceKey) || changed
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
        return changed
    }

    fun needsLegacyDisplayCleanup(display: ItemDisplay): Boolean {
        val data = display.persistentDataContainer
        return (!data.has(kindKey, PersistentDataType.STRING) && data.has(legacyKindKey, PersistentDataType.STRING)) ||
            (!data.has(permanentLockModeKey, PersistentDataType.STRING) && data.has(legacyPermanentLockModeKey, PersistentDataType.STRING)) ||
            (!data.has(temporaryLockDateKey, PersistentDataType.STRING) && data.has(legacyTemporaryLockDateKey, PersistentDataType.STRING)) ||
            (!data.has(faceKey, PersistentDataType.STRING) && data.has(legacyFaceKey, PersistentDataType.STRING)) ||
            !hasString(display, kindKey, legacyKindKey) ||
            display.scoreboardTags.none { it == GROUND_ITEM_TAG } ||
            data.has(legacyKindKey, PersistentDataType.STRING) ||
            data.has(pairIdKey, PersistentDataType.STRING) ||
            data.has(legacyPairIdKey, PersistentDataType.STRING) ||
            data.has(legacyFaceKey, PersistentDataType.STRING)
    }

    fun getLegacyPartnerUuid(entity: PersistentDataHolder): UUID? {
        return getPartnerUuid(entity)
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
        const val KIND_TEXT = "text"
        const val KIND_INTERACTION = "interaction"
        const val PERMANENT_LOCK_VALUE = "permanent"
        const val GROUND_ITEM_TAG = "grounditem"
    }
}

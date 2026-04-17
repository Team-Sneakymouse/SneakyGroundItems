package com.danidipp.sneakygrounditems

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.persistence.PersistentDataHolder
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

internal class GroundItemPersistence(
    plugin: SneakyGroundItems,
) {
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

    fun isInteraction(entity: Entity): Boolean {
        return entity is Interaction && hasKind(entity, KIND_INTERACTION)
    }

    fun isGroundItem(entity: Entity): Boolean {
        return hasString(entity, kindKey, legacyKindKey)
    }

    fun resolveTarget(entity: Entity): GroundItemTarget? {
        val pairId = getPairId(entity) ?: return null
        return when {
            entity is Interaction && hasKind(entity, KIND_INTERACTION) -> {
                val display = resolvePartner(entity, KIND_DISPLAY) as? ItemDisplay ?: return null
                GroundItemTarget(display, entity, pairId)
            }

            entity is ItemDisplay && hasKind(entity, KIND_DISPLAY) -> {
                val interaction = resolvePartner(entity, KIND_INTERACTION) as? Interaction ?: return null
                GroundItemTarget(entity, interaction, pairId)
            }

            else -> null
        }
    }

    fun tagDisplay(display: ItemDisplay, pairId: String) {
        tagEntity(display, KIND_DISPLAY, pairId)
    }

    fun tagInteraction(interaction: Interaction, pairId: String) {
        tagEntity(interaction, KIND_INTERACTION, pairId)
    }

    fun linkPair(display: ItemDisplay, interaction: Interaction, face: BlockFace) {
        setPartner(display, interaction.uniqueId)
        setPartner(interaction, display.uniqueId)
        setFace(display, face)
        setFace(interaction, face)
    }

    fun getPairId(entity: Entity): String? {
        return getString(entity, pairIdKey, legacyPairIdKey)
    }

    fun getStoredFace(entity: PersistentDataHolder): BlockFace? {
        val faceName = getString(entity, faceKey, legacyFaceKey) ?: return null
        return runCatching { BlockFace.valueOf(faceName) }.getOrNull()
    }

    fun getLockMode(entity: PersistentDataHolder): LockMode {
        return when {
            getString(entity, permanentLockModeKey, legacyPermanentLockModeKey) == PERMANENT_LOCK_VALUE -> LockMode.PERMANENT
            getString(entity, temporaryLockDateKey, legacyTemporaryLockDateKey) != null -> LockMode.TEMPORARY
            else -> LockMode.NONE
        }
    }

    fun getTemporaryLockDate(entity: PersistentDataHolder): String? {
        return getString(entity, temporaryLockDateKey, legacyTemporaryLockDateKey)
    }

    fun setPermanentLock(target: GroundItemTarget) {
        writeString(target.display, permanentLockModeKey, PERMANENT_LOCK_VALUE)
        writeString(target.interaction, permanentLockModeKey, PERMANENT_LOCK_VALUE)
        clearTemporaryLock(target)
    }

    fun setTemporaryLock(target: GroundItemTarget, date: String) {
        clearPermanentLock(target)
        writeString(target.display, temporaryLockDateKey, date)
        writeString(target.interaction, temporaryLockDateKey, date)
    }

    fun clearTemporaryLock(target: GroundItemTarget) {
        removeString(target.display, temporaryLockDateKey, legacyTemporaryLockDateKey)
        removeString(target.interaction, temporaryLockDateKey, legacyTemporaryLockDateKey)
    }

    fun clearPermanentLock(target: GroundItemTarget) {
        removeString(target.display, permanentLockModeKey, legacyPermanentLockModeKey)
        removeString(target.interaction, permanentLockModeKey, legacyPermanentLockModeKey)
    }

    fun clearLock(target: GroundItemTarget) {
        clearPermanentLock(target)
        clearTemporaryLock(target)
    }

    fun resolveDisplay(uuid: UUID): ItemDisplay? {
        return Bukkit.getEntity(uuid) as? ItemDisplay
    }

    fun resolveInteraction(uuid: UUID): Interaction? {
        return Bukkit.getEntity(uuid) as? Interaction
    }

    fun removePair(target: GroundItemTarget) {
        removePair(target.display, target.interaction)
    }

    fun removePair(display: ItemDisplay, interaction: Interaction) {
        display.remove()
        interaction.remove()
    }

    private fun tagEntity(entity: PersistentDataHolder, kind: String, pairId: String) {
        writeString(entity, kindKey, kind)
        writeString(entity, pairIdKey, pairId)
        writeString(entity, partnerUuidKey, "")
        (entity as? Entity)?.addScoreboardTag(GROUND_ITEM_TAG)
    }

    private fun setPartner(entity: PersistentDataHolder, partnerUuid: UUID) {
        writeString(entity, partnerUuidKey, partnerUuid.toString())
    }

    private fun setFace(entity: PersistentDataHolder, face: BlockFace) {
        writeString(entity, faceKey, face.name)
    }

    private fun hasKind(entity: Entity, expectedKind: String): Boolean {
        return getString(entity, kindKey, legacyKindKey) == expectedKind
    }

    private fun resolvePartner(entity: Entity, expectedKind: String): Entity? {
        val partnerId = getString(entity, partnerUuidKey, legacyPartnerUuidKey) ?: return null
        val partnerUuid = runCatching { UUID.fromString(partnerId) }.getOrNull() ?: return null
        val partner = Bukkit.getEntity(partnerUuid) ?: return null
        return partner.takeIf { hasKind(it, expectedKind) }
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
        val data = holder.persistentDataContainer
        data.set(key, PersistentDataType.STRING, value)
    }

    private fun removeString(holder: PersistentDataHolder, key: NamespacedKey, legacyKey: NamespacedKey) {
        val data = holder.persistentDataContainer
        data.remove(key)
        data.remove(legacyKey)
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

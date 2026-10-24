package com.danidipp.sneakygrounditems.display

import com.danidipp.sneakygrounditems.config.Constants
import com.danidipp.sneakygrounditems.integration.magicspells.GroundItemMetadataService
import com.danidipp.sneakygrounditems.lock.LockService
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay

/** Owns real item changes. Derived labels never participate in these operations. */
internal class GroundItemDisplayService(
    private val persistence: PersistenceService,
    private val lockService: LockService,
    private val placementService: PlacementService,
    private val metadataService: GroundItemMetadataService,
) {
    fun processLoadedChunks() {
        for (world in Bukkit.getWorlds()) {
            for (chunk in world.loadedChunks) processChunk(chunk)
        }
    }

    fun processChunk(chunk: Chunk) = processEntities(chunk.entities.asList())

    fun processEntities(entities: List<Entity>) {
        val now = System.currentTimeMillis()
        for (entity in entities) {
            when {
                entity is TextDisplay && persistence.isGroundItemText(entity) -> persistence.removeTextDisplay(entity)
                entity is ItemDisplay && persistence.isGroundItemDisplay(entity) -> {
                    // Old companion references are migration data, never live ownership.
                    persistence.setPartnerUuid(entity, null)
                    if (metadataService.hasExpiredMagicSpellsLifetime(entity.itemStack, now)) {
                        persistence.removeDisplay(entity)
                        continue
                    }
                    entity.viewRange = Constants.ITEM_DISPLAY_VIEW_RANGE
                    lockService.clearExpiredTemporaryLock(entity)
                    lockService.clearInvalidPlotLock(entity)
                    when (persistence.getGroundItemState(entity)) {
                        GroundItemState.NORMAL -> normalizeDisplay(entity)
                        GroundItemState.SALE -> prepareSaleDisplay(entity)
                        GroundItemState.PAYMENT, GroundItemState.STOLEN -> ensureDisplayFace(entity)
                    }
                }
            }
        }
    }

    fun normalizeDisplay(display: ItemDisplay) {
        persistence.clearCommerceData(display)
        val scale = placementService.scaleForStack(display.itemStack)
        if (placementService.displayScale(display) != scale) {
            display.transformation = placementService.rescaleTransformation(display.transformation, scale)
        }
    }

    fun prepareSaleDisplay(display: ItemDisplay) {
        ensureDisplayFace(display)
        val scale = placementService.minimumScaleForStack(display.itemStack)
        if (placementService.displayScale(display) != scale) {
            display.transformation = placementService.rescaleTransformation(display.transformation, scale)
        }
    }

    fun ensureDisplayFace(display: ItemDisplay): BlockFace {
        persistence.getFace(display)?.let { return it }
        return placementService.deriveFace(display).also { persistence.setFace(display, it) }
    }

    fun removeDisplay(display: ItemDisplay) {
        // Remove a still-loaded legacy companion until all chunks have migrated.
        persistence.getPartnerUuid(display)?.let(persistence::resolveTextDisplay)
            ?.takeIf { persistence.isGroundItemText(it) && persistence.getPartnerUuid(it) == display.uniqueId }
            ?.let(persistence::removeTextDisplay)
        persistence.removeDisplay(display)
    }
}

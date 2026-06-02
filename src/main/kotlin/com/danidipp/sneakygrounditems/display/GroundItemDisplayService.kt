package com.danidipp.sneakygrounditems.display

import com.danidipp.sneakygrounditems.commerce.Currency
import com.danidipp.sneakygrounditems.config.Constants
import com.danidipp.sneakygrounditems.integration.magicspells.GroundItemMetadataService
import com.danidipp.sneakygrounditems.lock.LockService
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.block.BlockFace
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

internal class GroundItemDisplayService(
    private val persistence: PersistenceService,
    private val lockService: LockService,
    private val placementService: PlacementService,
    private val metadataService: GroundItemMetadataService,
) {
    fun processLoadedChunks() {
        for (world in Bukkit.getWorlds()) {
            for (chunk in world.loadedChunks) {
                processChunk(chunk)
            }
        }
    }

    fun processChunk(chunk: Chunk) {
        val displays = mutableListOf<ItemDisplay>()
        val companionTextByPartner = mutableMapOf<UUID, MutableList<TextDisplay>>()
        val now = System.currentTimeMillis()

        for (entity in chunk.entities) {
            when {
                entity is ItemDisplay && persistence.isGroundItemDisplay(entity) -> {
                    if (metadataService.hasExpiredMagicSpellsLifetime(entity.itemStack, now)) {
                        removeDisplayAndCompanion(entity)
                        continue
                    }

                    entity.viewRange = Constants.ITEM_DISPLAY_VIEW_RANGE
                    displays += entity
                    lockService.clearExpiredTemporaryLock(entity)
                    lockService.clearInvalidPlotLock(entity)
                }

                entity is TextDisplay && persistence.isGroundItemText(entity) -> {
                    val partnerUuid = persistence.getPartnerUuid(entity)
                    if (partnerUuid == null) {
                        persistence.removeTextDisplay(entity)
                        continue
                    }

                    companionTextByPartner.getOrPut(partnerUuid) { mutableListOf() }.add(entity)
                }
            }
        }

        for (display in displays) {
            reconcileCommerceDisplay(display, companionTextByPartner.remove(display.uniqueId).orEmpty())
        }

        for (orphanGroup in companionTextByPartner.values) {
            for (orphan in orphanGroup) {
                persistence.removeTextDisplay(orphan)
            }
        }
    }

    fun syncCompanionText(display: ItemDisplay): Boolean {
        val state = persistence.getGroundItemState(display)
        if (state == GroundItemState.NORMAL) {
            reconcileNormalDisplay(display, emptyList())
            return true
        }

        val commerceData = readCommerceData(display, state) ?: return false
        val face = ensureDisplayFace(display)
        rescaleForCommerceState(display, state)
        val linkedText = persistence.getPartnerUuid(display)
            ?.let(persistence::resolveTextDisplay)
            ?.takeIf { persistence.isGroundItemText(it) && persistence.getPartnerUuid(it) == display.uniqueId }

        val textDisplay = linkedText ?: spawnCompanionText(display)
        if (textDisplay == null) {
            persistence.setPartnerUuid(display, null)
            return false
        }

        updateCompanionText(textDisplay, display, commerceData, face)
        return true
    }

    fun normalizeDisplay(display: ItemDisplay) {
        persistence.setGroundItemState(display, GroundItemState.NORMAL)
        persistence.setCurrency(display, null)
        persistence.setPrice(display, null)
        rescaleForNormalState(display)
        syncCompanionText(display)
    }

    fun ensureDisplayFace(display: ItemDisplay): BlockFace {
        val existingFace = persistence.getFace(display)
        if (existingFace != null) return existingFace

        val derivedFace = placementService.deriveFace(display)
        persistence.setFace(display, derivedFace)
        return derivedFace
    }

    fun removeDisplayAndCompanion(display: ItemDisplay) {
        val partnerUuid = persistence.getPartnerUuid(display)
            ?: return persistence.removeDisplay(display)
        val textDisplay = persistence.resolveTextDisplay(partnerUuid)
            ?.takeIf { persistence.isGroundItemText(it) && persistence.getPartnerUuid(it) == display.uniqueId }

        if (textDisplay != null) {
            persistence.removeTextDisplay(textDisplay)
        }
        persistence.removeDisplay(display)
    }

    private fun reconcileCommerceDisplay(display: ItemDisplay, candidates: List<TextDisplay>) {
        val state = persistence.getGroundItemState(display)
        if (state == GroundItemState.NORMAL) {
            reconcileNormalDisplay(display, candidates)
            return
        }

        val commerceData = readCommerceData(display, state)
        if (commerceData == null) {
            reconcileNormalDisplay(display, candidates)
            return
        }

        val face = ensureDisplayFace(display)
        rescaleForCommerceState(display, state)
        val linkedText = persistence.getPartnerUuid(display)
            ?.let(persistence::resolveTextDisplay)
            ?.takeIf { persistence.isGroundItemText(it) && persistence.getPartnerUuid(it) == display.uniqueId }
        val chosenText = (listOfNotNull(linkedText) + candidates)
            .distinctBy { it.uniqueId }
            .firstOrNull()
            ?: spawnCompanionText(display)

        if (chosenText == null) {
            persistence.setPartnerUuid(display, null)
            return
        }

        updateCompanionText(chosenText, display, commerceData, face)

        for (candidate in candidates) {
            if (candidate.uniqueId != chosenText.uniqueId) {
                persistence.removeTextDisplay(candidate)
            }
        }
        if (linkedText != null && linkedText.uniqueId != chosenText.uniqueId) {
            persistence.removeTextDisplay(linkedText)
        }
    }

    private fun reconcileNormalDisplay(display: ItemDisplay, candidates: List<TextDisplay>) {
        val linkedText = persistence.getPartnerUuid(display)
            ?.let(persistence::resolveTextDisplay)
            ?.takeIf { persistence.getPartnerUuid(it) == display.uniqueId }

        if (linkedText != null) {
            persistence.removeTextDisplay(linkedText)
        }
        for (candidate in candidates) {
            if (candidate.uniqueId != linkedText?.uniqueId) {
                persistence.removeTextDisplay(candidate)
            }
        }

        persistence.clearCommerceData(display)
        rescaleForNormalState(display)
    }

    private fun rescaleForNormalState(display: ItemDisplay) {
        val stackScale = placementService.scaleForStack(display.itemStack)
        if (placementService.displayScale(display) == stackScale) return
        display.transformation = placementService.rescaleTransformation(display.transformation, stackScale)
    }

    private fun rescaleForCommerceState(display: ItemDisplay, state: GroundItemState) {
        if (state != GroundItemState.SALE) return

        val minimumScale = placementService.minimumScaleForStack(display.itemStack)
        if (placementService.displayScale(display) == minimumScale) return
        display.transformation = placementService.rescaleTransformation(display.transformation, minimumScale)
    }

    private fun spawnCompanionText(display: ItemDisplay): TextDisplay? {
        var textDisplay: TextDisplay? = null

        return runCatching {
            textDisplay = display.world.spawn(display.location, TextDisplay::class.java) { spawnedDisplay ->
                spawnedDisplay.isPersistent = true
                spawnedDisplay.setGravity(false)
                persistence.tagText(spawnedDisplay)
            }
            textDisplay
        }.getOrElse {
            textDisplay?.remove()
            null
        }
    }

    private fun updateCompanionText(textDisplay: TextDisplay, display: ItemDisplay, commerceData: CommerceData, face: BlockFace) {
        val surface = placementService.surfaceForFace(face)
        val offset = if (surface == PlacementSurface.WALL) {
            Vector(0.0, TEXT_OFFSET.toDouble(), 0.0)
        } else {
            face.direction.clone().normalize().multiply(TEXT_OFFSET.toDouble())
        }
        textDisplay.teleport(display.location.clone().add(offset))
        textDisplay.isPersistent = true
        textDisplay.setGravity(false)
        textDisplay.viewRange = Constants.TEXT_DISPLAY_VIEW_RANGE
        textDisplay.transformation = Transformation(
            Vector3f(),
            Quaternionf(),
            Vector3f(TEXT_SCALE, TEXT_SCALE, TEXT_SCALE),
            Quaternionf(),
        )
        textDisplay.billboard = billboardForFace(face)
        if (surface == PlacementSurface.WALL) {
            textDisplay.setRotation(yawForFace(face), 0.0f)
        } else {
            textDisplay.setRotation(0.0f, 0.0f)
        }
        textDisplay.text(buildCompanionText(display.itemStack, commerceData))

        persistence.tagText(textDisplay)
        persistence.setGroundItemState(textDisplay, commerceData.state)
        persistence.setPartnerUuid(textDisplay, display.uniqueId)
        persistence.setPartnerUuid(display, textDisplay.uniqueId)
    }

    private fun buildCompanionText(stack: ItemStack, commerceData: CommerceData): Component {
        return when (commerceData.state) {
            GroundItemState.SALE -> {
                val firstLine = Component.text()
                    .append(Component.text("${stack.amount}x "))
                    .append(stack.displayName())
                    .build()
                val secondLine = Component.text()
                    .append(buildPriceLabel(commerceData.price, commerceData.currency, stack.amount, NamedTextColor.GOLD))
                    .build()
                firstLine.append(Component.newline()).append(secondLine)
            }

            GroundItemState.PAYMENT -> Component.text("${commerceData.price} ${commerceData.currency}")
            GroundItemState.NORMAL -> Component.empty()
        }
    }

    private fun buildPriceLabel(totalPrice: Int, currency: Currency, quantity: Int, priceColor: NamedTextColor): Component {
        val priceLabel = Component.text()
            .append(Component.text("$totalPrice ", priceColor))
            .append(Component.text(currency.toString(), NamedTextColor.GOLD))

        if (quantity <= 1) {
            return priceLabel.build()
        }

        return priceLabel
            .append(Component.text(" (", NamedTextColor.YELLOW))
            .append(Component.text(formatEachPrice(totalPrice, quantity), NamedTextColor.GOLD))
            .append(Component.text(" each)", NamedTextColor.YELLOW))
            .build()
    }

    private fun formatEachPrice(totalPrice: Int, quantity: Int): String {
        val divisor = quantity.coerceAtLeast(1)
        return java.math.BigDecimal.valueOf(totalPrice.toLong())
            .divide(java.math.BigDecimal.valueOf(divisor.toLong()), 1, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    private fun readCommerceData(display: ItemDisplay, expectedState: GroundItemState): CommerceData? {
        if (persistence.getGroundItemState(display) != expectedState) return null

        val currency = persistence.getCurrency(display) ?: return null
        val price = persistence.getPrice(display) ?: return null
        return CommerceData(expectedState, currency, price)
    }

    private fun billboardForFace(face: BlockFace): Display.Billboard {
        return when (placementService.surfaceForFace(face)) {
            PlacementSurface.GROUND -> Display.Billboard.VERTICAL
            PlacementSurface.WALL -> Display.Billboard.FIXED
            PlacementSurface.CEILING -> Display.Billboard.CENTER
        }
    }

    private fun yawForFace(face: BlockFace): Float {
        return when (face) {
            BlockFace.NORTH -> 180.0f
            BlockFace.SOUTH -> 0.0f
            BlockFace.EAST -> -90.0f
            BlockFace.WEST -> 90.0f
            else -> 0.0f
        }
    }
    private data class CommerceData(
        val state: GroundItemState,
        val currency: Currency,
        val price: Int,
    )

    companion object {
        private const val TEXT_SCALE = 0.15f
        private const val TEXT_OFFSET = 0.25f
    }
}

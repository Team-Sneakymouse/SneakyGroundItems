package com.danidipp.sneakygrounditems

import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.entity.Display
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

internal class GroundItemPlacementService(
    private val persistence: GroundItemPersistence,
    private val lockService: GroundItemLockService,
) {
    private val groundItemMinScaleKey = NamespacedKey.fromString("magicspells:magicspellpermanentdata_grounditem_min_scale")
        ?: throw IllegalStateException("Failed to create NamespacedKey for magicspells:magicspellpermanentdata_grounditem_min_scale")
    private val groundItemMaxScaleKey = NamespacedKey.fromString("magicspells:magicspellpermanentdata_grounditem_max_scale")
        ?: throw IllegalStateException("Failed to create NamespacedKey for magicspells:magicspellpermanentdata_grounditem_max_scale")

    fun buildPlacement(playerYaw: Float, hitPosition: Location, hitFace: BlockFace, stack: ItemStack): Placement {
        val normal = hitFace.direction.clone().normalize()
        val stackScale = scaleForStack(stack)
        val randomOffset = ThreadLocalRandom.current().nextDouble(0.0, GroundItemConstants.SURFACE_RANDOM_OFFSET)
        val anchor = hitPosition.clone()
            .add(normal.clone().multiply(GroundItemConstants.SURFACE_EPSILON))
            .add(normal.clone().multiply(randomOffset))
        val interactionLocation = anchor.clone().add(normal.clone().multiply(interactionNormalOffset(stackScale).toDouble()))

        return Placement(
            location = anchor,
            interactionLocation = interactionLocation,
            interactionScale = stackScale,
            transformation = transformationForFace(hitFace, playerYaw, stackScale),
            face = hitFace,
        )
    }

    fun spawnPair(placement: Placement, stack: ItemStack): Boolean {
        var display: ItemDisplay? = null
        var interaction: Interaction? = null

        return runCatching {
            val pairId = UUID.randomUUID().toString()

            display = placement.location.world.spawn(placement.location, ItemDisplay::class.java) { itemDisplay ->
                itemDisplay.isPersistent = true
                itemDisplay.setGravity(false)
                itemDisplay.setItemStack(stack)
                itemDisplay.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                itemDisplay.billboard = Display.Billboard.FIXED
                itemDisplay.transformation = placement.transformation
                itemDisplay.setRotation(0.0f, 0.0f)
                persistence.tagDisplay(itemDisplay, pairId)
            }

            interaction = placement.interactionLocation.world.spawn(placement.interactionLocation, Interaction::class.java) { target ->
                target.isPersistent = true
                target.setGravity(false)
                target.isResponsive = true
                target.interactionWidth = placement.interactionScale
                target.interactionHeight = placement.interactionScale
                persistence.tagInteraction(target, pairId)
            }

            val spawnedDisplay = display ?: return@runCatching false
            val spawnedInteraction = interaction ?: return@runCatching false
            persistence.linkPair(spawnedDisplay, spawnedInteraction, placement.face)
            true
        }.getOrElse {
            display?.remove()
            interaction?.remove()
            false
        }
    }

    fun mergeIntoNearbyDisplay(location: Location, stack: ItemStack): MergeResult? {
        val mergeTarget = findMergeTarget(location, stack) ?: return null
        val currentStack = mergeTarget.itemStack.clone()
        val maxStackSize = currentStack.maxStackSize.coerceAtLeast(1)
        val spaceLeft = maxStackSize - currentStack.amount
        if (spaceLeft <= 0) return null

        val mergeAmount = minOf(spaceLeft, stack.amount)
        currentStack.amount += mergeAmount
        val newScale = scaleForStack(currentStack)
        mergeTarget.setItemStack(currentStack)
        mergeTarget.transformation = rescaleTransformation(mergeTarget.transformation, newScale)
        updateInteractionForDisplayScale(mergeTarget, newScale)

        return MergeResult(
            display = mergeTarget,
            mergedAmount = mergeAmount,
            remainder = stack.amount - mergeAmount,
        )
    }

    private fun findMergeTarget(location: Location, stack: ItemStack): ItemDisplay? {
        return location.world.getNearbyEntities(
            location,
            GroundItemConstants.MERGE_SEARCH_RADIUS,
            GroundItemConstants.MERGE_SEARCH_RADIUS,
            GroundItemConstants.MERGE_SEARCH_RADIUS,
        ).asSequence()
            .filterIsInstance<ItemDisplay>()
            .mapNotNull(persistence::resolveTarget)
            .filter { lockService.getLockMode(it) == LockMode.NONE }
            .filter { it.display.itemStack.isSimilar(stack) }
            .filter { it.display.itemStack.amount < it.display.itemStack.maxStackSize.coerceAtLeast(1) }
            .minByOrNull { it.display.location.distanceSquared(location) }
            ?.display
    }

    private fun updateInteractionForDisplayScale(display: ItemDisplay, stackScale: Float) {
        val target = persistence.resolveTarget(display) ?: return
        target.interaction.interactionWidth = stackScale
        target.interaction.interactionHeight = stackScale

        val face = persistence.getStoredFace(display) ?: return
        val normal = face.direction.clone().normalize()
        target.interaction.teleport(display.location.clone().add(normal.multiply(interactionNormalOffset(stackScale).toDouble())))
    }

    private fun rescaleTransformation(existing: Transformation, stackScale: Float): Transformation {
        return Transformation(
            Vector3f(existing.translation),
            Quaternionf(existing.leftRotation),
            Vector3f(stackScale, stackScale, stackScale),
            Quaternionf(existing.rightRotation),
        )
    }

    private fun transformationForFace(face: BlockFace, playerYaw: Float, stackScale: Float): Transformation {
        val rotation = when (face) {
            BlockFace.UP -> Quaternionf()
                .rotateY(Math.toRadians((-playerYaw + 180.0)).toFloat())
                .rotateX((-Math.PI / 2.0).toFloat())

            BlockFace.DOWN -> Quaternionf()
                .rotateY(Math.toRadians((-playerYaw + 180.0)).toFloat())
                .rotateX((Math.PI / 2.0).toFloat())

            BlockFace.NORTH -> Quaternionf().rotateY(Math.PI.toFloat())
            BlockFace.SOUTH -> Quaternionf()
            BlockFace.EAST -> Quaternionf().rotateY((Math.PI / 2.0).toFloat())
            BlockFace.WEST -> Quaternionf().rotateY((-Math.PI / 2.0).toFloat())
            else -> Quaternionf()
        }

        return Transformation(
            Vector3f(),
            rotation,
            Vector3f(stackScale, stackScale, stackScale),
            Quaternionf(),
        )
    }

    private fun scaleForStack(stack: ItemStack): Float {
        val scaleRange = resolveGroundItemScaleRange(stack)
        val maxStackSize = stack.maxStackSize.coerceAtLeast(1)
        if (maxStackSize == 1) return scaleRange.max

        val amount = stack.amount.coerceIn(1, maxStackSize)
        val progress = (amount - 1).toFloat() / (maxStackSize - 1).toFloat()
        return scaleRange.min + (progress * (scaleRange.max - scaleRange.min))
    }

    private fun resolveGroundItemScaleRange(stack: ItemStack): ScaleRange {
        val minScale = readScaleOverride(stack, groundItemMinScaleKey, GroundItemConstants.DEFAULT_MIN_SCALE)
        val maxScale = readScaleOverride(stack, groundItemMaxScaleKey, GroundItemConstants.DEFAULT_MAX_SCALE)

        return if (maxScale < minScale) {
            ScaleRange(maxScale, minScale)
        } else {
            ScaleRange(minScale, maxScale)
        }
    }

    private fun readScaleOverride(stack: ItemStack, key: NamespacedKey, defaultValue: Float): Float {
        val meta = stack.itemMeta ?: return defaultValue
        val rawValue = meta.persistentDataContainer.get(key, PersistentDataType.STRING) ?: return defaultValue
        val parsedValue = rawValue.toFloatOrNull() ?: return defaultValue
        if (!parsedValue.isFinite()) return defaultValue
        return parsedValue.coerceAtLeast(0.0f)
    }

    private fun interactionNormalOffset(stackScale: Float): Float {
        return maxOf(0.0f, (stackScale - 0.5f) * 0.5f)
    }

    internal data class Placement(
        val location: Location,
        val interactionLocation: Location,
        val interactionScale: Float,
        val transformation: Transformation,
        val face: BlockFace,
    )

    internal data class MergeResult(
        val display: ItemDisplay,
        val mergedAmount: Int,
        val remainder: Int,
    )

    private data class ScaleRange(
        val min: Float,
        val max: Float,
    )
}

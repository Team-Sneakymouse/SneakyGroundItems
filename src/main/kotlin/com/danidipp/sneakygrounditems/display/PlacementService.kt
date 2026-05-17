package com.danidipp.sneakygrounditems.display

import com.danidipp.sneakygrounditems.config.Constants
import com.danidipp.sneakygrounditems.lock.LockService
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import java.util.concurrent.ThreadLocalRandom

internal class PlacementService(
    private val persistence: PersistenceService,
    private val lockService: LockService,
) {
    private val groundItemMinScaleKey = NamespacedKey("magicspells", "magicspellpermanentdata_grounditem_min_scale")
    private val groundItemMaxScaleKey = NamespacedKey("magicspells", "magicspellpermanentdata_grounditem_max_scale")

    fun buildPlacement(playerYaw: Float, hitPosition: Location, hitFace: BlockFace, stack: ItemStack): Placement {
        val normal = hitFace.direction.clone().normalize()
        val stackScale = scaleForStack(stack)
        val randomOffset = ThreadLocalRandom.current().nextDouble(0.0, Constants.SURFACE_RANDOM_OFFSET)
        val anchor = hitPosition.clone()
            .add(normal.clone().multiply(Constants.SURFACE_EPSILON))
            .add(normal.clone().multiply(randomOffset))

        return Placement(
            location = anchor,
            transformation = transformationForFace(hitFace, playerYaw, stackScale),
            face = hitFace,
        )
    }

    fun spawnDisplay(placement: Placement, stack: ItemStack): Boolean {
        var display: ItemDisplay? = null

        return runCatching {
            display = placement.location.world.spawn(placement.location, ItemDisplay::class.java) { itemDisplay ->
                itemDisplay.isPersistent = true
                itemDisplay.setGravity(false)
                itemDisplay.viewRange = Constants.ITEM_DISPLAY_VIEW_RANGE
                itemDisplay.setItemStack(stack)
                itemDisplay.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                itemDisplay.billboard = Display.Billboard.FIXED
                itemDisplay.transformation = placement.transformation
                itemDisplay.setRotation(0.0f, 0.0f)
                persistence.tagDisplay(itemDisplay)
                persistence.setFace(itemDisplay, placement.face)
            }

            true
        }.getOrElse {
            display?.remove()
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

        return MergeResult(
            display = mergeTarget,
            mergedAmount = mergeAmount,
            remainder = stack.amount - mergeAmount,
        )
    }

    fun selectionRadiusForDisplay(display: ItemDisplay): Double {
        return (scaleForStack(display.itemStack) / 2.0f)
            .coerceIn(Constants.MIN_SELECTION_RADIUS.toFloat(), Constants.MAX_SELECTION_RADIUS.toFloat())
            .toDouble()
    }

    private fun findMergeTarget(location: Location, stack: ItemStack): ItemDisplay? {
        return location.world.getNearbyEntities(
            location,
            Constants.MERGE_SEARCH_RADIUS,
            Constants.MERGE_SEARCH_RADIUS,
            Constants.MERGE_SEARCH_RADIUS,
        ).asSequence()
            .filterIsInstance<ItemDisplay>()
            .filter(persistence::isGroundItemDisplay)
            .filter { persistence.getGroundItemState(it) == GroundItemState.NORMAL }
            .filter { lockService.getLockMode(it) == LockService.LockMode.NONE }
            .filter { it.itemStack.isSimilar(stack) }
            .filter { it.itemStack.amount < it.itemStack.maxStackSize.coerceAtLeast(1) }
            .minByOrNull { it.location.distanceSquared(location) }
    }

    fun rescaleTransformation(existing: Transformation, stackScale: Float): Transformation {
        return Transformation(
            Vector3f(existing.translation),
            Quaternionf(existing.leftRotation),
            Vector3f(stackScale, stackScale, stackScale),
            Quaternionf(existing.rightRotation),
        )
    }

    fun displayScale(display: ItemDisplay): Float {
        return display.transformation.scale.x
    }

    fun deriveFace(display: ItemDisplay): BlockFace {
        val direction = Vector3d(0.0, 0.0, 1.0)
        display.transformation.leftRotation.transform(direction)

        return BlockFace.entries
            .filter { it.modX != 0 || it.modY != 0 || it.modZ != 0 }
            .maxBy {
                (it.modX.toDouble() * direction.x) +
                    (it.modY.toDouble() * direction.y) +
                    (it.modZ.toDouble() * direction.z)
            }
    }

    fun surfaceForFace(face: BlockFace): PlacementSurface {
        return when (face) {
            BlockFace.UP -> PlacementSurface.GROUND
            BlockFace.DOWN -> PlacementSurface.CEILING
            else -> PlacementSurface.WALL
        }
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

    fun scaleForStack(stack: ItemStack): Float {
        val scaleRange = resolveGroundItemScaleRange(stack)
        return scaleForStackAmount(stack.amount, stack.maxStackSize, scaleRange.min, scaleRange.max)
    }

    fun minimumScaleForStack(stack: ItemStack): Float {
        return resolveGroundItemScaleRange(stack).min
    }

    internal fun scaleForStackAmount(amount: Int, maxStackSize: Int, minScale: Float, maxScale: Float): Float {
        val resolvedMaxStackSize = maxStackSize.coerceAtLeast(1)
        if (resolvedMaxStackSize == 1) return minScale

        val resolvedAmount = amount.coerceIn(1, resolvedMaxStackSize)
        val progress = (resolvedAmount - 1).toFloat() / (resolvedMaxStackSize - 1).toFloat()
        return minScale + (progress * (maxScale - minScale))
    }

    private fun resolveGroundItemScaleRange(stack: ItemStack): ScaleRange {
        val minScale = readScaleOverride(stack, groundItemMinScaleKey, Constants.DEFAULT_MIN_SCALE)
        val maxScale = readScaleOverride(stack, groundItemMaxScaleKey, Constants.DEFAULT_MAX_SCALE)

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

    internal data class Placement(
        val location: Location,
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

package com.danidipp.sneakygrounditems.interaction

import com.danidipp.sneakygrounditems.config.Constants
import com.danidipp.sneakygrounditems.display.PlacementService
import com.danidipp.sneakygrounditems.lock.LockService
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import com.danidipp.sneakygrounditems.runtime.GroundItemRuntimeStateService
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent

internal class GroundItemLookupService(
    private val persistence: PersistenceService,
    private val lockService: LockService,
    private val placementService: PlacementService,
    private val runtimeState: GroundItemRuntimeStateService,
) {
    fun findLookedAtGroundItem(player: Player): ItemDisplay? {
        return findLookedAtGroundItem(player, Constants.INTERACTION_DISTANCE)
    }

    fun findLookedAtGroundItem(player: Player, distance: Double): ItemDisplay? {
        val hitPosition = findLookedAtBlockHitPosition(player, distance) ?: return null
        return findGroundItemNearHitPosition(player, hitPosition)
    }

    fun findInteractedGroundItem(event: PlayerInteractEvent): ItemDisplay? {
        val hitPosition = event.interactionPoint ?: findLookedAtBlockHitPosition(event.player, Constants.INTERACTION_DISTANCE) ?: return null
        return findGroundItemNearHitPosition(event.player, hitPosition)
    }

    fun findPopLocation(player: Player): Location? {
        val result = player.world.rayTraceBlocks(
            player.eyeLocation,
            player.eyeLocation.direction,
            Constants.POP_RAYCAST_DISTANCE,
            FluidCollisionMode.NEVER,
            true,
        ) ?: return null

        return result.hitPosition?.toLocation(player.world)
    }

    fun collectPopTargets(center: Location, radius: Double): List<ItemDisplay> {
        val world = center.world ?: return emptyList()
        val radiusSquared = radius * radius

        return world.getNearbyEntities(center, radius, radius, radius).asSequence()
            .filterIsInstance<ItemDisplay>()
            .filter(persistence::isGroundItemDisplay)
            .filter { !runtimeState.isResolutionPending(it.uniqueId) }
            .filter { persistence.getGroundItemState(it) == GroundItemState.NORMAL }
            .filter { lockService.getLockMode(it) == LockService.LockMode.NONE }
            .filter { it.location.world == world }
            .filter { it.location.distanceSquared(center) <= radiusSquared }
            .toList()
    }

    private fun findLookedAtBlockHitPosition(player: Player, distance: Double): Location? {
        val result = player.world.rayTraceBlocks(
            player.eyeLocation,
            player.eyeLocation.direction,
            distance,
            FluidCollisionMode.NEVER,
            true,
        ) ?: return null

        return result.hitPosition?.toLocation(player.world)
    }

    private fun findGroundItemNearHitPosition(player: Player, hitPosition: Location): ItemDisplay? {
        val world = hitPosition.world ?: return null
        val eyeLocation = player.eyeLocation

        return world.getNearbyEntities(
            hitPosition,
            Constants.MAX_SELECTION_RADIUS,
            Constants.MAX_SELECTION_RADIUS,
            Constants.MAX_SELECTION_RADIUS,
        ).asSequence()
            .filterIsInstance<ItemDisplay>()
            .filter(persistence::isGroundItemDisplay)
            .filter { !runtimeState.isResolutionPending(it.uniqueId) }
            .mapNotNull { display ->
                val hitDistanceSquared = display.location.distanceSquared(hitPosition)
                val selectionRadius = placementService.selectionRadiusForDisplay(display)
                if (hitDistanceSquared > selectionRadius * selectionRadius) {
                    return@mapNotNull null
                }

                DisplayCandidate(
                    display = display,
                    hitDistanceSquared = hitDistanceSquared,
                    eyeDistanceSquared = display.location.distanceSquared(eyeLocation),
                )
            }
            .minWithOrNull(
                compareBy<DisplayCandidate>(
                    { it.hitDistanceSquared },
                    { it.eyeDistanceSquared },
                    { it.display.uniqueId.toString() },
                ),
            )
            ?.display
    }

    private data class DisplayCandidate(
        val display: ItemDisplay,
        val hitDistanceSquared: Double,
        val eyeDistanceSquared: Double,
    )
}

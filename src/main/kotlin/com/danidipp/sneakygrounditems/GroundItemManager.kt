package com.danidipp.sneakygrounditems

import io.papermc.paper.event.player.PlayerPickItemEvent
import net.coreprotect.CoreProtect
import net.coreprotect.CoreProtectAPI
import net.coreprotect.listener.entity.EntityPickupItemListener
import net.coreprotect.listener.player.PlayerDropItemListener
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Effect
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

internal class GroundItemManager(
    private val plugin: SneakyGroundItems,
    private val persistence: GroundItemPersistence,
    private val lockService: GroundItemLockService,
) {
    private val placementService = GroundItemPlacementService(persistence, lockService)
    private val soulboundOwnerKey = NamespacedKey.fromString("magicspells:soulbound_owner")
        ?: throw IllegalStateException("Failed to create NamespacedKey for magicspells:soulbound_owner")
    private val pendingPickupPairIds = mutableSetOf<String>()
    private val playerStateById = mutableMapOf<UUID, PlayerRuntimeState>()
    private val coreProtectApi: CoreProtectAPI? by lazy {
        val coreProtectPlugin = Bukkit.getPluginManager().getPlugin("CoreProtect") as? CoreProtect ?: return@lazy null
        coreProtectPlugin.api?.takeIf { it.isEnabled }
    }

    fun processLoadedChunks() {
        for (world in Bukkit.getWorlds()) {
            for (chunk in world.loadedChunks) {
                processChunk(chunk)
            }
        }
    }

    fun handlePlayerDropItem(event: PlayerDropItemEvent) {
        if (event.isCancelled) return
        if (isMagicSpellsDropEvent()) return

        val world = event.player.world
        val eyeLocation = event.player.eyeLocation
        val forbiddenHit = world.rayTraceBlocks(
            eyeLocation,
            eyeLocation.direction,
            GroundItemConstants.INTERACTION_DISTANCE,
            FluidCollisionMode.ALWAYS,
            false,
        )
        if (isVanillaThrowBlock(forbiddenHit?.hitBlock)) return

        val result = world.rayTraceBlocks(
            eyeLocation,
            eyeLocation.direction,
            GroundItemConstants.INTERACTION_DISTANCE,
            FluidCollisionMode.NEVER,
            true,
        ) ?: return

        val hitPosition = result.hitPosition ?: return
        val hitFace = result.hitBlockFace ?: return
        val droppedStack = event.itemDrop.itemStack.clone()
        val placement = placementService.buildPlacement(event.player.location.yaw, hitPosition.toLocation(world), hitFace, droppedStack)

        val mergeResult = placementService.mergeIntoNearbyDisplay(placement.location, droppedStack)
        if (mergeResult != null) {
            finishMerge(event, droppedStack, mergeResult)
            return
        }

        if (!canCreateGroundItem(event.player.uniqueId)) {
            event.isCancelled = true
            return
        }

        if (!placementService.spawnPair(placement, droppedStack)) return

        markGroundItemCreated(event.player.uniqueId)
        playFeedback(event.player.uniqueId, placement.location, "lom:sfx.clothingequip")
        logDrop(event.player, placement.location, droppedStack)
        event.itemDrop.remove()
    }

    fun handleEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        val interaction = event.entity as? Interaction ?: return
        if (!persistence.isInteraction(interaction)) return
        event.isCancelled = true

        val target = persistence.resolveTarget(interaction) ?: run {
            interaction.remove()
            return
        }
        if (lockService.getLockMode(target) != LockMode.NONE) {
            player.playEffect(target.display.location, Effect.SMOKE, BlockFace.UP)
            return
        }
        if (!isPickupAllowed(player.uniqueId, target.display.itemStack)) return
        if (!pendingPickupPairIds.add(target.pairId)) return

        target.interaction.isResponsive = false

        val pairId = target.pairId
        val playerUuid = player.uniqueId
        val displayUuid = target.display.uniqueId
        val interactionUuid = target.interaction.uniqueId

        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                val display = persistence.resolveDisplay(displayUuid)
                val targetInteraction = persistence.resolveInteraction(interactionUuid)

                if (display == null || targetInteraction == null) {
                    display?.remove()
                    targetInteraction?.remove()
                    return@Runnable
                }

                val stack = display.itemStack.clone()
                val pickupLocation = display.location.clone()
                persistence.removePair(display, targetInteraction)

                val onlinePlayer = Bukkit.getPlayer(playerUuid)
                if (onlinePlayer == null || !onlinePlayer.isOnline) {
                    pickupLocation.world.dropItemNaturally(pickupLocation, stack)
                    return@Runnable
                }

                val overflow = giveCollectedItem(onlinePlayer, stack)
                for (item in overflow) {
                    onlinePlayer.world.dropItemNaturally(onlinePlayer.location, item)
                }

                playFeedback(onlinePlayer.uniqueId, pickupLocation, "lom:sfx.clothingunequip")
                logPickup(onlinePlayer, pickupLocation, stack)
            } finally {
                pendingPickupPairIds.remove(pairId)
            }
        })
    }

    fun handlePlayerQuit(player: Player) {
        playerStateById.remove(player.uniqueId)
    }

    fun handleChunkLoad(chunk: Chunk) {
        processChunk(chunk)
    }

    fun handlePlayerPickItem(event: PlayerPickItemEvent) {
        if (event.player.gameMode != GameMode.CREATIVE) return

        val target = findPreciselyLookedAtGroundItem(event.player) ?: return
        event.isCancelled = true
        event.player.inventory.setItem(event.targetSlot, target.display.itemStack.clone())
        event.player.inventory.heldItemSlot = event.targetSlot
    }

    fun findLookedAtInteraction(player: Player): Interaction? {
        val result = player.world.rayTraceEntities(
            player.eyeLocation,
            player.eyeLocation.direction,
            GroundItemConstants.INTERACTION_DISTANCE,
            GroundItemConstants.RAYTRACE_SIZE,
        ) { entity ->
            entity is Interaction &&
                persistence.isInteraction(entity) &&
                !isPickupPending(persistence.getPairId(entity))
        } ?: return null

        return result.hitEntity as? Interaction
    }

    fun findPopLocation(player: Player): Location? {
        val result = player.world.rayTraceBlocks(
            player.eyeLocation,
            player.eyeLocation.direction,
            GroundItemConstants.POP_RAYCAST_DISTANCE,
            FluidCollisionMode.NEVER,
            true,
        ) ?: return null

        return result.hitPosition?.toLocation(player.world)
    }

    fun popGroundItems(center: Location, radius: Double): Int {
        val targets = collectPopTargets(center, radius)
        for (target in targets) {
            popGroundItem(target)
        }
        return targets.size
    }

    fun resolveGroundItemTarget(entity: Entity): GroundItemTarget? {
        return persistence.resolveTarget(entity)
    }

    private fun processChunk(chunk: Chunk) {
        val processedPairs = mutableSetOf<String>()

        for (entity in chunk.entities) {
            if (!persistence.isGroundItem(entity)) continue

            val pairId = persistence.getPairId(entity)
            if (pairId != null && !processedPairs.add(pairId)) continue

            val target = persistence.resolveTarget(entity)
            if (target == null) {
                entity.remove()
                continue
            }

            lockService.clearExpiredTemporaryLock(target)
            placementService.syncInteractionToDisplay(target.display)
        }
    }

    private fun collectPopTargets(center: Location, radius: Double): List<GroundItemTarget> {
        val world = center.world ?: return emptyList()
        val radiusSquared = radius * radius
        val processedPairs = mutableSetOf<String>()
        val targets = mutableListOf<GroundItemTarget>()

        for (entity in world.getNearbyEntities(center, radius, radius, radius)) {
            if (!persistence.isGroundItem(entity)) continue

            val pairId = persistence.getPairId(entity)
            if (pairId != null && !processedPairs.add(pairId)) continue

            val target = persistence.resolveTarget(entity)
            if (target == null) {
                entity.remove()
                continue
            }
            if (isPickupPending(target.pairId)) continue
            if (lockService.getLockMode(target) != LockMode.NONE) continue
            if (target.display.location.world != world) continue
            if (target.display.location.distanceSquared(center) > radiusSquared) continue

            targets.add(target)
        }

        return targets
    }

    private fun popGroundItem(target: GroundItemTarget) {
        val stack = target.display.itemStack.clone()
        val dropLocation = target.display.location.clone()
        persistence.removePair(target)
        dropLocation.world.dropItem(dropLocation, stack)
    }

    private fun finishMerge(
        event: PlayerDropItemEvent,
        droppedStack: ItemStack,
        mergeResult: GroundItemPlacementService.MergeResult,
    ) {
        playFeedback(event.player.uniqueId, mergeResult.display.location, "lom:sfx.clothingequip")
        logDrop(
            event.player,
            mergeResult.display.location,
            droppedStack.clone().apply { amount = mergeResult.mergedAmount },
        )

        if (mergeResult.remainder > 0) {
            val remainderStack = droppedStack.clone().apply { amount = mergeResult.remainder }
            val overflow = event.player.inventory.addItem(remainderStack).values
            for (item in overflow) {
                event.player.world.dropItemNaturally(event.player.location, item)
            }
        }

        event.itemDrop.remove()
    }

    private fun canCreateGroundItem(playerId: UUID): Boolean {
        val state = playerStateById[playerId] ?: return true
        return System.currentTimeMillis() - state.lastCreatedAt >= GroundItemConstants.ACTION_COOLDOWN_MS
    }

    private fun markGroundItemCreated(playerId: UUID) {
        val state = playerStateById.getOrPut(playerId, ::PlayerRuntimeState)
        state.lastCreatedAt = System.currentTimeMillis()
    }

    private fun findPreciselyLookedAtGroundItem(player: Player): GroundItemTarget? {
        val eyeLocation = player.eyeLocation
        val entityResult = player.world.rayTraceEntities(
            eyeLocation,
            eyeLocation.direction,
            GroundItemConstants.INTERACTION_DISTANCE,
            0.0,
        ) { entity ->
            entity is Interaction &&
                persistence.isInteraction(entity) &&
                !isPickupPending(persistence.getPairId(entity))
        } ?: return null

        val hitPosition = entityResult.hitPosition ?: return null
        val hitInteraction = entityResult.hitEntity as? Interaction ?: return null
        val blockResult = player.world.rayTraceBlocks(
            eyeLocation,
            eyeLocation.direction,
            GroundItemConstants.INTERACTION_DISTANCE,
            FluidCollisionMode.NEVER,
            true,
        )

        val eyeVector = eyeLocation.toVector()
        val entityDistanceSquared = hitPosition.distanceSquared(eyeVector)
        val blockDistanceSquared = blockResult?.hitPosition?.distanceSquared(eyeVector)
        if (blockDistanceSquared != null && blockDistanceSquared <= entityDistanceSquared) return null

        return persistence.resolveTarget(hitInteraction)
    }

    private fun isPickupPending(pairId: String?): Boolean {
        return pairId != null && pairId in pendingPickupPairIds
    }

    private fun isPickupAllowed(playerUuid: UUID, stack: ItemStack): Boolean {
        return !isSoulboundToAnotherPlayer(playerUuid, stack)
    }

    private fun giveCollectedItem(player: Player, stack: ItemStack): Collection<ItemStack> {
        if (player.inventory.itemInMainHand.type.isAir) {
            player.inventory.setItem(player.inventory.heldItemSlot, stack)
            return emptyList()
        }

        return player.inventory.addItem(stack).values
    }

    private fun isSoulboundToAnotherPlayer(playerUuid: UUID, stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return false
        val owner = meta.persistentDataContainer.get(soulboundOwnerKey, PersistentDataType.STRING) ?: return false
        val ownerUuid = runCatching { UUID.fromString(owner) }.getOrNull() ?: return true
        return ownerUuid != playerUuid
    }

    private fun playFeedback(playerId: UUID, location: Location, sound: String) {
        location.world.spawnParticle(Particle.WHITE_SMOKE, location, 1, 0.0, 0.0, 0.0, 0.0)

        val now = System.currentTimeMillis()
        val state = playerStateById.getOrPut(playerId, ::PlayerRuntimeState)
        if (now - state.lastFeedbackAt < GroundItemConstants.ACTION_COOLDOWN_MS) return

        state.lastFeedbackAt = now
        location.world.playSound(location, sound, SoundCategory.MASTER, 0.25f, 1.75f)
    }

    private fun logDrop(player: Player, location: Location, stack: ItemStack) {
        if (coreProtectApi == null) return
        PlayerDropItemListener.playerDropItem(location, player.name, stack)
    }

    private fun logPickup(player: Player, location: Location, stack: ItemStack) {
        if (coreProtectApi == null) return
        EntityPickupItemListener.onItemPickup(player, location, stack)
    }

    private fun isMagicSpellsDropEvent(): Boolean {
        return Thread.currentThread().stackTrace.any { element ->
            element.className.startsWith("com.nisovin.magicspells")
        }
    }

    private fun isVanillaThrowBlock(block: Block?): Boolean {
        val type = block?.type ?: return false
        return type == Material.FIRE ||
            type == Material.SOUL_FIRE ||
            type == Material.WATER ||
            type == Material.LAVA ||
            type.name.endsWith("CAULDRON")
    }

    private data class PlayerRuntimeState(
        var lastCreatedAt: Long = 0L,
        var lastFeedbackAt: Long = 0L,
    )
}

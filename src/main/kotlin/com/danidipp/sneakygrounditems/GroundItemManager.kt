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
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.Event.Result
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

internal class GroundItemManager(
    private val plugin: SneakyGroundItems,
    private val persistence: PersistenceService,
    private val lockService: LockService,
) {
    private val placementService = PlacementService(persistence, lockService)
    private val soulboundOwnerKey = NamespacedKey.fromString("magicspells:soulbound_owner")
        ?: throw IllegalStateException("Failed to create NamespacedKey for magicspells:soulbound_owner")
    private val pendingPickupDisplayIds = mutableSetOf<UUID>()
    private val playerStateById = mutableMapOf<UUID, PlayerRuntimeState>()
    private val coreProtectApi: CoreProtectAPI? by lazy {
        val coreProtectPlugin = Bukkit.getPluginManager().getPlugin("CoreProtect") as? CoreProtect ?: return@lazy null
        coreProtectPlugin.api?.takeIf { it.isEnabled }
    }
    private var activeMigration: MigrationState? = null

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
            Constants.INTERACTION_DISTANCE,
            FluidCollisionMode.ALWAYS,
            false,
        )
        if (isVanillaThrowBlock(forbiddenHit?.hitBlock)) return

        val result = world.rayTraceBlocks(
            eyeLocation,
            eyeLocation.direction,
            Constants.INTERACTION_DISTANCE,
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

        if (!placementService.spawnDisplay(placement, droppedStack)) return

        markGroundItemCreated(event.player.uniqueId)
        playFeedback(event.player.uniqueId, placement.location, "lom:sfx.clothingequip")
        logDrop(event.player, placement.location, droppedStack)
        event.itemDrop.remove()
    }

    fun handlePlayerInteract(event: PlayerInteractEvent) {
        if (event.useInteractedBlock() == Result.DENY || event.useItemInHand() == Result.DENY) return
        if (event.action != Action.LEFT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val hitPosition = clickedHitPosition(event) ?: return
        val display = findGroundItemNearHitPosition(player, hitPosition) ?: return

        event.setUseInteractedBlock(Result.DENY)
        event.setUseItemInHand(Result.DENY)
        event.setCancelled(true)

        if (lockService.getLockMode(display) != LockService.LockMode.NONE) {
            player.playEffect(display.location, Effect.SMOKE, BlockFace.UP)
            return
        }
        if (!isPickupAllowed(player.uniqueId, display.itemStack)) return
        pickupDisplay(player, display)
    }

    fun handlePlayerQuit(player: Player) {
        playerStateById.remove(player.uniqueId)
    }

    fun handleChunkLoad(chunk: Chunk) {
        processChunk(chunk)
    }

    fun handlePlayerPickItem(event: PlayerPickItemEvent) {
        if (event.player.gameMode != GameMode.CREATIVE) return

        val display = findLookedAtGroundItem(event.player) ?: return
        event.isCancelled = true
        event.player.inventory.setItem(event.targetSlot, display.itemStack.clone())
        event.player.inventory.heldItemSlot = event.targetSlot
    }

    fun findLookedAtGroundItem(player: Player): ItemDisplay? {
        val hitPosition = findLookedAtBlockHitPosition(player) ?: return null
        return findGroundItemNearHitPosition(player, hitPosition)
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

    fun popGroundItems(center: Location, radius: Double): Int {
        val targets = collectPopTargets(center, radius)
        for (target in targets) {
            popGroundItem(target)
        }
        return targets.size
    }

    fun startMigration(player: Player, dryRun: Boolean = false): Boolean {
        if (activeMigration != null) {
            player.sendMessage("A ground item migration is already running")
            return false
        }

        val minChunk = Math.floorDiv(Constants.MIGRATION_MIN_BLOCK, 16)
        val maxChunk = Math.floorDiv(Constants.MIGRATION_MAX_BLOCK, 16)
        val chunkWidth = maxChunk - minChunk + 1
        val totalChunkCount = chunkWidth * chunkWidth
        val migration = MigrationState(
            senderUuid = player.uniqueId,
            worldUuid = player.world.uid,
            dryRun = dryRun,
            minChunk = minChunk,
            maxChunk = maxChunk,
            width = chunkWidth,
            totalChunkCount = totalChunkCount,
            startedAt = System.currentTimeMillis(),
        )

        activeMigration = migration
        val modeLabel = if (dryRun) "dry migration" else "ground item migration"
        player.sendMessage("Starting $modeLabel for chunk range $minChunk..$maxChunk in ${player.world.name}")
        Bukkit.getScheduler().runTask(plugin, Runnable { runMigrationBatch(migration) })
        return true
    }

    private fun runMigrationBatch(migration: MigrationState) {
        if (activeMigration !== migration) return

        val world = Bukkit.getWorld(migration.worldUuid)
        if (world == null) {
            finishMigration(migration, "Migration aborted because the world is no longer loaded")
            return
        }

        repeat(Constants.MIGRATION_BATCH_SIZE) {
            val chunkCoordinates = migration.nextChunkCoordinates() ?: return@repeat
            migrateChunk(migration, world, chunkCoordinates.first, chunkCoordinates.second)
        }

        maybeReportMigrationProgress(migration)

        if (migration.isFinished) {
            finishMigration(migration, null)
            return
        }

        Bukkit.getScheduler().runTask(plugin, Runnable { runMigrationBatch(migration) })
    }

    private fun maybeReportMigrationProgress(migration: MigrationState) {
        if (migration.visitedChunks == 0) return
        if (migration.visitedChunks % Constants.MIGRATION_PROGRESS_INTERVAL != 0) return

        val sender = Bukkit.getPlayer(migration.senderUuid) ?: return
        val modeLabel = if (migration.dryRun) "Dry migration" else "Migration"
        sender.sendMessage(
            "$modeLabel progress: ${migration.visitedChunks}/${migration.totalChunkCount} candidate chunks checked, " +
                "${migration.processedChunks} chunks processed",
        )
    }

    private fun finishMigration(migration: MigrationState, errorMessage: String?) {
        if (activeMigration !== migration) return
        activeMigration = null

        val sender = Bukkit.getPlayer(migration.senderUuid)
        if (errorMessage != null) {
            sender?.sendMessage(errorMessage)
            return
        }

        migration.finalizeInteractionCounts()
        val elapsedMs = System.currentTimeMillis() - migration.startedAt
        val modeLabel = if (migration.dryRun) "Dry migration finished" else "Migration finished"
        val displayLabel = if (migration.dryRun) "displays needing migration" else "displays migrated"
        val interactionLabel = if (migration.dryRun) "interactions that would be removed" else "interactions removed"
        val orphanInteractionLabel = if (migration.dryRun) {
            "orphan interactions that would be removed"
        } else {
            "orphan interactions removed"
        }
        sender?.sendMessage(
            "$modeLabel: ${migration.processedChunks} chunks processed, " +
                "${migration.displaysMigrated} $displayLabel, " +
                "${migration.interactionsRemoved} $interactionLabel, " +
                "${migration.orphanInteractionsRemoved} $orphanInteractionLabel, " +
                "${elapsedMs}ms elapsed",
        )
    }

    private fun migrateChunk(migration: MigrationState, world: World, chunkX: Int, chunkZ: Int) {
        if (!world.isChunkGenerated(chunkX, chunkZ)) return

        val wasLoaded = world.isChunkLoaded(chunkX, chunkZ)
        if (!world.loadChunk(chunkX, chunkZ, false)) return

        val chunk = world.getChunkAt(chunkX, chunkZ, false)
        migration.processedChunks++

        for (entity in chunk.entities) {
            when {
                entity is ItemDisplay && persistence.isGroundItemDisplay(entity) -> {
                    migration.registerDisplay(entity.uniqueId)
                    val changed = if (migration.dryRun) {
                        persistence.needsLegacyDisplayCleanup(entity)
                    } else {
                        persistence.cleanupLegacyDisplay(entity)
                    }
                    if (changed) {
                        migration.displaysMigrated++
                    }
                }

                entity is Interaction && persistence.isLegacyInteraction(entity) -> {
                    val partnerUuid = persistence.getLegacyPartnerUuid(entity)
                    if (!migration.dryRun) {
                        entity.remove()
                    }
                    migration.registerInteractionPartner(partnerUuid)
                }
            }
        }

        if (!wasLoaded) {
            world.unloadChunk(chunkX, chunkZ, true)
        }
    }

    private fun processChunk(chunk: Chunk) {
        for (entity in chunk.entities) {
            if (entity !is ItemDisplay) continue
            if (!persistence.isGroundItemDisplay(entity)) continue

            lockService.clearExpiredTemporaryLock(entity)
        }
    }

    private fun collectPopTargets(center: Location, radius: Double): List<ItemDisplay> {
        val world = center.world ?: return emptyList()
        val radiusSquared = radius * radius

        return world.getNearbyEntities(center, radius, radius, radius).asSequence()
            .filterIsInstance<ItemDisplay>()
            .filter(persistence::isGroundItemDisplay)
            .filter { !isPickupPending(it.uniqueId) }
            .filter { lockService.getLockMode(it) == LockService.LockMode.NONE }
            .filter { it.location.world == world }
            .filter { it.location.distanceSquared(center) <= radiusSquared }
            .toList()
    }

    private fun popGroundItem(display: ItemDisplay) {
        val stack = display.itemStack.clone()
        val dropLocation = display.location.clone()
        persistence.removeDisplay(display)
        dropLocation.world.dropItem(dropLocation, stack)
    }

    private fun finishMerge(
        event: PlayerDropItemEvent,
        droppedStack: ItemStack,
        mergeResult: PlacementService.MergeResult,
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
        return System.currentTimeMillis() - state.lastCreatedAt >= Constants.ACTION_COOLDOWN_MS
    }

    private fun markGroundItemCreated(playerId: UUID) {
        val state = playerStateById.getOrPut(playerId, ::PlayerRuntimeState)
        state.lastCreatedAt = System.currentTimeMillis()
    }

    private fun clickedHitPosition(event: PlayerInteractEvent): Location? {
        return event.interactionPoint ?: findLookedAtBlockHitPosition(event.player)
    }

    private fun findLookedAtBlockHitPosition(player: Player): Location? {
        val result = player.world.rayTraceBlocks(
            player.eyeLocation,
            player.eyeLocation.direction,
            Constants.INTERACTION_DISTANCE,
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
            .filter { !isPickupPending(it.uniqueId) }
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

    private fun pickupDisplay(player: Player, display: ItemDisplay) {
        val displayUuid = display.uniqueId
        if (!pendingPickupDisplayIds.add(displayUuid)) return

        val playerUuid = player.uniqueId

        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                val display = persistence.resolveDisplay(displayUuid)
                if (display == null || !persistence.isGroundItemDisplay(display)) {
                    return@Runnable
                }

                val stack = display.itemStack.clone()
                val pickupLocation = display.location.clone()
                persistence.removeDisplay(display)

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
                pendingPickupDisplayIds.remove(displayUuid)
            }
        })
    }

    private fun isPickupPending(displayUuid: UUID): Boolean {
        return displayUuid in pendingPickupDisplayIds
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
        if (now - state.lastFeedbackAt < Constants.ACTION_COOLDOWN_MS) return

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

    private data class DisplayCandidate(
        val display: ItemDisplay,
        val hitDistanceSquared: Double,
        val eyeDistanceSquared: Double,
    )

    private data class MigrationState(
        val senderUuid: UUID,
        val worldUuid: UUID,
        val dryRun: Boolean,
        val minChunk: Int,
        val maxChunk: Int,
        val width: Int,
        val totalChunkCount: Int,
        val startedAt: Long,
        var nextChunkIndex: Int = 0,
        var visitedChunks: Int = 0,
        var processedChunks: Int = 0,
        var displaysMigrated: Int = 0,
        var interactionsRemoved: Int = 0,
        var orphanInteractionsRemoved: Int = 0,
    ) {
        private val migratedDisplayIds = mutableSetOf<UUID>()
        private val unresolvedInteractionPartners = mutableMapOf<UUID, Int>()

        val isFinished: Boolean
            get() = nextChunkIndex >= totalChunkCount

        fun nextChunkCoordinates(): Pair<Int, Int>? {
            if (nextChunkIndex >= totalChunkCount) return null

            val index = nextChunkIndex++
            visitedChunks++
            val x = minChunk + (index % width)
            val z = minChunk + (index / width)
            if (z > maxChunk) return null
            return x to z
        }

        fun registerDisplay(displayUuid: UUID) {
            if (!migratedDisplayIds.add(displayUuid)) return
            interactionsRemoved += unresolvedInteractionPartners.remove(displayUuid) ?: 0
        }

        fun registerInteractionPartner(partnerUuid: UUID?) {
            if (partnerUuid == null) {
                orphanInteractionsRemoved++
                return
            }

            if (partnerUuid in migratedDisplayIds) {
                interactionsRemoved++
                return
            }

            unresolvedInteractionPartners.merge(partnerUuid, 1, Int::plus)
        }

        fun finalizeInteractionCounts() {
            orphanInteractionsRemoved += unresolvedInteractionPartners.values.sum()
            unresolvedInteractionPartners.clear()
        }
    }

    private data class PlayerRuntimeState(
        var lastCreatedAt: Long = 0L,
        var lastFeedbackAt: Long = 0L,
    )
}

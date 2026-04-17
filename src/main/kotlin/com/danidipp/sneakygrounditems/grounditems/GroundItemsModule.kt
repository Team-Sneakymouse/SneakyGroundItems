package com.danidipp.sneakygrounditems.grounditems

import com.danidipp.sneakygrounditems.SneakyGroundItems
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import io.papermc.paper.event.player.PlayerPickItemEvent
import net.coreprotect.CoreProtect
import net.coreprotect.CoreProtectAPI
import net.coreprotect.listener.entity.EntityPickupItemListener
import net.coreprotect.listener.player.PlayerDropItemListener
import net.kyori.adventure.text.Component
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
import org.bukkit.command.CommandSender
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataHolder
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

class GroundItemsModule(
    private val plugin: SneakyGroundItems,
) : Listener {
    companion object {
        private const val MAX_DISTANCE = 2.5
        private const val POP_RAYCAST_DISTANCE = 8.0
        private const val SURFACE_EPSILON = 0.02
        private const val SURFACE_RANDOM_OFFSET = 0.01
        private const val RAYTRACE_SIZE = 0.25
        private const val SOUND_COOLDOWN_MS = 500L
        private const val DEFAULT_GROUND_ITEM_MIN_SCALE = 0.5f
        private const val DEFAULT_GROUND_ITEM_MAX_SCALE = 2.0f

        private const val KIND_DISPLAY = "display"
        private const val KIND_INTERACTION = "interaction"
        private const val PERMANENT_LOCK_VALUE = "permanent"
        private const val GROUND_ITEM_TAG = "grounditem"

        const val ADMIN_PERMISSION = "sneakygrounditems.command.grounditem.admin"
        private const val LEGACY_ADMIN_PERMISSION = "sneakymisc.command.grounditem.admin"

        fun hasAdminPermission(sender: CommandSender): Boolean {
            return sender.hasPermission(ADMIN_PERMISSION) || sender.hasPermission(LEGACY_ADMIN_PERMISSION)
        }
    }

    enum class LockMode {
        NONE,
        TEMPORARY,
        PERMANENT,
    }

    data class GroundItemTarget(
        val display: ItemDisplay,
        val interaction: Interaction,
        val pairId: String,
    )

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
    private val soulboundOwnerKey = NamespacedKey.fromString("magicspells:soulbound_owner")
        ?: throw IllegalStateException("Failed to create NamespacedKey for magicspells:soulbound_owner")
    private val groundItemMinScaleKey = NamespacedKey.fromString("magicspells:magicspellpermanentdata_grounditem_min_scale")
        ?: throw IllegalStateException("Failed to create NamespacedKey for magicspells:magicspellpermanentdata_grounditem_min_scale")
    private val groundItemMaxScaleKey = NamespacedKey.fromString("magicspells:magicspellpermanentdata_grounditem_max_scale")
        ?: throw IllegalStateException("Failed to create NamespacedKey for magicspells:magicspellpermanentdata_grounditem_max_scale")
    private val lockZone = ZoneId.of("UTC-07:00")
    private val lastGroundItemCreationByPlayer = mutableMapOf<UUID, Long>()
    private val lastFeedbackSoundByPlayer = mutableMapOf<UUID, Long>()
    private val pendingPickupPairIds = mutableSetOf<String>()
    private val coreProtectApi: CoreProtectAPI? by lazy {
        val coreProtectPlugin = Bukkit.getPluginManager().getPlugin("CoreProtect") as? CoreProtect ?: return@lazy null
        coreProtectPlugin.api?.takeIf { it.isEnabled }
    }

    init {
        processLoadedChunks()
    }

    @EventHandler
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        if (event.isCancelled) return
        if (isMagicSpellsDropEvent()) return

        val world = event.player.world
        val eyeLocation = event.player.eyeLocation
        val forbiddenHit = world.rayTraceBlocks(
            eyeLocation,
            eyeLocation.direction,
            MAX_DISTANCE,
            FluidCollisionMode.ALWAYS,
            false
        )
        if (isVanillaThrowBlock(forbiddenHit?.hitBlock)) return

        val result = world.rayTraceBlocks(
            eyeLocation,
            eyeLocation.direction,
            MAX_DISTANCE,
            FluidCollisionMode.NEVER,
            true
        ) ?: return

        val hitPosition = result.hitPosition ?: return
        val hitFace = result.hitBlockFace ?: return
        val droppedStack = event.itemDrop.itemStack.clone()
        val placement = buildPlacement(event.player.location.yaw, hitPosition.toLocation(world), hitFace, droppedStack)

        if (tryMergeIntoNearbyDisplay(event, placement.location, droppedStack)) {
            return
        }

        if (!canCreateGroundItem(event.player.uniqueId)) {
            event.isCancelled = true
            return
        }

        if (!spawnPair(placement, droppedStack)) return

        markGroundItemCreated(event.player.uniqueId)
        playGroundItemFeedback(event.player.uniqueId, placement.location, "lom:sfx.clothingequip")
        logCoreProtectDrop(event.player, placement.location, droppedStack)
        event.itemDrop.remove()
    }

    @EventHandler
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val player = event.damager as? Player ?: return
        val interaction = event.entity as? Interaction ?: return
        if (!hasKind(interaction, KIND_INTERACTION)) return
        event.isCancelled = true

        val target = resolveGroundItemTarget(interaction) ?: run {
            interaction.remove()
            return
        }
        if (getLockMode(target.display) != LockMode.NONE) {
            player.playEffect(target.display.location, Effect.SMOKE, BlockFace.UP)
            return
        }
        if (!isPickupAllowed(player.uniqueId, target.display)) return
        if (!tryReservePickup(target.pairId)) return

        target.interaction.isResponsive = false

        val pairId = target.pairId
        val playerUuid = player.uniqueId
        val displayUuid = target.display.uniqueId
        val interactionUuid = target.interaction.uniqueId

        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                val display = resolveDisplay(displayUuid)
                val targetInteraction = resolveInteraction(interactionUuid)

                if (display == null || targetInteraction == null) {
                    display?.remove()
                    targetInteraction?.remove()
                    return@Runnable
                }

                val stack = display.itemStack.clone()
                val pickupLocation = display.location.clone()
                removePair(display, targetInteraction)

                val onlinePlayer = Bukkit.getPlayer(playerUuid)
                if (onlinePlayer == null || !onlinePlayer.isOnline) {
                    pickupLocation.world.dropItemNaturally(pickupLocation, stack)
                    return@Runnable
                }

                val overflow = giveCollectedItem(onlinePlayer, stack)
                for (item in overflow) {
                    onlinePlayer.world.dropItemNaturally(onlinePlayer.location, item)
                }

                playGroundItemFeedback(onlinePlayer.uniqueId, pickupLocation, "lom:sfx.clothingunequip")
                logCoreProtectPickup(onlinePlayer, pickupLocation, stack)
            } finally {
                releasePickupReservation(pairId)
            }
        })
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        lastGroundItemCreationByPlayer.remove(event.player.uniqueId)
        lastFeedbackSoundByPlayer.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        processChunk(event.chunk)
    }

    @EventHandler
    fun onPlayerPickItem(event: PlayerPickItemEvent) {
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
            MAX_DISTANCE,
            RAYTRACE_SIZE
        ) { entity ->
            entity is Interaction &&
                hasKind(entity, KIND_INTERACTION) &&
                !isPickupPending(getPairId(entity))
        } ?: return null

        return result.hitEntity as? Interaction
    }

    private fun findPreciselyLookedAtGroundItem(player: Player): GroundItemTarget? {
        val eyeLocation = player.eyeLocation
        val entityResult = player.world.rayTraceEntities(
            eyeLocation,
            eyeLocation.direction,
            MAX_DISTANCE,
            0.0
        ) { entity ->
            entity is Interaction &&
                hasKind(entity, KIND_INTERACTION) &&
                !isPickupPending(getPairId(entity))
        } ?: return null

        val hitPosition = entityResult.hitPosition ?: return null
        val hitInteraction = entityResult.hitEntity as? Interaction ?: return null
        val blockResult = player.world.rayTraceBlocks(
            eyeLocation,
            eyeLocation.direction,
            MAX_DISTANCE,
            FluidCollisionMode.NEVER,
            true
        )

        val eyeVector = eyeLocation.toVector()
        val entityDistanceSquared = hitPosition.distanceSquared(eyeVector)
        val blockDistanceSquared = blockResult?.hitPosition?.distanceSquared(eyeVector)
        if (blockDistanceSquared != null && blockDistanceSquared <= entityDistanceSquared) return null

        return resolveGroundItemTarget(hitInteraction)
    }

    fun findPopLocation(player: Player): Location? {
        val result = player.world.rayTraceBlocks(
            player.eyeLocation,
            player.eyeLocation.direction,
            POP_RAYCAST_DISTANCE,
            FluidCollisionMode.NEVER,
            true
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

    fun lockTarget(actor: CommandSender, target: GroundItemTarget): LockMode {
        return when (determineLockMode(actor, target.display.location)) {
            LockMode.PERMANENT -> {
                setPermanentLock(target)
                LockMode.PERMANENT
            }

            else -> {
                setTemporaryLock(target)
                LockMode.TEMPORARY
            }
        }
    }

    fun getLockMode(target: GroundItemTarget): LockMode = getLockMode(target.display)

    fun canUnlock(actor: CommandSender, target: GroundItemTarget): Boolean {
        if (hasAdminPermission(actor)) return true

        val player = actor as? Player ?: return false
        return isPlotMember(player, target.display.location)
    }

    fun unlockTarget(target: GroundItemTarget) {
        clearLock(target)
    }

    fun formatBoundMessage(stack: ItemStack, temporary: Boolean): Component {
        val suffix = if (temporary) {
            " is now bound in place for the rest of the day"
        } else {
            " is now bound in place"
        }
        return stack.displayName().append(Component.text(suffix))
    }

    fun formatUnboundMessage(stack: ItemStack): Component {
        return stack.displayName().append(Component.text(" is now unbound"))
    }

    fun formatAlreadyBoundMessage(stack: ItemStack): Component {
        return stack.displayName().append(Component.text(" is already bound in place"))
    }

    fun formatAlreadyUnboundMessage(stack: ItemStack): Component {
        return stack.displayName().append(Component.text(" is already unbound"))
    }

    private fun processLoadedChunks() {
        for (world in Bukkit.getWorlds()) {
            for (chunk in world.loadedChunks) {
                processChunk(chunk)
            }
        }
    }

    private fun processChunk(chunk: Chunk) {
        val processedPairs = mutableSetOf<String>()

        for (entity in chunk.entities) {
            if (!isTaggedGroundItem(entity)) continue

            val pairId = getPairId(entity)
            if (pairId != null && !processedPairs.add(pairId)) continue

            val target = resolveGroundItemTarget(entity)
            if (target == null) {
                entity.remove()
                continue
            }

            clearExpiredTemporaryLock(target)
        }
    }

    private fun spawnPair(placement: Placement, stack: ItemStack): Boolean {
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
                tagEntity(itemDisplay, KIND_DISPLAY, pairId, "")
            }

            interaction = placement.interactionLocation.world.spawn(placement.interactionLocation, Interaction::class.java) { target ->
                target.isPersistent = true
                target.setGravity(false)
                target.isResponsive = true
                target.interactionWidth = placement.interactionScale
                target.interactionHeight = placement.interactionScale
                tagEntity(target, KIND_INTERACTION, pairId, "")
            }

            val spawnedDisplay = display ?: return@runCatching false
            val spawnedInteraction = interaction ?: return@runCatching false

            setPartner(spawnedDisplay, spawnedInteraction.uniqueId)
            setPartner(spawnedInteraction, spawnedDisplay.uniqueId)
            setFace(spawnedDisplay, placement.face)
            setFace(spawnedInteraction, placement.face)
            true
        }.getOrElse {
            display?.remove()
            interaction?.remove()
            false
        }
    }

    private fun buildPlacement(playerYaw: Float, hitPosition: Location, hitFace: BlockFace, stack: ItemStack): Placement {
        val normal = hitFace.direction.clone().normalize()
        val stackScale = scaleForStack(stack)
        val randomOffset = ThreadLocalRandom.current().nextDouble(0.0, SURFACE_RANDOM_OFFSET)
        val anchor = hitPosition.clone()
            .add(normal.clone().multiply(SURFACE_EPSILON))
            .add(normal.clone().multiply(randomOffset))
        val interactionLocation = anchor.clone().add(normal.clone().multiply(interactionNormalOffset(stackScale).toDouble()))
        return Placement(
            location = anchor,
            interactionLocation = interactionLocation,
            interactionScale = stackScale,
            transformation = transformationForFace(hitFace, playerYaw, stackScale),
            face = hitFace
        )
    }

    private fun tryMergeIntoNearbyDisplay(
        event: PlayerDropItemEvent,
        placementLocation: Location,
        droppedStack: ItemStack,
    ): Boolean {
        val mergeTarget = findMergeTarget(placementLocation, droppedStack) ?: return false
        val currentStack = mergeTarget.itemStack.clone()
        val maxStackSize = currentStack.maxStackSize.coerceAtLeast(1)
        val spaceLeft = maxStackSize - currentStack.amount
        if (spaceLeft <= 0) return false

        val mergeAmount = minOf(spaceLeft, droppedStack.amount)
        currentStack.amount += mergeAmount
        val newScale = scaleForStack(currentStack)
        mergeTarget.setItemStack(currentStack)
        mergeTarget.transformation = rescaleTransformation(mergeTarget.transformation, newScale)
        updateInteractionForDisplayScale(mergeTarget, newScale)
        playGroundItemFeedback(event.player.uniqueId, mergeTarget.location, "lom:sfx.clothingequip")
        logCoreProtectDrop(event.player, mergeTarget.location, droppedStack.clone().apply { amount = mergeAmount })

        val remainder = droppedStack.amount - mergeAmount
        if (remainder > 0) {
            val remainderStack = droppedStack.clone().apply { amount = remainder }
            val overflow = event.player.inventory.addItem(remainderStack).values
            for (item in overflow) {
                event.player.world.dropItemNaturally(event.player.location, item)
            }
        }

        event.itemDrop.remove()
        return true
    }

    private fun findMergeTarget(location: Location, stack: ItemStack): ItemDisplay? {
        return location.world.getNearbyEntities(location, 0.25, 0.25, 0.25)
            .asSequence()
            .filterIsInstance<ItemDisplay>()
            .filter { hasKind(it, KIND_DISPLAY) }
            .filter { getLockMode(it) == LockMode.NONE }
            .filter { it.itemStack.isSimilar(stack) }
            .filter { it.itemStack.amount < it.itemStack.maxStackSize.coerceAtLeast(1) }
            .minByOrNull { it.location.distanceSquared(location) }
    }

    private fun collectPopTargets(center: Location, radius: Double): List<GroundItemTarget> {
        val world = center.world ?: return emptyList()
        val radiusSquared = radius * radius
        val processedPairs = mutableSetOf<String>()
        val targets = mutableListOf<GroundItemTarget>()

        for (entity in world.getNearbyEntities(center, radius, radius, radius)) {
            if (!isTaggedGroundItem(entity)) continue

            val pairId = getPairId(entity)
            if (pairId != null && !processedPairs.add(pairId)) continue

            val target = resolveGroundItemTarget(entity)
            if (target == null) {
                entity.remove()
                continue
            }
            if (isPickupPending(target.pairId)) continue
            if (getLockMode(target) != LockMode.NONE) continue
            if (target.display.location.world != world) continue
            if (target.display.location.distanceSquared(center) > radiusSquared) continue

            targets.add(target)
        }

        return targets
    }

    private fun popGroundItem(target: GroundItemTarget) {
        val stack = target.display.itemStack.clone()
        val dropLocation = target.display.location.clone()
        removePair(target.display, target.interaction)
        spawnVanillaDrop(dropLocation, stack)
    }

    private fun spawnVanillaDrop(location: Location, stack: ItemStack) {
        location.world.dropItem(location, stack)
    }

    private fun determineLockMode(actor: CommandSender, location: Location): LockMode {
        val player = actor as? Player
        if (player != null && isPlotMember(player, location)) {
            return LockMode.PERMANENT
        }
        if (hasAdminPermission(actor) && player?.gameMode == GameMode.CREATIVE) {
            return LockMode.PERMANENT
        }
        return LockMode.TEMPORARY
    }

    private fun isPlotMember(player: Player, location: Location): Boolean {
        val world = location.world ?: return false
        val worldGuardPlugin = Bukkit.getPluginManager().getPlugin("WorldGuard") as? WorldGuardPlugin ?: return false
        val regionManager = WorldGuard.getInstance().platform.regionContainer[BukkitAdapter.adapt(world)] ?: return false
        val applicableRegions = regionManager.getApplicableRegions(BlockVector3.at(location.x, location.y, location.z))
        val worldGuardPlayer = worldGuardPlugin.wrapPlayer(player)

        return applicableRegions.any { region ->
            region.priority == 5 && (region.isOwner(worldGuardPlayer) || region.isMember(worldGuardPlayer))
        }
    }

    private fun todayLockDate(): String = LocalDate.now(lockZone).toString()

    private fun getLockMode(display: ItemDisplay): LockMode {
        return when {
            getString(display, permanentLockModeKey, legacyPermanentLockModeKey) == PERMANENT_LOCK_VALUE -> LockMode.PERMANENT
            getString(display, temporaryLockDateKey, legacyTemporaryLockDateKey) != null -> LockMode.TEMPORARY
            else -> LockMode.NONE
        }
    }

    private fun clearExpiredTemporaryLock(target: GroundItemTarget) {
        val temporaryDate = getString(target.display, temporaryLockDateKey, legacyTemporaryLockDateKey) ?: return
        if (temporaryDate == todayLockDate()) return

        removeString(target.display, temporaryLockDateKey, legacyTemporaryLockDateKey)
        removeString(target.interaction, temporaryLockDateKey, legacyTemporaryLockDateKey)
    }

    private fun setPermanentLock(target: GroundItemTarget) {
        setString(target.display, permanentLockModeKey, legacyPermanentLockModeKey, PERMANENT_LOCK_VALUE)
        setString(target.interaction, permanentLockModeKey, legacyPermanentLockModeKey, PERMANENT_LOCK_VALUE)
        removeString(target.display, temporaryLockDateKey, legacyTemporaryLockDateKey)
        removeString(target.interaction, temporaryLockDateKey, legacyTemporaryLockDateKey)
    }

    private fun setTemporaryLock(target: GroundItemTarget) {
        val date = todayLockDate()
        removeString(target.display, permanentLockModeKey, legacyPermanentLockModeKey)
        removeString(target.interaction, permanentLockModeKey, legacyPermanentLockModeKey)
        setString(target.display, temporaryLockDateKey, legacyTemporaryLockDateKey, date)
        setString(target.interaction, temporaryLockDateKey, legacyTemporaryLockDateKey, date)
    }

    private fun clearLock(target: GroundItemTarget) {
        removeString(target.display, permanentLockModeKey, legacyPermanentLockModeKey)
        removeString(target.interaction, permanentLockModeKey, legacyPermanentLockModeKey)
        removeString(target.display, temporaryLockDateKey, legacyTemporaryLockDateKey)
        removeString(target.interaction, temporaryLockDateKey, legacyTemporaryLockDateKey)
    }

    private fun isPickupAllowed(playerUuid: UUID, display: ItemDisplay): Boolean {
        return !isSoulboundToAnotherPlayer(playerUuid, display.itemStack)
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

    private fun rescaleTransformation(existing: Transformation, stackScale: Float): Transformation {
        return Transformation(
            Vector3f(existing.translation),
            Quaternionf(existing.leftRotation),
            Vector3f(stackScale, stackScale, stackScale),
            Quaternionf(existing.rightRotation)
        )
    }

    private fun updateInteractionForDisplayScale(display: ItemDisplay, stackScale: Float) {
        val interaction = resolvePartner(display, KIND_INTERACTION) as? Interaction ?: return
        interaction.interactionWidth = stackScale
        interaction.interactionHeight = stackScale

        val face = getStoredFace(display) ?: return
        val normal = face.direction.clone().normalize()
        interaction.teleport(display.location.clone().add(normal.multiply(interactionNormalOffset(stackScale).toDouble())))
    }

    private fun canCreateGroundItem(playerId: UUID): Boolean {
        val now = System.currentTimeMillis()
        val lastCreatedAt = lastGroundItemCreationByPlayer[playerId] ?: 0L
        return now - lastCreatedAt >= SOUND_COOLDOWN_MS
    }

    private fun tryReservePickup(pairId: String): Boolean {
        return pendingPickupPairIds.add(pairId)
    }

    private fun releasePickupReservation(pairId: String) {
        pendingPickupPairIds.remove(pairId)
    }

    private fun isPickupPending(pairId: String?): Boolean {
        return pairId != null && pairId in pendingPickupPairIds
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

    private fun markGroundItemCreated(playerId: UUID) {
        lastGroundItemCreationByPlayer[playerId] = System.currentTimeMillis()
    }

    private fun playGroundItemFeedback(playerId: UUID, location: Location, sound: String) {
        location.world.spawnParticle(Particle.WHITE_SMOKE, location, 1, 0.0, 0.0, 0.0, 0.0)
        val now = System.currentTimeMillis()
        val lastPlayedAt = lastFeedbackSoundByPlayer[playerId] ?: 0L
        if (now - lastPlayedAt < SOUND_COOLDOWN_MS) return

        lastFeedbackSoundByPlayer[playerId] = now
        location.world.playSound(location, sound, SoundCategory.MASTER, 0.25f, 1.75f)
    }

    private fun logCoreProtectDrop(player: Player, location: Location, stack: ItemStack) {
        if (coreProtectApi == null) return
        PlayerDropItemListener.playerDropItem(location, player.name, stack)
    }

    private fun logCoreProtectPickup(player: Player, location: Location, stack: ItemStack) {
        if (coreProtectApi == null) return
        EntityPickupItemListener.onItemPickup(player, location, stack)
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
            Quaternionf()
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
        val minScale = readScaleOverride(stack, groundItemMinScaleKey, DEFAULT_GROUND_ITEM_MIN_SCALE)
        val maxScale = readScaleOverride(stack, groundItemMaxScaleKey, DEFAULT_GROUND_ITEM_MAX_SCALE)

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

    private fun tagEntity(entity: PersistentDataHolder, kind: String, pairId: String, partnerUuid: String) {
        setString(entity, kindKey, legacyKindKey, kind)
        setString(entity, pairIdKey, legacyPairIdKey, pairId)
        setString(entity, partnerUuidKey, legacyPartnerUuidKey, partnerUuid)
        (entity as? Entity)?.addScoreboardTag(GROUND_ITEM_TAG)
    }

    private fun setFace(entity: PersistentDataHolder, face: BlockFace) {
        setString(entity, faceKey, legacyFaceKey, face.name)
    }

    private fun getStoredFace(entity: PersistentDataHolder): BlockFace? {
        val faceName = getString(entity, faceKey, legacyFaceKey) ?: return null
        return runCatching { BlockFace.valueOf(faceName) }.getOrNull()
    }

    private fun getPairId(entity: Entity): String? {
        return getString(entity, pairIdKey, legacyPairIdKey)
    }

    private fun setPartner(entity: PersistentDataHolder, partnerUuid: UUID) {
        setString(entity, partnerUuidKey, legacyPartnerUuidKey, partnerUuid.toString())
    }

    private fun hasKind(entity: Entity, expectedKind: String): Boolean {
        return getString(entity, kindKey, legacyKindKey) == expectedKind
    }

    private fun isTaggedGroundItem(entity: Entity): Boolean {
        return hasString(entity, kindKey, legacyKindKey)
    }

    private fun resolvePartner(entity: Entity, expectedKind: String): Entity? {
        val partnerId = getString(entity, partnerUuidKey, legacyPartnerUuidKey) ?: return null
        val partnerUuid = runCatching { UUID.fromString(partnerId) }.getOrNull() ?: return null
        val partner = Bukkit.getEntity(partnerUuid) ?: return null
        return partner.takeIf { hasKind(it, expectedKind) }
    }

    private fun resolveDisplay(uuid: UUID): ItemDisplay? {
        return Bukkit.getEntity(uuid) as? ItemDisplay
    }

    private fun resolveInteraction(uuid: UUID): Interaction? {
        return Bukkit.getEntity(uuid) as? Interaction
    }

    private fun removePair(display: ItemDisplay, interaction: Interaction) {
        display.remove()
        interaction.remove()
    }

    private fun legacyKey(key: String): NamespacedKey {
        return NamespacedKey.fromString("sneakymisc:$key")
            ?: throw IllegalStateException("Failed to create legacy key sneakymisc:$key")
    }

    private fun getString(holder: PersistentDataHolder, key: NamespacedKey, legacyKey: NamespacedKey): String? {
        val data = holder.persistentDataContainer
        return data.get(key, PersistentDataType.STRING) ?: data.get(legacyKey, PersistentDataType.STRING)
    }

    private fun setString(holder: PersistentDataHolder, key: NamespacedKey, legacyKey: NamespacedKey, value: String) {
        val data = holder.persistentDataContainer
        data.set(key, PersistentDataType.STRING, value)
        data.set(legacyKey, PersistentDataType.STRING, value)
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

    private data class Placement(
        val location: Location,
        val interactionLocation: Location,
        val interactionScale: Float,
        val transformation: Transformation,
        val face: BlockFace,
    )

    private data class ScaleRange(
        val min: Float,
        val max: Float,
    )
}

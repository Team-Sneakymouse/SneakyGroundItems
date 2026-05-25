package com.danidipp.sneakygrounditems.interaction

import io.papermc.paper.event.player.PlayerPickItemEvent
import com.danidipp.sneakygrounditems.SneakyGroundItems
import com.danidipp.sneakygrounditems.commerce.GroundItemCommerceService
import com.danidipp.sneakygrounditems.config.Constants
import com.danidipp.sneakygrounditems.display.GroundItemDisplayService
import com.danidipp.sneakygrounditems.display.PlacementService
import com.danidipp.sneakygrounditems.integration.coreprotect.GroundItemAuditService
import com.danidipp.sneakygrounditems.integration.magicspells.GroundItemMetadataService
import com.danidipp.sneakygrounditems.lock.LockService
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import com.danidipp.sneakygrounditems.runtime.GroundItemRuntimeStateService
import com.nisovin.magicspells.MagicSpells
import org.bukkit.Bukkit
import org.bukkit.Effect
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.event.Event.Result
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

internal class GroundItemInteractionService(
    private val plugin: SneakyGroundItems,
    private val persistence: PersistenceService,
    private val lockService: LockService,
    private val placementService: PlacementService,
    private val displayService: GroundItemDisplayService,
    private val commerceService: GroundItemCommerceService,
    private val metadataService: GroundItemMetadataService,
    private val lookupService: GroundItemLookupService,
    private val runtimeState: GroundItemRuntimeStateService,
    private val effectsService: GroundItemEffectsService,
    private val inventoryService: GroundItemInventoryService,
    private val auditService: GroundItemAuditService,
) {
    fun handlePlayerDropItem(event: PlayerDropItemEvent) {
        if (event.isCancelled) return
        if (isMagicSpellsDropEvent()) return

        val droppedStack = event.itemDrop.itemStack.clone()
        if (metadataService.isExplicitlyUnplaceable(droppedStack)) return

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
        val placement = placementService.buildPlacement(event.player.location.yaw, hitPosition.toLocation(world), hitFace, droppedStack)

        val mergeResult = placementService.mergeIntoNearbyDisplay(placement.location, droppedStack)
        if (mergeResult != null) {
            finishMerge(event, droppedStack, mergeResult)
            return
        }

        if (!runtimeState.canCreateGroundItem(event.player.uniqueId)) {
            event.isCancelled = true
            return
        }

        if (!placementService.spawnDisplay(placement, droppedStack, event.player.name)) return

        runtimeState.markGroundItemCreated(event.player.uniqueId)
        effectsService.playFeedback(event.player.uniqueId, placement.location, "lom:sfx.clothingequip")
        auditService.logDrop(event.player, placement.location, droppedStack)
        event.itemDrop.remove()
    }

    fun handlePlayerInteract(event: PlayerInteractEvent) {
        if (event.useInteractedBlock() == Result.DENY || event.useItemInHand() == Result.DENY) return
        if (event.action == Action.LEFT_CLICK_BLOCK || event.action == Action.LEFT_CLICK_AIR) {
            if (runtimeState.shouldCancelAndRefreshPickupLeftClick(event.player.uniqueId)) {
                event.setUseInteractedBlock(Result.DENY)
                event.setUseItemInHand(Result.DENY)
                event.setCancelled(true)
                event.isCancelled = true
                return
            }
        }

        if (event.action != Action.LEFT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val display = lookupService.findInteractedGroundItem(event) ?: return

        event.setUseInteractedBlock(Result.DENY)
        event.setUseItemInHand(Result.DENY)
        event.setCancelled(true)

        when (persistence.getGroundItemState(display)) {
            GroundItemState.NORMAL -> handleNormalDisplayInteraction(player, display)
            GroundItemState.SALE -> commerceService.openPurchaseMenu(player, display)
            GroundItemState.PAYMENT -> handlePaymentInteraction(player, display)
        }
    }

    fun handlePlayerPickItem(event: PlayerPickItemEvent) {
        if (event.player.gameMode != GameMode.CREATIVE) return

        val display = lookupService.findLookedAtGroundItem(event.player) ?: return
        event.isCancelled = true
        event.player.inventory.setItem(event.targetSlot, display.itemStack.clone())
        event.player.inventory.heldItemSlot = event.targetSlot
    }

    fun handlePlayerQuit(player: Player) {
        runtimeState.clearPlayerState(player.uniqueId)
    }

    fun popGroundItems(center: Location, radius: Double): Int {
        val targets = lookupService.collectPopTargets(center, radius)
        for (target in targets) {
            popGroundItem(target)
        }
        return targets.size
    }

    private fun handleNormalDisplayInteraction(player: Player, display: ItemDisplay) {
        if (lockService.getLockMode(display) != LockService.LockMode.NONE) {
            player.playEffect(display.location, Effect.SMOKE, BlockFace.UP)
            return
        }
        if (metadataService.isSoulboundToAnotherPlayer(player.uniqueId, display.itemStack)) return
        pickupDisplay(player, display)
    }

    private fun handlePaymentInteraction(player: Player, display: ItemDisplay) {
        if (!lockService.isPlotMember(player, display.location)) {
            player.playEffect(display.location, Effect.SMOKE, BlockFace.UP)
            return
        }

        commerceService.redeemPaymentDisplay(player, display)
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
        effectsService.playFeedback(event.player.uniqueId, mergeResult.display.location, "lom:sfx.clothingequip")
        auditService.logDrop(
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

    private fun pickupDisplay(player: Player, display: ItemDisplay) {
        val displayUuid = display.uniqueId
        if (!runtimeState.tryBeginDisplayResolution(displayUuid)) return

        val playerUuid = player.uniqueId
        runtimeState.markPickupLeftClickGuard(playerUuid)
        MagicSpells.plugin.castListener.setNoCast(player)

        Bukkit.getScheduler().runTask(plugin, Runnable {
            try {
                val resolvedDisplay = persistence.resolveDisplay(displayUuid)
                if (resolvedDisplay == null || !persistence.isGroundItemDisplay(resolvedDisplay)) {
                    return@Runnable
                }
                if (persistence.getGroundItemState(resolvedDisplay) != GroundItemState.NORMAL) {
                    return@Runnable
                }

                val stack = resolvedDisplay.itemStack.clone()
                val pickupLocation = resolvedDisplay.location.clone()
                displayService.removeDisplayAndCompanion(resolvedDisplay)

                val onlinePlayer = Bukkit.getPlayer(playerUuid)
                if (onlinePlayer == null || !onlinePlayer.isOnline) {
                    pickupLocation.world.dropItemNaturally(pickupLocation, stack)
                    return@Runnable
                }

                val overflow = inventoryService.giveCollectedItem(onlinePlayer, stack)
                for (item in overflow) {
                    onlinePlayer.world.dropItemNaturally(onlinePlayer.location, item)
                }

                effectsService.playFeedback(onlinePlayer.uniqueId, pickupLocation, "lom:sfx.clothingunequip")
                auditService.logPickup(onlinePlayer, pickupLocation, stack)
            } finally {
                runtimeState.finishDisplayResolution(displayUuid)
            }
        })
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
}

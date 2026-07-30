package com.danidipp.sneakygrounditems.commerce

import com.danidipp.sneakygrounditems.SneakyGroundItems
import com.danidipp.sneakygrounditems.display.GroundItemDisplayService
import com.danidipp.sneakygrounditems.display.PlacementService
import com.danidipp.sneakygrounditems.integration.magicspells.GroundItemMetadataService
import com.danidipp.sneakygrounditems.integration.magicspells.MagicSpellsCommands
import com.danidipp.sneakygrounditems.interaction.GroundItemLookupService
import com.danidipp.sneakygrounditems.model.GroundItemCommandResult
import com.danidipp.sneakygrounditems.model.GroundItemPolicy
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.model.SaleSnapshot
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import com.danidipp.sneakygrounditems.runtime.GroundItemRuntimeStateService
import com.nisovin.magicspells.util.magicitems.MagicItems
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f

internal class GroundItemTheftService(
    private val plugin: SneakyGroundItems,
    private val persistence: PersistenceService,
    private val placementService: PlacementService,
    private val displayService: GroundItemDisplayService,
    private val metadataService: GroundItemMetadataService,
    private val lookupService: GroundItemLookupService,
    private val runtimeState: GroundItemRuntimeStateService,
) {
    fun stealGroundItem(player: Player): GroundItemCommandResult {
        val stolenGoods = createMagicItemStack(STOLEN_GOODS_MAGIC_ITEM_ID)
            ?: return failure("The stolen-goods item is unavailable")
        stolenGoods.amount = 1

        val display = lookupService.findLookedAtGroundItem(player)
            ?: return failure(null)
        val stack = display.itemStack
        val magicItemId = metadataService.getMagicItemId(stack)
        val originalMagicItemResolves = magicItemId != null && createMagicItemStack(magicItemId) != null
        val eligibility = GroundItemPolicy.StealEligibility(
                state = persistence.getGroundItemState(display),
                hasMagicItemId = magicItemId != null,
                magicItemResolves = originalMagicItemResolves,
                hasExpiry = metadataService.hasMagicSpellsExpiry(stack),
                hasFullDurability = metadataService.hasFullDurability(stack),
            )
        if (!GroundItemPolicy.canSteal(eligibility)) {
            return failure("That ground item cannot be stolen")
        }
        val resolvedMagicItemId = magicItemId ?: return failure("That ground item cannot be stolen")

        val currency = persistence.getCurrency(display) ?: return failure("That sale item has incomplete data")
        val price = persistence.getPrice(display) ?: return failure("That sale item has incomplete data")
        val displayUuid = display.uniqueId
        if (!runtimeState.tryBeginDisplayResolution(displayUuid)) {
            return failure("That ground item is already being changed")
        }

        try {
            val snapshot = SaleSnapshot(
                magicItemId = resolvedMagicItemId,
                itemAmount = stack.amount,
                currency = currency,
                price = price,
                originalScale = Vector3f(display.transformation.scale),
            )
            if (!convertToStolen(display, snapshot)) {
                return failure("Failed to create the stolen item display")
            }

            for (overflow in player.inventory.addItem(stolenGoods).values) {
                player.world.dropItemNaturally(player.location, overflow)
            }
            Bukkit.dispatchCommand(
                Bukkit.getConsoleSender(),
                MagicSpellsCommands.shopliftCost(player.name),
            )
            return GroundItemCommandResult(true, null)
        } finally {
            runtimeState.finishDisplayResolution(displayUuid)
        }
    }

    fun restoreStolenGroundItem(player: Player, display: ItemDisplay) {
        val displayUuid = display.uniqueId
        if (!runtimeState.tryBeginDisplayResolution(displayUuid)) return

        try {
            val snapshot = persistence.getSaleSnapshot(display)
            if (snapshot == null) {
                player.sendMessage("This stolen item has incomplete data")
                return
            }
            val restoredStack = createMagicItemStack(snapshot.magicItemId)
            if (restoredStack == null) {
                player.sendMessage("This stolen item cannot be restored")
                plugin.logger.warning(
                    "Could not restore stolen ground item $displayUuid: unknown MagicItem ${snapshot.magicItemId}",
                )
                return
            }
            restoredStack.amount = snapshot.itemAmount

            if (!restoreSaleDisplay(display, restoredStack, snapshot)) {
                player.sendMessage("This stolen item could not be restored")
                plugin.logger.warning("Could not restore stolen ground item $displayUuid: failed to create sale text")
                return
            }

            val command = MagicSpellsCommands.shopliftingNotice(player.name)
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                plugin.logger.warning("Restored stolen ground item $displayUuid, but console command failed: /$command")
            }
        } finally {
            runtimeState.finishDisplayResolution(displayUuid)
        }
    }

    private fun convertToStolen(display: ItemDisplay, snapshot: SaleSnapshot): Boolean {
        val originalStack = display.itemStack.clone()
        val originalTransformation = copyTransformation(display.transformation)

        display.setItemStack(ItemStack(Material.RABBIT_FOOT).apply {
            setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(STOLEN_ITEM_NOTICE_MODEL_DATA))
        })
        display.transformation = placementService.rescaleTransformation(display.transformation, STOLEN_ITEM_SCALE)
        persistence.setSaleSnapshot(display, snapshot)
        persistence.setGroundItemState(display, GroundItemState.STOLEN)
        displayService.ensureDisplayFace(display)
        if (displayService.syncCompanionText(display)) return true

        display.setItemStack(originalStack)
        display.transformation = originalTransformation
        persistence.setGroundItemState(display, GroundItemState.SALE)
        persistence.setSaleSnapshot(display, null)
        persistence.setCurrency(display, snapshot.currency)
        persistence.setPrice(display, snapshot.price)
        displayService.syncCompanionText(display)
        return false
    }

    private fun restoreSaleDisplay(
        display: ItemDisplay,
        restoredStack: ItemStack,
        snapshot: SaleSnapshot,
    ): Boolean {
        val stolenStack = display.itemStack.clone()
        val stolenTransformation = copyTransformation(display.transformation)

        display.setItemStack(restoredStack)
        display.transformation = copyTransformation(display.transformation).apply {
            scale.set(snapshot.originalScale)
        }
        persistence.setGroundItemState(display, GroundItemState.SALE)
        persistence.setSaleSnapshot(display, null)
        persistence.setCurrency(display, snapshot.currency)
        persistence.setPrice(display, snapshot.price)
        if (displayService.syncCompanionText(display)) return true

        display.setItemStack(stolenStack)
        display.transformation = stolenTransformation
        persistence.setSaleSnapshot(display, snapshot)
        persistence.setGroundItemState(display, GroundItemState.STOLEN)
        displayService.syncCompanionText(display)
        return false
    }

    private fun createMagicItemStack(magicItemId: String): ItemStack? {
        return MagicItems.getMagicItemByInternalName(magicItemId)?.itemStack?.clone()
    }

    private fun copyTransformation(transformation: Transformation): Transformation {
        return Transformation(
            Vector3f(transformation.translation),
            Quaternionf(transformation.leftRotation),
            Vector3f(transformation.scale),
            Quaternionf(transformation.rightRotation),
        )
    }

    private fun failure(message: String?): GroundItemCommandResult {
        val component = if (message != null) Component.text(message) else null
        return GroundItemCommandResult(false, component)
    }

    private companion object {
        const val STOLEN_GOODS_MAGIC_ITEM_ID = "item-stolengoods"
        const val STOLEN_ITEM_SCALE = 0.5f
        const val STOLEN_ITEM_NOTICE_MODEL_DATA = 247f
    }
}

package com.danidipp.sneakygrounditems.commerce

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import io.papermc.paper.datacomponent.item.ItemLore
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import com.danidipp.sneakygrounditems.command.Command
import com.danidipp.sneakygrounditems.config.Constants
import com.danidipp.sneakygrounditems.display.GroundItemDisplayService
import com.danidipp.sneakygrounditems.display.PlacementService
import com.danidipp.sneakygrounditems.integration.coreprotect.GroundItemAuditService
import com.danidipp.sneakygrounditems.integration.magicspells.GroundItemMetadataService
import com.danidipp.sneakygrounditems.interaction.GroundItemEffectsService
import com.danidipp.sneakygrounditems.interaction.GroundItemInventoryService
import com.danidipp.sneakygrounditems.interaction.GroundItemLookupService
import com.danidipp.sneakygrounditems.lock.LockService
import com.danidipp.sneakygrounditems.model.GroundItemCommandResult
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import com.danidipp.sneakygrounditems.runtime.GroundItemRuntimeStateService
import com.nisovin.magicspells.util.magicitems.MagicItems
import com.sneakybagofholding.api.BagOfHoldingApi
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

internal class GroundItemCommerceService(
    private val plugin: Plugin,
    private val persistence: PersistenceService,
    private val lockService: LockService,
    private val placementService: PlacementService,
    private val displayService: GroundItemDisplayService,
    private val bankService: BankService,
    private val metadataService: GroundItemMetadataService,
    private val lookupService: GroundItemLookupService,
    private val runtimeState: GroundItemRuntimeStateService,
    private val inventoryService: GroundItemInventoryService,
    private val effectsService: GroundItemEffectsService,
    private val auditService: GroundItemAuditService,
) {
    fun sellGroundItem(player: Player, price: Int, currency: Currency): GroundItemCommandResult {
        if (price > Command.MAX_SELL_PRICE) {
            return GroundItemCommandResult(false, Component.text("Sell price cannot exceed ${Command.MAX_SELL_PRICE}"))
        }

        if (!bankService.hasVariable(currency)) {
            return GroundItemCommandResult(false, Component.text("The $currency bank is unavailable"))
        }

        val display = lookupService.findLookedAtGroundItem(player) ?: return GroundItemCommandResult(false, null)
        if (metadataService.hasExpiredMagicSpellsLifetime(display.itemStack)) {
            displayService.removeDisplayAndCompanion(display)
            return GroundItemCommandResult(false, Component.text("That ground item has expired"))
        }

        val state = persistence.getGroundItemState(display)
        if (state == GroundItemState.PAYMENT) {
            return GroundItemCommandResult(false, Component.text("Payment items cannot be repriced"))
        }
        if (state == GroundItemState.NORMAL && lockService.getLockMode(display) != LockService.LockMode.NONE) {
            return GroundItemCommandResult(false, Component.text("That ground item is locked"))
        }
        if (state == GroundItemState.NORMAL && metadataService.isSoulbound(display.itemStack)) {
            return GroundItemCommandResult(false, Component.text("Soulbound ground items cannot be sold"))
        }
        if (state == GroundItemState.NORMAL && !player.hasPermission(Command.ADMIN_PERMISSION) && metadataService.isExplicitlyUnsellable(display.itemStack)) {
            return GroundItemCommandResult(false, Component.text("That item cannot be sold"))
        }
        if (!lockService.isPlotMember(player, display.location)) {
            return GroundItemCommandResult(false, Component.text("You must be a plot member where the ground item is placed"))
        }
        if (state != GroundItemState.SALE) {
            val limitFailure = plotSaleItemLimitFailure(display)
            if (limitFailure != null) {
                return GroundItemCommandResult(false, limitFailure)
            }
        }

        if (!markGroundItemForSale(display, price, currency)) {
            return GroundItemCommandResult(false, Component.text("Failed to create the sale display text"))
        }

        effectsService.playNearbySound(player.location, "lom:sfx.pricegun")

        val messageSuffix = if (state == GroundItemState.SALE) {
            " price updated to $price $currency"
        } else {
            " is now for sale for $price $currency"
        }
        val message = display.itemStack.displayName().append(Component.text(messageSuffix, NamedTextColor.YELLOW))
        return GroundItemCommandResult(true, message)
    }

    fun markGroundItemForSale(display: ItemDisplay, price: Int, currency: Currency): Boolean {
        persistence.setGroundItemState(display, GroundItemState.SALE)
        persistence.setCurrency(display, currency)
        persistence.setPrice(display, price)
        displayService.ensureDisplayFace(display)

        if (displayService.syncCompanionText(display)) {
            return true
        }

        displayService.normalizeDisplay(display)
        return false
    }

    fun openPurchaseMenu(player: Player, display: ItemDisplay) {
        val saleData = readCommerceData(display, GroundItemState.SALE) ?: run {
            player.sendMessage("This item is no longer for sale")
            displayService.normalizeDisplay(display)
            return
        }

        val holder = PurchaseMenuHolder(display.uniqueId)
        val inventory = Bukkit.createInventory(holder, PurchaseMenuSpec.size, PurchaseMenuSpec.title)
        holder.bind(inventory)

        inventory.setItem(PurchaseMenuSpec.purchaseSlot, createPurchaseButton(display.itemStack, saleData))
        inventory.setItem(PurchaseMenuSpec.previewSlot, display.itemStack.clone())
        inventory.setItem(PurchaseMenuSpec.noSlot, createNoButton())
        inventory.setItem(PurchaseMenuSpec.backgroundSlot, createBackground())
        player.openInventory(inventory)
    }

    fun handlePurchaseConfirmation(player: Player, displayUuid: UUID) {
        val display = persistence.resolveDisplay(displayUuid)
        if (display == null || !persistence.isGroundItemDisplay(display) || persistence.getGroundItemState(display) != GroundItemState.SALE) {
            player.closeInventory()
            player.sendMessage("This item is no longer for sale")
            return
        }

        if (!runtimeState.tryBeginDisplayResolution(displayUuid)) {
            player.closeInventory()
            player.sendMessage("This item is already being purchased")
            return
        }

        try {
            val saleData = readCommerceData(display, GroundItemState.SALE)
            if (saleData == null) {
                player.closeInventory()
                player.sendMessage("This item is no longer for sale")
                displayService.normalizeDisplay(display)
                return
            }

            if (lockService.isPlotMember(player, display.location)) {
                player.closeInventory()
                effectsService.playUiSound(player, "lom:buy")
                completeSalePickup(player, display, saleData, createPayment = false)
                return
            }

            val balance = bankService.getBalance(player, saleData.currency)
            if (balance == null) {
                player.closeInventory()
                player.sendMessage("The ${saleData.currency} bank is unavailable")
                return
            }

            if (balance + BANK_EPSILON < saleData.price.toDouble()) {
                effectsService.playUiSound(player, "lom:failwrong")
                return
            }

            if (!bankService.withdraw(player, saleData.currency, saleData.price)) {
                effectsService.playUiSound(player, "lom:failwrong")
                return
            }

            player.closeInventory()
            if (!completeSalePickup(player, display, saleData, createPayment = true)) {
                bankService.deposit(player, saleData.currency, saleData.price)
                effectsService.playUiSound(player, "lom:failwrong")
                player.sendMessage("Purchase failed because the payment item could not be created")
                return
            }
            effectsService.playUiSound(player, "lom:buy")
        } finally {
            runtimeState.finishDisplayResolution(displayUuid)
        }
    }

    fun redeemPaymentDisplay(player: Player, display: ItemDisplay) {
        val displayUuid = display.uniqueId
        if (!runtimeState.tryBeginDisplayResolution(displayUuid)) return

        try {
            val paymentData = readCommerceData(display, GroundItemState.PAYMENT)
            if (paymentData == null) {
                player.sendMessage("This payment item is invalid")
                return
            }
            if (!bankService.deposit(player, paymentData.currency, paymentData.price)) {
                player.sendMessage("The ${paymentData.currency} bank is unavailable")
                return
            }

            if (plotSaleItemLimitFailure(display) == null) {
                restoreSoldItemForSale(player, display, paymentData)
            }
            displayService.removeDisplayAndCompanion(display)
            effectsService.playUiSound(player, "lom:buy")
            player.sendMessage(buildRedeemPaymentMessage(display, paymentData))
        } finally {
            runtimeState.finishDisplayResolution(displayUuid)
        }
    }

    private fun restoreSoldItemForSale(player: Player, paymentDisplay: ItemDisplay, paymentData: CommerceData) {
        val soldMagicItemId = persistence.getPaymentMagicItemId(paymentDisplay) ?: return
        val soldAmount = persistence.getPaymentItemAmount(paymentDisplay) ?: return
        val replacementPlan = buildReplacementPlan(player, soldMagicItemId, soldAmount) ?: return
        val replacementStack = replacementPlan.stack
        val face = persistence.getFace(paymentDisplay) ?: placementService.deriveFace(paymentDisplay)
        val placement = PlacementService.Placement(
            location = paymentDisplay.location.clone(),
            transformation = placementService.rescaleTransformation(
                paymentDisplay.transformation,
                placementService.scaleForStack(replacementStack),
            ),
            face = face,
        )

        val replacementDisplay = placementService.spawnDisplayEntity(placement, replacementStack, player.name) ?: return
        if (!consumeReplacementSources(player, soldMagicItemId, replacementPlan)) {
            persistence.removeDisplay(replacementDisplay)
            return
        }

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val resolvedDisplay = persistence.resolveDisplay(replacementDisplay.uniqueId) ?: return@Runnable
            if (!persistence.isGroundItemDisplay(resolvedDisplay)) return@Runnable
            if (persistence.getGroundItemState(resolvedDisplay) != GroundItemState.NORMAL) return@Runnable

            markGroundItemForSale(resolvedDisplay, paymentData.price, paymentData.currency)
        }, 1L)
    }

    private fun buildReplacementPlan(player: Player, magicItemId: String, amount: Int): ReplacementPlan? {
        var inventoryAmount = 0
        var template: ItemStack? = null

        for (stack in player.inventory.contents.filterNotNull()) {
            if (metadataService.getMagicItemId(stack) != magicItemId) continue
            if (!hasFullDurability(stack)) continue

            if (template == null) {
                template = stack.clone()
            }
            inventoryAmount += stack.amount
        }

        val inventoryAmountToConsume = inventoryAmount.coerceAtMost(amount)
        val bagAmountToWithdraw = amount - inventoryAmountToConsume
        val api = if (bagAmountToWithdraw > 0) {
            runCatching { BagOfHoldingApi.get() }.getOrNull() ?: return null
        } else {
            null
        }

        if (api != null) {
            val storedAmount = runCatching { api.getStoredAmount(player, magicItemId) }.getOrDefault(0)
            if (storedAmount < bagAmountToWithdraw) {
                return null
            }
        }

        val replacementStack = template
            ?.clone()
            ?: createMagicItemStack(magicItemId)
            ?: return null

        return ReplacementPlan(
            stack = replacementStack.apply { this.amount = amount },
            inventoryAmountToConsume = inventoryAmountToConsume,
            bagAmountToWithdraw = bagAmountToWithdraw,
            bagApi = api,
        )
    }

    private fun consumeReplacementSources(player: Player, magicItemId: String, replacementPlan: ReplacementPlan): Boolean {
        if (replacementPlan.inventoryAmountToConsume > 0 &&
            countInventoryMagicItems(player, magicItemId) < replacementPlan.inventoryAmountToConsume
        ) {
            return false
        }

        if (replacementPlan.bagAmountToWithdraw > 0) {
            val api = replacementPlan.bagApi ?: return false
            runCatching {
                api.withdrawAsItemStack(player, magicItemId, replacementPlan.bagAmountToWithdraw).itemStackOrNull()
            }.getOrNull() ?: return false
        }

        if (replacementPlan.inventoryAmountToConsume > 0) {
            consumeMagicItems(player, magicItemId, replacementPlan.inventoryAmountToConsume)
        }

        return true
    }

    private fun plotSaleItemLimitFailure(display: ItemDisplay): Component? {
        val plot = lockService.findPriorityFivePlotAt(display.location) ?: return null
        val world = display.world
        val saleItemCount = world.loadedChunks.asSequence()
            .flatMap { chunk -> chunk.entities.asSequence() }
            .filterIsInstance<ItemDisplay>()
            .filter(persistence::isGroundItemDisplay)
            .filter { it.world == world }
            .filter { persistence.getGroundItemState(it) == GroundItemState.SALE }
            .filter { plot.contains(BlockVector3.at(it.location.blockX, it.location.blockY, it.location.blockZ)) }
            .count()

        return when {
            saleItemCount == Constants.PLOT_SALE_ITEM_LIMIT -> {
                Component.text("This plot is at the limit for sale items (${Constants.PLOT_SALE_ITEM_LIMIT})")
            }

            saleItemCount > Constants.PLOT_SALE_ITEM_LIMIT -> {
                Component.text("This plot is over the limit for sale items ($saleItemCount/${Constants.PLOT_SALE_ITEM_LIMIT})")
            }

            else -> null
        }
    }

    private fun createMagicItemStack(magicItemId: String): ItemStack? {
        return MagicItems.getMagicItemByInternalName(magicItemId)
            ?.itemStack
            ?.clone()
    }

    private fun countInventoryMagicItems(player: Player, magicItemId: String): Int {
        return player.inventory.contents
            .filterNotNull()
            .filter { metadataService.getMagicItemId(it) == magicItemId }
            .filter(::hasFullDurability)
            .sumOf { it.amount }
    }

    private fun hasFullDurability(stack: ItemStack): Boolean {
        val damageable = stack.itemMeta as? Damageable ?: return true
        return !damageable.hasDamage() || damageable.damage == 0
    }

    private fun consumeMagicItems(player: Player, magicItemId: String, amount: Int) {
        var remaining = amount
        val inventory = player.inventory

        for (slot in inventory.contents.indices) {
            if (remaining <= 0) return

            val stack = inventory.getItem(slot) ?: continue
            if (metadataService.getMagicItemId(stack) != magicItemId) continue
            if (!hasFullDurability(stack)) continue

            val consumed = minOf(stack.amount, remaining)
            remaining -= consumed

            if (stack.amount == consumed) {
                inventory.setItem(slot, null)
            } else {
                stack.amount -= consumed
                inventory.setItem(slot, stack)
            }
        }
    }

    private fun completeSalePickup(player: Player, display: ItemDisplay, saleData: CommerceData, createPayment: Boolean): Boolean {
        val stack = display.itemStack.clone()
        val pickupLocation = display.location.clone()

        if (createPayment) {
            if (!convertDisplayToPayment(display, saleData)) {
                return false
            }
        } else {
            displayService.removeDisplayAndCompanion(display)
        }

        val overflow = inventoryService.giveCollectedItem(player, stack)
        for (item in overflow) {
            player.world.dropItemNaturally(player.location, item)
        }

        auditService.logPickup(player, pickupLocation, stack)

        if (createPayment) {
            player.sendMessage(
                Component.text("You bought ", NamedTextColor.YELLOW)
                    .append(stack.displayName())
                    .append(Component.text(" for ", NamedTextColor.YELLOW))
                    .append(Component.text("${saleData.price} ${saleData.currency}", NamedTextColor.GOLD)),
            )
        }
        return true
    }

    private fun convertDisplayToPayment(display: ItemDisplay, saleData: CommerceData): Boolean {
        val originalStack = display.itemStack.clone()
        val originalTransformation = copyTransformation(display.transformation)
        val originalState = persistence.getGroundItemState(display)
        val originalCurrency = persistence.getCurrency(display)
        val originalPrice = persistence.getPrice(display)
        val originalPaymentMagicItemId = persistence.getPaymentMagicItemId(display)
        val originalPaymentItemAmount = persistence.getPaymentItemAmount(display)

        display.setItemStack(createPaymentStack(saleData.currency))
        display.transformation = placementService.rescaleTransformation(display.transformation, PAYMENT_ITEM_SCALE)
        persistence.setGroundItemState(display, GroundItemState.PAYMENT)
        persistence.setCurrency(display, saleData.currency)
        persistence.setPrice(display, saleData.price)
        persistence.setPaymentMagicItemId(display, metadataService.getMagicItemId(originalStack))
        persistence.setPaymentItemAmount(display, originalStack.amount)
        displayService.ensureDisplayFace(display)
        if (displayService.syncCompanionText(display)) {
            return true
        }

        display.setItemStack(originalStack)
        display.transformation = originalTransformation
        persistence.setGroundItemState(display, originalState)
        persistence.setCurrency(display, originalCurrency)
        persistence.setPrice(display, originalPrice)
        persistence.setPaymentMagicItemId(display, originalPaymentMagicItemId)
        persistence.setPaymentItemAmount(display, originalPaymentItemAmount)
        displayService.ensureDisplayFace(display)
        displayService.syncCompanionText(display)
        return false
    }

    private fun createPurchaseButton(stack: ItemStack, saleData: CommerceData): ItemStack {
        return rabbitFootWithMeta(
            customModelData = 7,
            name = Component.text("Purchase", NamedTextColor.GREEN),
            lore = listOf(
                Component.text()
                    .append(Component.text("${stack.amount}x ", NamedTextColor.YELLOW))
                    .append(stack.displayName())
                    .build(),
                buildPriceLabel(saleData.price, saleData.currency, stack.amount, NamedTextColor.YELLOW),
            ),
        )
    }

    private fun createNoButton(): ItemStack {
        return rabbitFootWithMeta(
            customModelData = 7,
            name = Component.text("No", NamedTextColor.RED),
            lore = listOf(Component.text("Cancel", NamedTextColor.GRAY)),
        )
    }

    private fun createBackground(): ItemStack {
        return ItemStack(Material.JIGSAW).apply {
            setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(3013f))
            setData(DataComponentTypes.HIDE_TOOLTIP)
        }
    }

    private fun createPaymentStack(currency: Currency): ItemStack {
        return ItemStack(Material.RABBIT_FOOT).apply {
            setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(currency.customModelData.toFloat()))
        }
    }

    private fun rabbitFootWithMeta(
        customModelData: Int,
        name: Component,
        lore: List<Component>,
    ): ItemStack = ItemStack(Material.RABBIT_FOOT).apply {
        setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(customModelData.toFloat()))
        setData(DataComponentTypes.ITEM_NAME, name)
        setData(DataComponentTypes.LORE, ItemLore.lore(lore.map { it.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE) }))
    }

    private fun formatEachPrice(totalPrice: Int, quantity: Int): String {
        val divisor = quantity.coerceAtLeast(1)
        return BigDecimal.valueOf(totalPrice.toLong())
            .divide(BigDecimal.valueOf(divisor.toLong()), 1, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
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

    private fun readCommerceData(display: ItemDisplay, expectedState: GroundItemState): CommerceData? {
        if (persistence.getGroundItemState(display) != expectedState) return null

        val currency = persistence.getCurrency(display) ?: return null
        val price = persistence.getPrice(display) ?: return null
        return CommerceData(expectedState, currency, price)
    }

    private fun buildRedeemPaymentMessage(display: ItemDisplay, paymentData: CommerceData): Component {
        val soldMagicItemId = persistence.getPaymentMagicItemId(display)
        val soldAmount = persistence.getPaymentItemAmount(display)
        val message = Component.text("You gained ", NamedTextColor.YELLOW)
            .append(Component.text("${paymentData.price} ${paymentData.currency}", NamedTextColor.GOLD))

        if (soldMagicItemId == null || soldAmount == null) {
            return message
        }

        val soldMagicItem = MagicItems.getMagicItemByInternalName(soldMagicItemId)
        if (soldMagicItem == null) {
            return message
                .append(Component.text(" from selling ", NamedTextColor.YELLOW))
                .append(Component.text("an unknown item (ID: $soldMagicItemId)", NamedTextColor.RED))
        }

        return message
            .append(Component.text(" from selling ", NamedTextColor.YELLOW))
            .append(Component.text("${soldAmount}x ", NamedTextColor.GOLD))
            .append(soldMagicItem.itemStack.displayName())
    }

    private fun copyTransformation(transformation: Transformation): Transformation {
        return Transformation(
            Vector3f(transformation.translation),
            Quaternionf(transformation.leftRotation),
            Vector3f(transformation.scale),
            Quaternionf(transformation.rightRotation),
        )
    }

    private data class CommerceData(
        val state: GroundItemState,
        val currency: Currency,
        val price: Int,
    )

    private data class ReplacementPlan(
        val stack: ItemStack,
        val inventoryAmountToConsume: Int,
        val bagAmountToWithdraw: Int,
        val bagApi: BagOfHoldingApi?,
    )

    companion object {
        private const val PAYMENT_ITEM_SCALE = 0.5f
        private const val BANK_EPSILON = 1.0e-6
    }
}

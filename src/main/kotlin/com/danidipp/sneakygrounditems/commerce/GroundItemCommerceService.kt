package com.danidipp.sneakygrounditems.commerce

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import io.papermc.paper.datacomponent.item.ItemLore
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import com.danidipp.sneakygrounditems.command.Command
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
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

internal class GroundItemCommerceService(
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

        persistence.setGroundItemState(display, GroundItemState.SALE)
        persistence.setCurrency(display, currency)
        persistence.setPrice(display, price)
        displayService.ensureDisplayFace(display)

        if (!displayService.syncCompanionText(display)) {
            displayService.normalizeDisplay(display)
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

            displayService.removeDisplayAndCompanion(display)
            effectsService.playUiSound(player, "lom:buy")
            player.sendMessage(
                Component.text("Redeemed payment item for ", NamedTextColor.YELLOW)
                    .append(Component.text("${paymentData.price} ${paymentData.currency}", NamedTextColor.GOLD)),
            )
        } finally {
            runtimeState.finishDisplayResolution(displayUuid)
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

        display.setItemStack(createPaymentStack(saleData.currency))
        display.transformation = placementService.rescaleTransformation(display.transformation, PAYMENT_ITEM_SCALE)
        persistence.setGroundItemState(display, GroundItemState.PAYMENT)
        persistence.setCurrency(display, saleData.currency)
        persistence.setPrice(display, saleData.price)
        displayService.ensureDisplayFace(display)
        if (displayService.syncCompanionText(display)) {
            return true
        }

        display.setItemStack(originalStack)
        display.transformation = originalTransformation
        persistence.setGroundItemState(display, originalState)
        persistence.setCurrency(display, originalCurrency)
        persistence.setPrice(display, originalPrice)
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
    companion object {
        private const val PAYMENT_ITEM_SCALE = 0.5f
        private const val BANK_EPSILON = 1.0e-6
    }
}

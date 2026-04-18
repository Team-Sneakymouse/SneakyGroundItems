package com.danidipp.sneakygrounditems

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import io.papermc.paper.datacomponent.item.ItemLore
import io.papermc.paper.event.player.PlayerPickItemEvent
import net.coreprotect.CoreProtect
import net.coreprotect.CoreProtectAPI
import net.coreprotect.listener.entity.EntityPickupItemListener
import net.coreprotect.listener.player.PlayerDropItemListener
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
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
import org.bukkit.entity.Display
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.Event.Result
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

internal class GroundItemManager(
    private val plugin: SneakyGroundItems,
    private val persistence: PersistenceService,
    private val lockService: LockService,
) {
    private val placementService = PlacementService(persistence, lockService)
    private val bankService = BankService()
    private val soulboundOwnerKey = NamespacedKey("magicspells", "soulbound_owner")
    private val groundItemSellableKey = NamespacedKey("magicspells", "magicspellpermanentdata_grounditem_sellable")
    private val pendingResolutionDisplayIds = mutableSetOf<UUID>()
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

        when (persistence.getGroundItemState(display)) {
            GroundItemState.NORMAL -> handleNormalDisplayInteraction(player, display)
            GroundItemState.SALE -> openPurchaseMenu(player, display)
            GroundItemState.PAYMENT -> handlePaymentInteraction(player, display)
        }
    }

    fun handleInventoryClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? PurchaseMenuHolder ?: return
        event.isCancelled = true
        if (event.rawSlot !in 0 until event.view.topInventory.size) return

        when (event.rawSlot) {
            PURCHASE_SLOT -> {
                val player = event.whoClicked as? Player ?: return
                handlePurchaseConfirmation(player, holder.displayUuid)
            }

            NO_SLOT -> event.whoClicked.closeInventory()
        }
    }

    fun handleInventoryDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder !is PurchaseMenuHolder) return
        if (event.rawSlots.any { it in 0 until event.view.topInventory.size }) {
            event.isCancelled = true
        }
    }

    fun handleInventoryClose(event: InventoryCloseEvent) {
        if (event.view.topInventory.holder !is PurchaseMenuHolder) return
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

    fun sellGroundItem(player: Player, price: Int, currency: Currency): CommandResult {
        if (price > Command.MAX_SELL_PRICE) {
            return CommandResult(false, Component.text("Sell price cannot exceed ${Command.MAX_SELL_PRICE}"))
        }

        if (!bankService.hasVariable(currency)) {
            return CommandResult(false, Component.text("The $currency bank is unavailable"))
        }

        val display = findLookedAtGroundItem(player)
            ?: return CommandResult(false, Component.text("You are not looking at a ground item"))
        val state = persistence.getGroundItemState(display)

        if (state == GroundItemState.PAYMENT) {
            return CommandResult(false, Component.text("Payment items cannot be repriced"))
        }
        if (state == GroundItemState.NORMAL && lockService.getLockMode(display) != LockService.LockMode.NONE) {
            return CommandResult(false, Component.text("That ground item is locked"))
        }
        if (state == GroundItemState.NORMAL && isSoulbound(display.itemStack)) {
            return CommandResult(false, Component.text("Soulbound ground items cannot be sold"))
        }
        if (state == GroundItemState.NORMAL && !player.hasPermission(Command.ADMIN_PERMISSION) && isExplicitlyUnsellable(display.itemStack)) {
            return CommandResult(false, Component.text("That item cannot be sold"))
        }
        if (!lockService.isPlotMember(player, display.location)) {
            return CommandResult(false, Component.text("You must be a plot member where the ground item is placed"))
        }

        persistence.setGroundItemState(display, GroundItemState.SALE)
        persistence.setCurrency(display, currency)
        persistence.setPrice(display, price)
        ensureDisplayFace(display)

        if (!syncCompanionText(display)) {
            normalizeDisplay(display)
            return CommandResult(false, Component.text("Failed to create the sale display text"))
        }

        playNearbySound(player.location, "lom:sfx.pricegun")

        val messageSuffix = if (state == GroundItemState.SALE) {
            " price updated to $price $currency"
        } else {
            " is now for sale for $price $currency"
        }
        val message = display.itemStack.displayName().append(Component.text(messageSuffix, NamedTextColor.YELLOW))
        return CommandResult(true, message)
    }

    fun getGroundItemState(display: ItemDisplay): GroundItemState {
        return persistence.getGroundItemState(display)
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

    private fun handleNormalDisplayInteraction(player: Player, display: ItemDisplay) {
        if (lockService.getLockMode(display) != LockService.LockMode.NONE) {
            player.playEffect(display.location, Effect.SMOKE, BlockFace.UP)
            return
        }
        if (!isPickupAllowed(player.uniqueId, display.itemStack)) return
        pickupDisplay(player, display)
    }

    private fun handlePaymentInteraction(player: Player, display: ItemDisplay) {
        if (!lockService.isPlotMember(player, display.location)) {
            player.playEffect(display.location, Effect.SMOKE, BlockFace.UP)
            return
        }

        redeemPaymentDisplay(player, display)
    }

    private fun openPurchaseMenu(player: Player, display: ItemDisplay) {
        val saleData = readCommerceData(display, GroundItemState.SALE) ?: run {
            player.sendMessage("This item is no longer for sale")
            normalizeDisplay(display)
            return
        }

        val holder = PurchaseMenuHolder(display.uniqueId)
        val inventory = Bukkit.createInventory(holder, PURCHASE_MENU_SIZE, PURCHASE_MENU_TITLE)
        holder.bind(inventory)

        inventory.setItem(PURCHASE_SLOT, createPurchaseButton(display.itemStack, saleData))
        inventory.setItem(PREVIEW_SLOT, display.itemStack.clone())
        inventory.setItem(NO_SLOT, createNoButton())
        inventory.setItem(BACKGROUND_SLOT, createBackground())
        player.openInventory(inventory)
    }

    private fun handlePurchaseConfirmation(player: Player, displayUuid: UUID) {
        val display = persistence.resolveDisplay(displayUuid)
        if (display == null || !persistence.isGroundItemDisplay(display) || persistence.getGroundItemState(display) != GroundItemState.SALE) {
            player.closeInventory()
            player.sendMessage("This item is no longer for sale")
            return
        }

        if (!pendingResolutionDisplayIds.add(displayUuid)) {
            player.closeInventory()
            player.sendMessage("This item is already being purchased")
            return
        }

        try {
            val saleData = readCommerceData(display, GroundItemState.SALE)
            if (saleData == null) {
                player.closeInventory()
                player.sendMessage("This item is no longer for sale")
                normalizeDisplay(display)
                return
            }

            if (lockService.isPlotMember(player, display.location)) {
                player.closeInventory()
                playUiSound(player, "lom:buy")
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
                playUiSound(player, "lom:failwrong")
                return
            }

            if (!bankService.withdraw(player, saleData.currency, saleData.price)) {
                playUiSound(player, "lom:failwrong")
                return
            }

            player.closeInventory()
            if (!completeSalePickup(player, display, saleData, createPayment = true)) {
                bankService.deposit(player, saleData.currency, saleData.price)
                playUiSound(player, "lom:failwrong")
                player.sendMessage("Purchase failed because the payment item could not be created")
                return
            }
            playUiSound(player, "lom:buy")
        } finally {
            pendingResolutionDisplayIds.remove(displayUuid)
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
            removeDisplayAndCompanion(display)
        }

        val overflow = giveCollectedItem(player, stack)
        for (item in overflow) {
            player.world.dropItemNaturally(player.location, item)
        }

        logPickup(player, pickupLocation, stack)

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

    private fun redeemPaymentDisplay(player: Player, display: ItemDisplay) {
        val displayUuid = display.uniqueId
        if (!pendingResolutionDisplayIds.add(displayUuid)) return

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

            removeDisplayAndCompanion(display)
            playUiSound(player, "lom:buy")
            player.sendMessage(
                Component.text("Redeemed payment item for ", NamedTextColor.YELLOW)
                    .append(Component.text("${paymentData.price} ${paymentData.currency}", NamedTextColor.GOLD)),
            )
        } finally {
            pendingResolutionDisplayIds.remove(displayUuid)
        }
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
        ensureDisplayFace(display)
        if (syncCompanionText(display)) {
            return true
        }

        display.setItemStack(originalStack)
        display.transformation = originalTransformation
        persistence.setGroundItemState(display, originalState)
        persistence.setCurrency(display, originalCurrency)
        persistence.setPrice(display, originalPrice)
        ensureDisplayFace(display)
        syncCompanionText(display)
        return false
    }

    private fun syncCompanionText(display: ItemDisplay): Boolean {
        val state = persistence.getGroundItemState(display)
        if (state == GroundItemState.NORMAL) {
            reconcileNormalDisplay(display, emptyList())
            return true
        }

        val commerceData = readCommerceData(display, state) ?: return false
        val face = ensureDisplayFace(display)
        val linkedText = persistence.getPartnerUuid(display)
            ?.let(persistence::resolveTextDisplay)
            ?.takeIf { persistence.isGroundItemText(it) && persistence.getPartnerUuid(it) == display.uniqueId }

        val textDisplay = linkedText ?: spawnCompanionText(display)
        if (textDisplay == null) {
            persistence.setPartnerUuid(display, null)
            return false
        }

        updateCompanionText(textDisplay, display, commerceData, face)
        return true
    }

    private fun spawnCompanionText(display: ItemDisplay): TextDisplay? {
        var textDisplay: TextDisplay? = null

        return runCatching {
            textDisplay = display.world.spawn(display.location, TextDisplay::class.java) { spawnedDisplay ->
                spawnedDisplay.isPersistent = true
                spawnedDisplay.setGravity(false)
                persistence.tagText(spawnedDisplay)
            }
            textDisplay
        }.getOrElse {
            textDisplay?.remove()
            null
        }
    }

    private fun updateCompanionText(textDisplay: TextDisplay, display: ItemDisplay, commerceData: CommerceData, face: BlockFace) {
        val surface = placementService.surfaceForFace(face)
        val offset = if (surface == PlacementSurface.WALL) {
            Vector(0.0, TEXT_OFFSET.toDouble(), 0.0)
        } else {
            face.direction.clone().normalize().multiply(TEXT_OFFSET.toDouble())
        }
        textDisplay.teleport(display.location.clone().add(offset))
        textDisplay.isPersistent = true
        textDisplay.setGravity(false)
        textDisplay.viewRange = SALE_TEXT_VIEW_RANGE
        textDisplay.transformation = Transformation(
            Vector3f(),
            Quaternionf(),
            Vector3f(TEXT_SCALE, TEXT_SCALE, TEXT_SCALE),
            Quaternionf(),
        )
        textDisplay.billboard = billboardForFace(face)
        if (surface == PlacementSurface.WALL) {
            textDisplay.setRotation(yawForFace(face), 0.0f)
        } else {
            textDisplay.setRotation(0.0f, 0.0f)
        }
        textDisplay.text(buildCompanionText(display.itemStack, commerceData))

        persistence.tagText(textDisplay)
        persistence.setGroundItemState(textDisplay, commerceData.state)
        persistence.setPartnerUuid(textDisplay, display.uniqueId)
        persistence.setPartnerUuid(display, textDisplay.uniqueId)
    }

    private fun buildCompanionText(stack: ItemStack, commerceData: CommerceData): Component {
        return when (commerceData.state) {
            GroundItemState.SALE -> {
                val firstLine = Component.text()
                    .append(Component.text("${stack.amount}x "))
                    .append(stack.displayName())
                    .build()
                val secondLine = Component.text()
                    .append(buildPriceLabel(commerceData.price, commerceData.currency, stack.amount, NamedTextColor.GOLD))
                    .build()
                firstLine.append(Component.newline()).append(secondLine)
            }

            GroundItemState.PAYMENT -> Component.text("${commerceData.price} ${commerceData.currency}")
            GroundItemState.NORMAL -> Component.empty()
        }
    }

    private fun reconcileNormalDisplay(display: ItemDisplay, candidates: List<TextDisplay>) {
        val linkedText = persistence.getPartnerUuid(display)
            ?.let(persistence::resolveTextDisplay)
            ?.takeIf { persistence.getPartnerUuid(it) == display.uniqueId }

        if (linkedText != null) {
            persistence.removeTextDisplay(linkedText)
        }
        for (candidate in candidates) {
            if (candidate.uniqueId != linkedText?.uniqueId) {
                persistence.removeTextDisplay(candidate)
            }
        }

        persistence.clearCommerceData(display)
    }

    private fun normalizeDisplay(display: ItemDisplay) {
        persistence.setGroundItemState(display, GroundItemState.NORMAL)
        persistence.setCurrency(display, null)
        persistence.setPrice(display, null)
        syncCompanionText(display)
    }

    private fun processChunk(chunk: Chunk) {
        val displays = mutableListOf<ItemDisplay>()
        val companionTextByPartner = mutableMapOf<UUID, MutableList<TextDisplay>>()

        for (entity in chunk.entities) {
            when {
                entity is ItemDisplay && persistence.isGroundItemDisplay(entity) -> {
                    displays += entity
                    lockService.clearExpiredTemporaryLock(entity)
                }

                entity is TextDisplay && persistence.isGroundItemText(entity) -> {
                    val partnerUuid = persistence.getPartnerUuid(entity)
                    if (partnerUuid == null) {
                        persistence.removeTextDisplay(entity)
                        continue
                    }

                    companionTextByPartner.getOrPut(partnerUuid) { mutableListOf() }.add(entity)
                }
            }
        }

        for (display in displays) {
            reconcileCommerceDisplay(display, companionTextByPartner.remove(display.uniqueId).orEmpty())
        }

        for (orphanGroup in companionTextByPartner.values) {
            for (orphan in orphanGroup) {
                persistence.removeTextDisplay(orphan)
            }
        }
    }

    private fun reconcileCommerceDisplay(display: ItemDisplay, candidates: List<TextDisplay>) {
        val state = persistence.getGroundItemState(display)
        if (state == GroundItemState.NORMAL) {
            reconcileNormalDisplay(display, candidates)
            return
        }

        val commerceData = readCommerceData(display, state)
        if (commerceData == null) {
            reconcileNormalDisplay(display, candidates)
            return
        }

        val face = ensureDisplayFace(display)
        val linkedText = persistence.getPartnerUuid(display)
            ?.let(persistence::resolveTextDisplay)
            ?.takeIf { persistence.isGroundItemText(it) && persistence.getPartnerUuid(it) == display.uniqueId }
        val chosenText = (listOfNotNull(linkedText) + candidates)
            .distinctBy { it.uniqueId }
            .firstOrNull()
            ?: spawnCompanionText(display)

        if (chosenText == null) {
            persistence.setPartnerUuid(display, null)
            return
        }

        updateCompanionText(chosenText, display, commerceData, face)

        for (candidate in candidates) {
            if (candidate.uniqueId != chosenText.uniqueId) {
                persistence.removeTextDisplay(candidate)
            }
        }
        if (linkedText != null && linkedText.uniqueId != chosenText.uniqueId) {
            persistence.removeTextDisplay(linkedText)
        }
    }

    private fun ensureDisplayFace(display: ItemDisplay): BlockFace {
        val existingFace = persistence.getFace(display)
        if (existingFace != null) return existingFace

        val derivedFace = placementService.deriveFace(display)
        persistence.setFace(display, derivedFace)
        return derivedFace
    }

    private fun billboardForFace(face: BlockFace): Display.Billboard {
        return when (placementService.surfaceForFace(face)) {
            PlacementSurface.GROUND -> Display.Billboard.VERTICAL
            PlacementSurface.WALL -> Display.Billboard.FIXED
            PlacementSurface.CEILING -> Display.Billboard.CENTER
        }
    }

    private fun yawForFace(face: BlockFace): Float {
        return when (face) {
            BlockFace.NORTH -> 180.0f
            BlockFace.SOUTH -> 0.0f
            BlockFace.EAST -> -90.0f
            BlockFace.WEST -> 90.0f
            else -> 0.0f
        }
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

    private fun removeDisplayAndCompanion(display: ItemDisplay) {
        val partnerUuid = persistence.getPartnerUuid(display)
            ?: return persistence.removeDisplay(display)
        val textDisplay = persistence.resolveTextDisplay(partnerUuid)
            ?.takeIf { persistence.isGroundItemText(it) && persistence.getPartnerUuid(it) == display.uniqueId }

        if (textDisplay != null) {
            persistence.removeTextDisplay(textDisplay)
        }
        persistence.removeDisplay(display)
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

    private fun collectPopTargets(center: Location, radius: Double): List<ItemDisplay> {
        val world = center.world ?: return emptyList()
        val radiusSquared = radius * radius

        return world.getNearbyEntities(center, radius, radius, radius).asSequence()
            .filterIsInstance<ItemDisplay>()
            .filter(persistence::isGroundItemDisplay)
            .filter { !isResolutionPending(it.uniqueId) }
            .filter { persistence.getGroundItemState(it) == GroundItemState.NORMAL }
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
            .filter { !isResolutionPending(it.uniqueId) }
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
        if (!pendingResolutionDisplayIds.add(displayUuid)) return

        val playerUuid = player.uniqueId

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
                removeDisplayAndCompanion(resolvedDisplay)

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
                pendingResolutionDisplayIds.remove(displayUuid)
            }
        })
    }

    private fun isResolutionPending(displayUuid: UUID): Boolean {
        return displayUuid in pendingResolutionDisplayIds
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

    private fun isSoulbound(stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return false
        return meta.persistentDataContainer.has(soulboundOwnerKey, PersistentDataType.STRING)
    }

    private fun isExplicitlyUnsellable(stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return false
        val rawValue = meta.persistentDataContainer.get(groundItemSellableKey, PersistentDataType.STRING) ?: return false
        return rawValue.trim().equals("false", ignoreCase = true)
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

    private fun playUiSound(player: Player, sound: String) {
        player.world.playSound(player.location, sound, SoundCategory.MASTER, 0.5f, 1.0f)
    }

    private fun playNearbySound(location: Location, sound: String) {
        location.world.playSound(location, sound, SoundCategory.MASTER, 0.5f, 1.0f)
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

    internal data class CommandResult(
        val success: Boolean,
        val message: Component,
    )

    private data class CommerceData(
        val state: GroundItemState,
        val currency: Currency,
        val price: Int,
    )

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

    companion object {
        private val PURCHASE_MENU_TITLE = Component.text("Purchase Ground Item")
        private const val PURCHASE_MENU_SIZE = 9
        private const val PURCHASE_SLOT = 3
        private const val PREVIEW_SLOT = 4
        private const val NO_SLOT = 5
        private const val BACKGROUND_SLOT = 8
        private const val SALE_TEXT_VIEW_RANGE = 24.0f
        private const val TEXT_SCALE = 0.15f
        private const val TEXT_OFFSET = 0.25f
        private const val PAYMENT_ITEM_SCALE = 0.5f
        private const val BANK_EPSILON = 1.0e-6
    }
}

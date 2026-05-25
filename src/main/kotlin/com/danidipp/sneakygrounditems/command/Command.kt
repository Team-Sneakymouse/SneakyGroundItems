package com.danidipp.sneakygrounditems.command

import com.danidipp.sneakygrounditems.commerce.Currency
import com.danidipp.sneakygrounditems.commerce.GroundItemCommerceService
import com.danidipp.sneakygrounditems.config.Constants
import com.danidipp.sneakygrounditems.interaction.GroundItemInteractionService
import com.danidipp.sneakygrounditems.interaction.GroundItemLookupService
import com.danidipp.sneakygrounditems.lock.LockService
import com.danidipp.sneakygrounditems.migration.GroundItemMigrationService
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import com.sk89q.worldedit.math.BlockVector3
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player

internal class Command(
    private val persistence: PersistenceService,
    private val lookupService: GroundItemLookupService,
    private val interactionService: GroundItemInteractionService,
    private val commerceService: GroundItemCommerceService,
    private val migrationService: GroundItemMigrationService,
    private val lockService: LockService,
) {
    private val playerSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        suggest(Bukkit.getOnlinePlayers().map { it.name }.sorted(), builder)
    }
    private val offlinePlayerSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        if (builder.remaining.isEmpty()) {
            builder.buildFuture()
        } else {
            suggest(Bukkit.getOfflinePlayers().mapNotNull { it.name }.sorted(), builder)
        }
    }
    private val currencySuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        suggest(Currency.entries.map { it.id }, builder)
    }

    fun build(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("grounditem")
            .requires { source ->
                source.sender.hasAnyPermission(
                    LOCK_PERMISSION,
                    UNLOCK_PERMISSION,
                    POP_PERMISSION,
                    SELL_PERMISSION,
                    ADMIN_PERMISSION,
                    MODERATOR_PERMISSION,
                )
            }
            .executes { fail(it, "Missing subcommand. Usage: /grounditem <lock|unlock|pop|sell|migrate|check> ...") }
            .then(buildLockCommand())
            .then(buildUnlockCommand())
            .then(buildPopCommand())
            .then(buildSellCommand())
            .then(buildMigrateCommand())
            .then(buildCheckCommand())
            .build()

    private fun buildLockCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("lock")
            .requires { it.sender.hasAnyPermission(LOCK_PERMISSION, ADMIN_PERMISSION) }
            .executes { lockOrUnlock(it, lock = true, targetName = null) }
            .then(
                Commands.argument("playerName", StringArgumentType.word())
                    .suggests(playerSuggestions)
                    .executes { lockOrUnlock(it, lock = true, targetName = StringArgumentType.getString(it, "playerName")) }
            )

    private fun buildUnlockCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("unlock")
            .requires { it.sender.hasAnyPermission(UNLOCK_PERMISSION, ADMIN_PERMISSION) }
            .executes { lockOrUnlock(it, lock = false, targetName = null) }
            .then(
                Commands.argument("playerName", StringArgumentType.word())
                    .suggests(playerSuggestions)
                    .executes { lockOrUnlock(it, lock = false, targetName = StringArgumentType.getString(it, "playerName")) }
            )

    private fun buildPopCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("pop")
            .requires { it.sender.hasPermission(POP_PERMISSION) }
            .then(buildPopRaycastCommand())
            .then(buildPopCoordinatesCommand())

    private fun buildSellCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("sell")
            .requires { it.sender.hasAnyPermission(SELL_PERMISSION, ADMIN_PERMISSION) }
            .then(
                Commands.argument("amount", IntegerArgumentType.integer(1, MAX_SELL_PRICE))
                    .then(
                        Commands.argument("currency", StringArgumentType.word())
                            .suggests(currencySuggestions)
                            .executes {
                                sell(
                                    it,
                                    IntegerArgumentType.getInteger(it, "amount"),
                                    StringArgumentType.getString(it, "currency"),
                                )
                            }
                    )
            )

    private fun buildMigrateCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("migrate")
            .requires { it.sender.hasPermission(ADMIN_PERMISSION) }
            .executes { migrate(it, dryRun = false) }
            .then(
                Commands.literal("dry")
                    .executes { migrate(it, dryRun = true) }
            )

    private fun buildCheckCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("check")
            .requires { it.sender.hasPermission(MODERATOR_PERMISSION) }
            .executes { checkRaycast(it) }
            .then(
                Commands.literal("plot")
                    .executes { checkPlot(it) }
            )
            .then(buildCheckRangeCommand())
            .then(
                Commands.argument("playerName", StringArgumentType.word())
                    .suggests(offlinePlayerSuggestions)
                    .executes { checkPlayer(it, StringArgumentType.getString(it, "playerName")) }
            )

    private fun buildCheckRangeCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("range")
            .executes { checkRange(it, DEFAULT_CHECK_RANGE) }
            .then(
                Commands.argument("range", DoubleArgumentType.doubleArg(0.0))
                    .executes { checkRange(it, DoubleArgumentType.getDouble(it, "range")) }
            )

    private fun buildPopRaycastCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("raycast")
            .executes { popRaycast(it, DEFAULT_POP_RADIUS) }
            .then(
                Commands.argument("radius", DoubleArgumentType.doubleArg(0.0))
                    .executes { popRaycast(it, DoubleArgumentType.getDouble(it, "radius")) }
            )

    private fun buildPopCoordinatesCommand(): ArgumentBuilder<CommandSourceStack, *> =
        Commands.argument("x", DoubleArgumentType.doubleArg())
            .then(
                Commands.argument("y", DoubleArgumentType.doubleArg())
                    .then(
                        Commands.argument("z", DoubleArgumentType.doubleArg())
                            .executes {
                                popCoordinates(
                                    it,
                                    DoubleArgumentType.getDouble(it, "x"),
                                    DoubleArgumentType.getDouble(it, "y"),
                                    DoubleArgumentType.getDouble(it, "z"),
                                    DEFAULT_POP_RADIUS,
                                )
                            }
                            .then(
                                Commands.argument("radius", DoubleArgumentType.doubleArg(0.0))
                                    .executes {
                                        popCoordinates(
                                            it,
                                            DoubleArgumentType.getDouble(it, "x"),
                                            DoubleArgumentType.getDouble(it, "y"),
                                            DoubleArgumentType.getDouble(it, "z"),
                                            DoubleArgumentType.getDouble(it, "radius"),
                                        )
                                    }
                            )
                    )
            )

    private fun lockOrUnlock(context: CommandContext<CommandSourceStack>, lock: Boolean, targetName: String?): Int {
        val sender = context.source.sender
        val targetedPlayer = resolveTargetPlayer(sender, targetName) ?: return 0
        val display = lookupService.findLookedAtGroundItem(targetedPlayer)
            ?: return fail(context, "Target player is not looking at a ground item")

        if (lock) {
            if (persistence.getGroundItemState(display).isLockedLike || lockService.getLockMode(display) != LockService.LockMode.NONE) {
                sender.sendMessage(lockService.formatAlreadyBoundMessage(display.itemStack))
                return Command.SINGLE_SUCCESS
            }
            val lockMode = lockService.lockTarget(sender, display)
            sender.sendMessage(
                lockService.formatBoundMessage(
                    display.itemStack,
                    lockMode == LockService.LockMode.TEMPORARY,
                ),
            )
        } else {
            if (persistence.getGroundItemState(display).isLockedLike) {
                return fail(context, "Sale and payment ground items cannot be unlocked")
            }
            if (lockService.getLockMode(display) == LockService.LockMode.NONE) {
                sender.sendMessage(lockService.formatAlreadyUnboundMessage(display.itemStack))
                return Command.SINGLE_SUCCESS
            }
            if (!lockService.canUnlock(sender, display)) {
                return fail(context, "You may not unlock this ground item")
            }
            lockService.unlockTarget(display)
            sender.sendMessage(lockService.formatUnboundMessage(display.itemStack))
        }

        return Command.SINGLE_SUCCESS
    }

    private fun sell(context: CommandContext<CommandSourceStack>, amount: Int, currencyId: String): Int {
        val player = context.source.sender as? Player ?: return fail(context, "Only players can run /grounditem sell")
        val currency = Currency.fromId(currencyId) ?: return fail(context, "Unknown currency")
        val result = commerceService.sellGroundItem(player, amount, currency)
        if (result.message != null) player.sendMessage(result.message)
        return if (result.success) Command.SINGLE_SUCCESS else 0
    }

    private fun migrate(context: CommandContext<CommandSourceStack>, dryRun: Boolean): Int {
        val player = context.source.sender as? Player ?: return fail(context, "Only players can run /grounditem migrate")
        return if (migrationService.startMigration(player, dryRun)) Command.SINGLE_SUCCESS else 0
    }

    private fun checkRaycast(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.sender as? Player ?: return fail(context, "Only players can run /grounditem check")
        val display = lookupService.findLookedAtGroundItem(player, Constants.CHECK_RAYCAST_DISTANCE)
            ?: return fail(context, "You are not looking at a ground item")

        val stack = display.itemStack
        val creatorName = persistence.getCreatorName(display) ?: UNKNOWN_CREATOR
        context.source.sender.sendMessage(
            Component.text("${stack.amount}x ")
                .append(stack.displayName())
                .append(Component.text(" was placed by $creatorName${statusSuffix(display)}")),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun checkRange(context: CommandContext<CommandSourceStack>, range: Double): Int {
        val player = context.source.sender as? Player ?: return fail(context, "Only players can run /grounditem check range")
        val rangeSquared = range * range
        val center = player.location
        val world = player.world
        val counts = countGroundItems {
            it.world == world && it.location.distanceSquared(center) <= rangeSquared
        }

        context.source.sender.sendMessage(
            "Range contains ${counts.total} ground items (${counts.sellable} sellable, ${counts.locked} locked)",
        )
        return Command.SINGLE_SUCCESS
    }

    private fun checkPlot(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.sender as? Player ?: return fail(context, "Only players can run /grounditem check plot")
        val plot = lockService.findPriorityFivePlotAt(player.location)
            ?: return fail(context, "You are not standing in a plot")
        val world = player.world
        val counts = countGroundItems {
            it.world == world && plot.contains(BlockVector3.at(it.location.blockX, it.location.blockY, it.location.blockZ))
        }

        context.source.sender.sendMessage(
            "${plot.id} contains ${counts.total} ground items (${counts.sellable} sellable, ${counts.locked} locked)",
        )
        return Command.SINGLE_SUCCESS
    }

    private fun checkPlayer(context: CommandContext<CommandSourceStack>, playerName: String): Int {
        val resolvedName = resolveOfflinePlayerName(playerName)
        val counts = countGroundItems {
            persistence.getCreatorName(it)?.equals(resolvedName, ignoreCase = true) == true
        }

        context.source.sender.sendMessage(
            "$resolvedName placed ${counts.total} grounditems (${counts.sellable} sellable, ${counts.locked} locked)",
        )
        return Command.SINGLE_SUCCESS
    }

    private fun popRaycast(context: CommandContext<CommandSourceStack>, radius: Double): Int {
        val player = context.source.sender as? Player ?: return 0
        val center = lookupService.findPopLocation(player) ?: return Command.SINGLE_SUCCESS
        interactionService.popGroundItems(center, radius)
        return Command.SINGLE_SUCCESS
    }

    private fun popCoordinates(
        context: CommandContext<CommandSourceStack>,
        x: Double,
        y: Double,
        z: Double,
        radius: Double,
    ): Int {
        val player = context.source.sender as? Player ?: return 0
        val center = player.location.clone().apply {
            this.x = x
            this.y = y
            this.z = z
        }
        interactionService.popGroundItems(center, radius)
        return Command.SINGLE_SUCCESS
    }

    private fun resolveTargetPlayer(sender: CommandSender, targetName: String?): Player? {
        if (targetName != null) {
            return Bukkit.getPlayer(targetName) ?: run {
                sender.sendMessage("Player not found")
                null
            }
        }

        return sender as? Player ?: run {
            sender.sendMessage("Only players can use this form of /grounditem")
            null
        }
    }

    private fun countGroundItems(predicate: (ItemDisplay) -> Boolean): GroundItemAuditCounts {
        return loadedGroundItemDisplays()
            .filter(predicate)
            .fold(GroundItemAuditCounts()) { counts, display ->
                val sellable = isSellable(display)
                val locked = isLocked(display)
                GroundItemAuditCounts(
                    total = counts.total + 1,
                    sellable = counts.sellable + if (sellable) 1 else 0,
                    locked = counts.locked + if (locked) 1 else 0,
                )
            }
    }

    private fun loadedGroundItemDisplays(): Sequence<ItemDisplay> {
        return Bukkit.getWorlds().asSequence()
            .flatMap { world -> world.loadedChunks.asSequence() }
            .flatMap { chunk -> chunk.entities.asSequence() }
            .filterIsInstance<ItemDisplay>()
            .filter(persistence::isGroundItemDisplay)
    }

    private fun isSellable(display: ItemDisplay): Boolean {
        return persistence.getGroundItemState(display) == GroundItemState.SALE
    }

    private fun isLocked(display: ItemDisplay): Boolean {
        return persistence.getGroundItemState(display) == GroundItemState.PAYMENT ||
            lockService.getLockMode(display) != LockService.LockMode.NONE
    }

    private fun statusSuffix(display: ItemDisplay): String {
        val suffixes = buildList {
            if (isSellable(display)) add("(sellable)")
            if (isLocked(display)) add("(locked)")
        }
        return if (suffixes.isEmpty()) "" else " ${suffixes.joinToString(" ")}"
    }

    private fun resolveOfflinePlayerName(playerName: String): String {
        return Bukkit.getOfflinePlayers()
            .firstOrNull { it.name?.equals(playerName, ignoreCase = true) == true }
            ?.name
            ?: playerName
    }

    private fun fail(context: CommandContext<CommandSourceStack>, message: String): Int {
        context.source.sender.sendMessage(message)
        return 0
    }

    private fun suggest(values: Iterable<String>, builder: SuggestionsBuilder) = builder.apply {
        for (value in values) {
            if (value.lowercase().startsWith(remainingLowerCase)) {
                suggest(value)
            }
        }
    }.buildFuture()

    private fun CommandSender.hasAnyPermission(vararg permissions: String): Boolean = permissions.any(::hasPermission)

    companion object {
        const val DEFAULT_POP_RADIUS = 2.5
        const val DEFAULT_CHECK_RANGE = 5.0
        const val LOCK_PERMISSION = "sneakygrounditems.command.lock"
        const val UNLOCK_PERMISSION = "sneakygrounditems.command.unlock"
        const val POP_PERMISSION = "sneakygrounditems.command.pop"
        const val SELL_PERMISSION = "sneakygrounditems.command.sell"
        const val ADMIN_PERMISSION = "sneakygrounditems.command.admin"
        const val MODERATOR_PERMISSION = "sneakygrounditems.command.moderator"
        const val MAX_SELL_PRICE = 999_999_999
        const val UNKNOWN_CREATOR = "unknown"
    }

    private data class GroundItemAuditCounts(
        val total: Int = 0,
        val sellable: Int = 0,
        val locked: Int = 0,
    )
}

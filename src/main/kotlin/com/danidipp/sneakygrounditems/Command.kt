package com.danidipp.sneakygrounditems

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

internal class Command(
    private val manager: GroundItemManager,
    private val lockService: LockService,
) {
    private val playerSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        suggest(Bukkit.getOnlinePlayers().map { it.name }.sorted(), builder)
    }

    fun build(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("grounditem")
            .requires { source ->
                source.sender.hasAnyPermission(LOCK_PERMISSION, UNLOCK_PERMISSION, POP_PERMISSION, ADMIN_PERMISSION)
            }
            .executes { fail(it, "Missing subcommand. Usage: /grounditem <lock|unlock|pop|migrate> ...") }
            .then(buildLockCommand())
            .then(buildUnlockCommand())
            .then(buildPopCommand())
            .then(buildMigrateCommand())
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

    private fun buildMigrateCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("migrate")
            .requires { it.sender.hasPermission(ADMIN_PERMISSION) }
            .executes { migrate(it, dryRun = false) }
            .then(
                Commands.literal("dry")
                    .executes { migrate(it, dryRun = true) }
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
        val display = manager.findLookedAtGroundItem(targetedPlayer)
            ?: return fail(context, "Target player is not looking at a ground item")

        if (lock) {
            if (lockService.getLockMode(display) != LockService.LockMode.NONE) {
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

    private fun migrate(context: CommandContext<CommandSourceStack>, dryRun: Boolean): Int {
        val player = context.source.sender as? Player ?: return fail(context, "Only players can run /grounditem migrate")
        return if (manager.startMigration(player, dryRun)) Command.SINGLE_SUCCESS else 0
    }

    private fun popRaycast(context: CommandContext<CommandSourceStack>, radius: Double): Int {
        val player = context.source.sender as? Player ?: return 0
        val center = manager.findPopLocation(player) ?: return Command.SINGLE_SUCCESS
        manager.popGroundItems(center, radius)
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
        manager.popGroundItems(center, radius)
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
        const val LOCK_PERMISSION = "sneakygrounditems.command.lock"
        const val UNLOCK_PERMISSION = "sneakygrounditems.command.unlock"
        const val POP_PERMISSION = "sneakygrounditems.command.pop"
        const val ADMIN_PERMISSION = "sneakygrounditems.command.admin"
    }
}

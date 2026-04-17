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

internal class GroundItemCommand(
    private val manager: GroundItemManager,
    private val lockService: GroundItemLockService,
) {
    private val playerSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        suggest(Bukkit.getOnlinePlayers().map { it.name }.sorted(), builder)
    }

    fun build(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("grounditem")
            .requires { source ->
                GroundItemPermissions.hasLockPermission(source.sender) ||
                    GroundItemPermissions.hasUnlockPermission(source.sender) ||
                    GroundItemPermissions.hasPopPermission(source.sender) ||
                    GroundItemPermissions.hasAdminPermission(source.sender)
            }
            .executes { fail(it, "Missing subcommand. Usage: /grounditem <lock|unlock|pop> ...") }
            .then(buildLockCommand())
            .then(buildUnlockCommand())
            .then(buildPopCommand())
            .build()

    private fun buildLockCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("lock")
            .requires {
                GroundItemPermissions.hasLockPermission(it.sender) ||
                    GroundItemPermissions.hasAdminPermission(it.sender)
            }
            .executes { lockOrUnlock(it, lock = true, targetName = null) }
            .then(
                Commands.argument("playerName", StringArgumentType.word())
                    .suggests(playerSuggestions)
                    .executes { lockOrUnlock(it, lock = true, targetName = StringArgumentType.getString(it, "playerName")) }
            )

    private fun buildUnlockCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("unlock")
            .requires {
                GroundItemPermissions.hasUnlockPermission(it.sender) ||
                    GroundItemPermissions.hasAdminPermission(it.sender)
            }
            .executes { lockOrUnlock(it, lock = false, targetName = null) }
            .then(
                Commands.argument("playerName", StringArgumentType.word())
                    .suggests(playerSuggestions)
                    .executes { lockOrUnlock(it, lock = false, targetName = StringArgumentType.getString(it, "playerName")) }
            )

    private fun buildPopCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("pop")
            .requires { GroundItemPermissions.hasPopPermission(it.sender) }
            .then(buildPopRaycastCommand())
            .then(buildPopCoordinatesCommand())

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
        val lookedAtInteraction = manager.findLookedAtInteraction(targetedPlayer)
            ?: return fail(context, "Target player is not looking at a ground item")
        val target = manager.resolveGroundItemTarget(lookedAtInteraction)
            ?: return fail(context, "Ground item pair is invalid")

        if (lock) {
            if (lockService.getLockMode(target) != LockMode.NONE) {
                sender.sendMessage(lockService.formatAlreadyBoundMessage(target.display.itemStack))
                return Command.SINGLE_SUCCESS
            }
            val lockMode = lockService.lockTarget(sender, target)
            sender.sendMessage(lockService.formatBoundMessage(target.display.itemStack, lockMode == LockMode.TEMPORARY))
        } else {
            if (lockService.getLockMode(target) == LockMode.NONE) {
                sender.sendMessage(lockService.formatAlreadyUnboundMessage(target.display.itemStack))
                return Command.SINGLE_SUCCESS
            }
            if (!lockService.canUnlock(sender, target)) {
                return fail(context, "You may not unlock this ground item")
            }
            lockService.unlockTarget(target)
            sender.sendMessage(lockService.formatUnboundMessage(target.display.itemStack))
        }

        return Command.SINGLE_SUCCESS
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

    private companion object {
        const val DEFAULT_POP_RADIUS = 2.5
    }
}

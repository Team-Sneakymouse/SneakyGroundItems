package com.danidipp.sneakygrounditems.grounditems

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

class GroundItemCommand(
    private val module: GroundItemsModule,
) {
    private val playerSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        suggest(Bukkit.getOnlinePlayers().map { it.name }.sorted(), builder)
    }

    fun build(): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("grounditem")
            .requires { source ->
                source.sender.hasAnyPermission(LOCK_PERMISSION, LEGACY_LOCK_PERMISSION) ||
                    source.sender.hasAnyPermission(UNLOCK_PERMISSION, LEGACY_UNLOCK_PERMISSION) ||
                    source.sender.hasAnyPermission(POP_PERMISSION, LEGACY_POP_PERMISSION) ||
                    GroundItemsModule.hasAdminPermission(source.sender)
            }
            .executes { fail(it, "Missing subcommand. Usage: /grounditem <lock|unlock|pop> ...") }
            .then(buildLockCommand())
            .then(buildUnlockCommand())
            .then(buildPopCommand())
            .build()

    private fun buildLockCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("lock")
            .requires {
                it.sender.hasAnyPermission(LOCK_PERMISSION, LEGACY_LOCK_PERMISSION) ||
                    GroundItemsModule.hasAdminPermission(it.sender)
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
                it.sender.hasAnyPermission(UNLOCK_PERMISSION, LEGACY_UNLOCK_PERMISSION) ||
                    GroundItemsModule.hasAdminPermission(it.sender)
            }
            .executes { lockOrUnlock(it, lock = false, targetName = null) }
            .then(
                Commands.argument("playerName", StringArgumentType.word())
                    .suggests(playerSuggestions)
                    .executes { lockOrUnlock(it, lock = false, targetName = StringArgumentType.getString(it, "playerName")) }
            )

    private fun buildPopCommand(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("pop")
            .requires { it.sender.hasAnyPermission(POP_PERMISSION, LEGACY_POP_PERMISSION) }
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
        val lookedAtInteraction = module.findLookedAtInteraction(targetedPlayer)
            ?: return fail(context, "Target player is not looking at a ground item")
        val target = module.resolveGroundItemTarget(lookedAtInteraction)
            ?: return fail(context, "Ground item pair is invalid")

        if (lock) {
            if (module.getLockMode(target) != GroundItemsModule.LockMode.NONE) {
                sender.sendMessage(module.formatAlreadyBoundMessage(target.display.itemStack))
                return Command.SINGLE_SUCCESS
            }
            val lockMode = module.lockTarget(sender, target)
            sender.sendMessage(module.formatBoundMessage(target.display.itemStack, lockMode == GroundItemsModule.LockMode.TEMPORARY))
        } else {
            if (module.getLockMode(target) == GroundItemsModule.LockMode.NONE) {
                sender.sendMessage(module.formatAlreadyUnboundMessage(target.display.itemStack))
                return Command.SINGLE_SUCCESS
            }
            if (!module.canUnlock(sender, target)) {
                return fail(context, "You may not unlock this ground item")
            }
            module.unlockTarget(target)
            sender.sendMessage(module.formatUnboundMessage(target.display.itemStack))
        }

        return Command.SINGLE_SUCCESS
    }

    private fun popRaycast(context: CommandContext<CommandSourceStack>, radius: Double): Int {
        val player = context.source.sender as? Player ?: return 0
        val center = module.findPopLocation(player) ?: return Command.SINGLE_SUCCESS
        module.popGroundItems(center, radius)
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
        module.popGroundItems(center, radius)
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

    private companion object {
        const val LOCK_PERMISSION = "sneakygrounditems.command.grounditem.lock"
        const val UNLOCK_PERMISSION = "sneakygrounditems.command.grounditem.unlock"
        const val POP_PERMISSION = "sneakygrounditems.command.grounditem.pop"

        const val LEGACY_LOCK_PERMISSION = "sneakymisc.command.grounditem.lock"
        const val LEGACY_UNLOCK_PERMISSION = "sneakymisc.command.grounditem.unlock"
        const val LEGACY_POP_PERMISSION = "sneakymisc.command.grounditem.pop"

        const val DEFAULT_POP_RADIUS = 2.5
    }
}

package com.danidipp.sneakygrounditems

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.LocalDate
import java.time.ZoneId

internal class LockService(
    private val persistence: PersistenceService,
) {
    enum class LockMode {
        NONE,
        TEMPORARY,
        PERMANENT,
    }

    private val lockZone = ZoneId.of("UTC-07:00")

    fun lockTarget(actor: CommandSender, display: ItemDisplay): LockMode {
        return when (determineLockMode(actor, display.location)) {
            LockMode.PERMANENT -> {
                persistence.setPermanentLock(display)
                LockMode.PERMANENT
            }

            else -> {
                persistence.setTemporaryLock(display, todayLockDate())
                LockMode.TEMPORARY
            }
        }
    }

    fun getLockMode(display: ItemDisplay): LockMode {
        return persistence.getLockMode(display)
    }

    fun canUnlock(actor: CommandSender, display: ItemDisplay): Boolean {
        if (actor.hasPermission(Command.ADMIN_PERMISSION)) return true

        val player = actor as? Player ?: return false
        return isPlotMember(player, display.location)
    }

    fun unlockTarget(display: ItemDisplay) {
        persistence.clearLock(display)
    }

    fun clearExpiredTemporaryLock(display: ItemDisplay) {
        val temporaryDate = persistence.getTemporaryLockDate(display) ?: return
        if (temporaryDate == todayLockDate()) return

        persistence.clearTemporaryLock(display)
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

    private fun determineLockMode(actor: CommandSender, location: Location): LockMode {
        val player = actor as? Player
        if (player != null && isPlotMember(player, location)) {
            return LockMode.PERMANENT
        }
        if (actor.hasPermission(Command.ADMIN_PERMISSION) && player?.gameMode == GameMode.CREATIVE) {
            return LockMode.PERMANENT
        }
        return LockMode.TEMPORARY
    }

    fun isPlotMember(player: Player, location: Location): Boolean {
        val world = location.world ?: return false
        val worldGuardPlugin = Bukkit.getPluginManager().getPlugin("WorldGuard") as? WorldGuardPlugin ?: return false
        val regionManager = WorldGuard.getInstance().platform.regionContainer[BukkitAdapter.adapt(world)] ?: return false
        val applicableRegions = regionManager.getApplicableRegions(BlockVector3.at(location.x, location.y, location.z))
        val worldGuardPlayer = worldGuardPlugin.wrapPlayer(player)

        return applicableRegions.any { region ->
            region.priority == 5 && (region.isOwner(worldGuardPlayer) || region.isMember(worldGuardPlayer))
        }
    }

    private fun todayLockDate(): String {
        return LocalDate.now(lockZone).toString()
    }
}

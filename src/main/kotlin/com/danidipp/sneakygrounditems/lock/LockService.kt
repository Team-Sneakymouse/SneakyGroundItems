package com.danidipp.sneakygrounditems.lock

import com.danidipp.sneakygrounditems.command.Command
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import net.kyori.adventure.text.Component
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
    private val plots: PlotLookup,
) {
    enum class LockMode {
        NONE,
        TEMPORARY,
        PLOT,
        PERMANENT,
    }

    enum class Outcome {
        /** The lock state changed. */
        CHANGED,

        /** Nothing to do: already in the requested state. */
        UNCHANGED,

        /** Not allowed on this item or for this actor. */
        DENIED,
    }

    data class Result(val outcome: Outcome, val message: Component)

    private val lockZone = ZoneId.of("UTC-07:00")

    /** Locks a normal ground item, or turns an existing lock into a showoff lock when [showoff] is set. */
    fun lock(actor: CommandSender, display: ItemDisplay, showoff: Boolean = false): Result {
        val stack = display.itemStack
        if (persistence.getGroundItemState(display).isLockedLike) {
            return Result(Outcome.UNCHANGED, formatAlreadyBoundMessage(stack))
        }
        if (getLockMode(display) != LockMode.NONE) {
            return when {
                !showoff -> Result(Outcome.UNCHANGED, formatAlreadyBoundMessage(stack))
                isShowoff(display) -> Result(Outcome.UNCHANGED, formatAlreadyShowoffMessage(stack))
                else -> {
                    showOff(display)
                    Result(Outcome.CHANGED, formatShowoffMessage(stack))
                }
            }
        }
        val mode = lockTarget(actor, display, showoff)
        return Result(Outcome.CHANGED, formatBoundMessage(stack, mode == LockMode.TEMPORARY, showoff))
    }

    fun unlock(actor: CommandSender, display: ItemDisplay): Result {
        val stack = display.itemStack
        if (persistence.getGroundItemState(display).isLockedLike) {
            return Result(Outcome.DENIED, Component.text("Sale, payment, and stolen ground items cannot be unlocked"))
        }
        if (getLockMode(display) == LockMode.NONE) {
            return Result(Outcome.UNCHANGED, formatAlreadyUnboundMessage(stack))
        }
        if (!canUnlock(actor, display)) {
            return Result(Outcome.DENIED, Component.text("You may not unlock this ground item"))
        }
        unlockTarget(display)
        return Result(Outcome.CHANGED, formatUnboundMessage(stack))
    }

    fun lockTarget(actor: CommandSender, display: ItemDisplay, showoff: Boolean = false): LockMode {
        persistence.setShowoff(display, showoff)
        return when (determineLockMode(actor, display.location)) {
            LockMode.PERMANENT -> {
                persistence.setPermanentLock(display)
                LockMode.PERMANENT
            }

            LockMode.PLOT -> {
                persistence.setPlotLock(display)
                LockMode.PLOT
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

    fun isShowoff(display: ItemDisplay): Boolean {
        return persistence.isShowoff(display)
    }

    /** Turns an existing lock into a showoff lock without changing its mode. */
    fun showOff(display: ItemDisplay) {
        persistence.setShowoff(display, true)
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
        persistence.setShowoff(display, false)
    }

    fun clearInvalidPlotLock(display: ItemDisplay) {
        if (persistence.getLockMode(display) != LockMode.PLOT) return
        // Unknown plots (SneakyPlots missing or still loading) must not read as "no plot here" and wipe plot locks.
        if (!plots.isReady) return
        if (plotIdAt(display.location) != null) return

        persistence.clearLock(display)
    }

    fun formatBoundMessage(stack: ItemStack, temporary: Boolean, showoff: Boolean = false): Component {
        val suffix = when {
            showoff && temporary -> " is now on show for the rest of the day"
            showoff -> " is now on show"
            temporary -> " is now bound in place for the rest of the day"
            else -> " is now bound in place"
        }
        return stack.displayName().append(Component.text(suffix))
    }

    fun formatShowoffMessage(stack: ItemStack): Component {
        return stack.displayName().append(Component.text(" is now on show"))
    }

    fun formatAlreadyShowoffMessage(stack: ItemStack): Component {
        return stack.displayName().append(Component.text(" is already on show"))
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
            return LockMode.PLOT
        }
        if (actor.hasPermission(Command.ADMIN_PERMISSION) && player?.gameMode == GameMode.CREATIVE) {
            return LockMode.PERMANENT
        }
        return LockMode.TEMPORARY
    }

    fun isPlotMember(player: Player, location: Location): Boolean {
        val plotId = plotIdAt(location) ?: return false
        return plots.isMember(player, plotId)
    }

    /** The SneakyPlots plot containing [location], or null outside plots and while plots aren't known. */
    fun plotIdAt(location: Location): String? = plots.plotIdAt(location)

    private fun todayLockDate(): String {
        return LocalDate.now(lockZone).toString()
    }
}

package com.danidipp.sneakygrounditems.pricegun

import com.danidipp.sneakygrounditems.commerce.GroundItemCommerceService
import com.danidipp.sneakygrounditems.config.Constants
import com.danidipp.sneakygrounditems.interaction.GroundItemLookupService
import com.danidipp.sneakygrounditems.lock.LockService
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import com.sneakyitems.api.HeldSlot
import com.sneakyitems.api.ItemWatch
import com.sneakyitems.api.SneakyItemsAPI
import com.sneakyitems.api.WatchMembership
import com.sneakyutils.triggers.PlayerTimerOptions
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * The Price Gun (`item-pricegun`): right-click fires the selected mode at the ground item you look at, up to
 * [Constants.PRICE_GUN_RANGE] blocks away; sneak-right-click on an item for sale copies its price into the gun; F opens
 * [PriceGunMenu]. While held, the action bar shows the mode and price. Settings live on each gun ([PriceGunSettings]).
 */
internal class PriceGunService(
    private val plugin: Plugin,
    private val persistence: PersistenceService,
    private val lookupService: GroundItemLookupService,
    private val commerceService: GroundItemCommerceService,
    private val lockService: LockService,
    val menu: PriceGunMenu = PriceGunMenu(),
) : AutoCloseable {
    private val lastShotAt = HashMap<UUID, Long>()
    /** Until when the action bar shows a shot's result instead of the held-gun status. */
    private val feedbackUntil = HashMap<UUID, Long>()
    private var watch: ItemWatch? = null

    fun start() {
        watch = SneakyItemsAPI.whileHolding(
            plugin, ITEM_ID, HeldSlot.MAIN_HAND, STATUS_INTERVAL_TICKS, ::showStatus,
            PlayerTimerOptions.builder().label("sneakygrounditems:pricegun-status").build(),
            object : WatchMembership {
                override fun entered(player: Player) = showStatus(player)

                override fun left(player: Player) {
                    feedbackUntil.remove(player.uniqueId)
                    lastShotAt.remove(player.uniqueId)
                }
            },
        )
    }

    override fun close() {
        watch?.close()
        watch = null
        lastShotAt.clear()
        feedbackUntil.clear()
    }

    /** Fires the gun in the player's main hand. */
    fun fire(player: Player) {
        val now = System.currentTimeMillis()
        if (now - (lastShotAt[player.uniqueId] ?: 0L) < SHOT_COOLDOWN_MS) return
        lastShotAt[player.uniqueId] = now

        val gunSlot = player.inventory.heldItemSlot
        PriceGunSettings.unstack(player, gunSlot)
        val gun = PriceGunSettings.gunAt(player, gunSlot) ?: return
        val settings = PriceGunSettings.of(gun)
        val mode = settings.mode
        val display = lookupService.findLookedAtGroundItem(player, Constants.PRICE_GUN_RANGE)
        if (display == null) {
            PriceGunEffects.shot(plugin, player, mode, missPoint(player), PriceGunEffects.Impact.FIZZLE)
            return
        }

        if (player.isSneaking && persistence.getGroundItemState(display) == GroundItemState.SALE && sample(player, gunSlot, display)) {
            return
        }

        val (impact, message) = when (val currency = mode.currency) {
            null -> {
                val result = when (mode) {
                    PriceGunMode.UNLOCK -> lockService.unlock(player, display)
                    else -> lockService.lock(player, display, showoff = mode == PriceGunMode.SHOWOFF)
                }
                val impact = when (result.outcome) {
                    LockService.Outcome.CHANGED -> PriceGunEffects.Impact.HIT
                    LockService.Outcome.UNCHANGED -> PriceGunEffects.Impact.UNCHANGED
                    LockService.Outcome.DENIED -> PriceGunEffects.Impact.FIZZLE
                }
                impact to result.message
            }

            else -> {
                val result = commerceService.sellGroundItem(player, display, settings.price(currency), currency)
                (if (result.success) PriceGunEffects.Impact.HIT else PriceGunEffects.Impact.FIZZLE) to result.message
            }
        }
        PriceGunEffects.shot(plugin, player, mode, display.location, impact)
        if (message != null) feedback(player, message)
    }

    fun openMenu(player: Player) {
        PriceGunSettings.unstack(player, player.inventory.heldItemSlot)
        menu.open(player, player.inventory.heldItemSlot)
    }

    /** Copies a sale item's price and currency into the gun. False when the item's sale data can't be read. */
    private fun sample(player: Player, gunSlot: Int, display: ItemDisplay): Boolean {
        val currency = persistence.getCurrency(display) ?: return false
        val mode = PriceGunMode.forCurrency(currency) ?: return false
        val price = persistence.getPrice(display) ?: return false
        val stored = PriceGunSettings.update(player, gunSlot) {
            it.withMode(mode).withPrice(currency, price.toLong())
        }?.price(currency) ?: return false
        PriceGunEffects.sample(plugin, player, mode, display.location)
        feedback(
            player,
            Component.text("Copied price: ", NamedTextColor.GRAY)
                .append(Component.text("$stored $currency", NamedTextColor.YELLOW)),
        )
        return true
    }

    private fun feedback(player: Player, message: Component) {
        feedbackUntil[player.uniqueId] = System.currentTimeMillis() + FEEDBACK_HOLD_MS
        player.sendActionBar(message)
    }

    private fun showStatus(player: Player) {
        val until = feedbackUntil[player.uniqueId]
        if (until != null) {
            if (System.currentTimeMillis() < until) return
            feedbackUntil.remove(player.uniqueId)
        }
        player.sendActionBar(status(player))
    }

    private fun status(player: Player): Component {
        val settings = PriceGunSettings.of(player.inventory.itemInMainHand)
        val mode = settings.mode
        val label = Component.text("Price Gun: ", NamedTextColor.GRAY).append(Component.text(mode.label, mode.color))
        val currency = mode.currency ?: return label
        return label
            .append(Component.text(" · ", NamedTextColor.DARK_GRAY))
            .append(Component.text("${settings.price(currency)} $currency", NamedTextColor.YELLOW))
    }

    private fun missPoint(player: Player): Location {
        val eye = player.eyeLocation
        val hit = player.world.rayTraceBlocks(eye, eye.direction, Constants.PRICE_GUN_RANGE, FluidCollisionMode.NEVER, true)
        return hit?.hitPosition?.toLocation(player.world)
            ?: eye.clone().add(eye.direction.multiply(Constants.PRICE_GUN_RANGE))
    }

    companion object {
        const val ITEM_ID = "item-pricegun"
        private const val SHOT_COOLDOWN_MS = 200L
        private const val FEEDBACK_HOLD_MS = 2_500L
        private const val STATUS_INTERVAL_TICKS = 20
    }
}

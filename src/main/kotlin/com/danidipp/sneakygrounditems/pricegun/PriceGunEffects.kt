package com.danidipp.sneakygrounditems.pricegun

import io.papermc.paper.datacomponent.DataComponentTypes
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Beams, impacts and sounds of the Price Gun. Everything is cosmetic; the shot's effect is applied before. */
internal object PriceGunEffects {
    enum class Impact {
        /** The shot changed the item. */
        HIT,

        /** The item was already in that state. */
        UNCHANGED,

        /** Refused, or nothing was hit. */
        FIZZLE,
    }

    private const val BEAM_STEP = 0.3
    private const val BEAM_TICKS = 3
    private const val FIRE_SOUND = "lom:arcane.lobshot"
    private const val PRICE_SOUND = "lom:sfx.pricegun"
    private const val STAPLE_SOUND = "lom:ui.staple"
    private const val CLICK_SOUND = "lom:ui.button.click"

    /** Where the beam leaves the gun: in front of the right hand. */
    fun muzzle(player: Player): Location {
        val eye = player.eyeLocation
        val direction = eye.direction
        val right = direction.clone().crossProduct(Vector(0, 1, 0))
        if (right.lengthSquared() > 1.0E-6) right.normalize().multiply(0.3)
        return eye.add(direction.multiply(0.6)).add(right).add(0.0, -0.25, 0.0)
    }

    /** Fires a beam from the gun to [target] over a few ticks, then plays the impact for [mode]. */
    fun shot(plugin: Plugin, player: Player, mode: PriceGunMode, target: Location, impact: Impact) {
        val from = muzzle(player)
        player.world.playSound(from, FIRE_SOUND, SoundCategory.PLAYERS, 0.3f, mode.firePitch)
        beam(plugin, from, target, mode.beam) { impact(mode, target, impact) }
    }

    /** A beam travelling from [target] back into the gun: the sampled price flows into it. */
    fun sample(plugin: Plugin, player: Player, mode: PriceGunMode, target: Location) {
        val to = muzzle(player)
        burst(target, mode.beam.first(), 8, 0.15)
        player.playSound(player.location, STAPLE_SOUND, SoundCategory.PLAYERS, 0.8f, 1.4f)
        beam(plugin, target, to, mode.beam) {
            player.playSound(player.location, PRICE_SOUND, SoundCategory.PLAYERS, 0.4f, 1.6f)
        }
    }

    fun playPriceSet(player: Player) {
        player.playSound(player.location, PRICE_SOUND, SoundCategory.MASTER, 0.5f, 1.0f)
    }

    fun playModeSelect(player: Player) {
        player.playSound(player.location, STAPLE_SOUND, SoundCategory.MASTER, 1.0f, 1.0f)
    }

    /** Stepper clicks rise in pitch with the step, as in the prototype (0.6 for -1000 up to 1.7 for +1000). */
    fun playStep(player: Player, step: Int) {
        val pitch = when (step) {
            -1000 -> 0.6f
            -100 -> 0.7f
            -10 -> 0.8f
            -1 -> 0.9f
            1 -> 1.1f
            10 -> 1.3f
            100 -> 1.5f
            else -> 1.7f
        }
        player.playSound(player.location, CLICK_SOUND, SoundCategory.MASTER, 1.0f, pitch)
    }

    private fun beam(plugin: Plugin, from: Location, to: Location, colors: List<Color>, onArrive: () -> Unit) {
        val world = from.world ?: return
        val path = to.toVector().subtract(from.toVector())
        val length = path.length()
        val steps = max(1, (length / BEAM_STEP).toInt())
        val step = if (length > 0) path.multiply(1.0 / steps) else Vector()
        val perTick = (steps + BEAM_TICKS - 1) / BEAM_TICKS

        fun draw(tick: Int) {
            val start = tick * perTick
            val end = minOf(steps, start + perTick)
            for (i in start..end) {
                val point = from.clone().add(step.clone().multiply(i))
                val color = colors[i % colors.size]
                world.spawnParticle(
                    Particle.DUST_COLOR_TRANSITION, point, 1, 0.02, 0.02, 0.02, 0.0,
                    Particle.DustTransition(color, Color.BLACK, 0.8f),
                )
            }
        }

        draw(0)
        if (BEAM_TICKS <= 1) {
            onArrive()
            return
        }
        for (tick in 1 until BEAM_TICKS) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                draw(tick)
                if (tick == BEAM_TICKS - 1) onArrive()
            }, tick.toLong())
        }
    }

    private fun impact(mode: PriceGunMode, at: Location, impact: Impact) {
        val world = at.world ?: return
        when (impact) {
            Impact.FIZZLE -> {
                world.spawnParticle(Particle.SMOKE, at, 8, 0.08, 0.08, 0.08, 0.01)
                world.playSound(at, Sound.BLOCK_DISPENSER_FAIL, SoundCategory.PLAYERS, 0.4f, 1.6f)
            }

            Impact.UNCHANGED -> ring(at, mode.beam.first(), 0.3, 10)

            Impact.HIT -> when (mode) {
                PriceGunMode.GOLD, PriceGunMode.SILVER, PriceGunMode.PENNY -> {
                    val coin = ItemStack(Material.PAPER).apply {
                        setData(DataComponentTypes.ITEM_MODEL, mode.currency!!.itemModel)
                    }
                    world.spawnParticle(Particle.ITEM, at.clone().add(0.0, 0.15, 0.0), 10, 0.1, 0.05, 0.1, 0.12, coin)
                    ring(at, mode.beam.first(), 0.4, 14)
                }

                PriceGunMode.LOCK -> {
                    ring(at, mode.beam.first(), 0.45, 16)
                    ring(at.clone().add(0.0, 0.12, 0.0), mode.beam.first(), 0.3, 10)
                    world.playSound(at, STAPLE_SOUND, SoundCategory.PLAYERS, 0.8f, 0.9f)
                }

                PriceGunMode.UNLOCK -> {
                    ring(at, mode.beam.first(), 0.45, 16)
                    world.spawnParticle(Particle.CLOUD, at.clone().add(0.0, 0.1, 0.0), 4, 0.1, 0.05, 0.1, 0.02)
                    world.playSound(at, STAPLE_SOUND, SoundCategory.PLAYERS, 0.8f, 1.3f)
                }

                PriceGunMode.SHOWOFF -> {
                    world.spawnParticle(Particle.END_ROD, at.clone().add(0.0, 0.1, 0.0), 8, 0.12, 0.05, 0.12, 0.04)
                    world.spawnParticle(Particle.WAX_ON, at.clone().add(0.0, 0.3, 0.0), 10, 0.25, 0.2, 0.25, 0.0)
                    world.playSound(at, PRICE_SOUND, SoundCategory.PLAYERS, 0.5f, 1.6f)
                }
            }
        }
    }

    private fun ring(center: Location, color: Color, radius: Double, points: Int) {
        val world = center.world ?: return
        val dust = Particle.DustOptions(color, 0.7f)
        for (i in 0 until points) {
            val angle = 2 * PI * i / points
            world.spawnParticle(
                Particle.DUST, center.clone().add(radius * cos(angle), 0.05, radius * sin(angle)), 1, 0.0, 0.0, 0.0, 0.0, dust,
            )
        }
    }

    private fun burst(center: Location, color: Color, count: Int, spread: Double) {
        center.world?.spawnParticle(Particle.DUST, center, count, spread, spread, spread, 0.0, Particle.DustOptions(color, 0.9f))
    }
}

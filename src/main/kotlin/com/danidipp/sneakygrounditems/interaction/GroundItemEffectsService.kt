package com.danidipp.sneakygrounditems.interaction

import com.danidipp.sneakygrounditems.runtime.GroundItemRuntimeStateService
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import java.util.UUID

internal class GroundItemEffectsService(
    private val runtimeState: GroundItemRuntimeStateService,
) {
    fun playFeedback(playerId: UUID, location: Location, sound: String) {
        location.world.spawnParticle(Particle.WHITE_SMOKE, location, 1, 0.0, 0.0, 0.0, 0.0)
        if (!runtimeState.shouldPlayFeedback(playerId)) return

        location.world.playSound(location, sound, SoundCategory.MASTER, 0.25f, 1.75f)
    }

    fun playUiSound(player: Player, sound: String) {
        player.world.playSound(player.location, sound, SoundCategory.MASTER, 0.5f, 1.0f)
    }

    fun playNearbySound(location: Location, sound: String) {
        location.world.playSound(location, sound, SoundCategory.MASTER, 0.5f, 1.0f)
    }
}

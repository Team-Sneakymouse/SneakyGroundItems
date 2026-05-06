package com.danidipp.sneakygrounditems.runtime

import com.danidipp.sneakygrounditems.config.Constants

import java.util.UUID

internal class GroundItemRuntimeStateService {
    private val pendingResolutionDisplayIds = mutableSetOf<UUID>()
    private val playerStateById = mutableMapOf<UUID, PlayerRuntimeState>()

    fun clearPlayerState(playerId: UUID) {
        playerStateById.remove(playerId)
    }

    fun canCreateGroundItem(playerId: UUID, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val state = playerStateById[playerId] ?: return true
        return nowMillis - state.lastCreatedAt >= Constants.ACTION_COOLDOWN_MS
    }

    fun markGroundItemCreated(playerId: UUID, nowMillis: Long = System.currentTimeMillis()) {
        val state = playerStateById.getOrPut(playerId, ::PlayerRuntimeState)
        state.lastCreatedAt = nowMillis
    }

    fun shouldPlayFeedback(playerId: UUID, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val state = playerStateById.getOrPut(playerId, ::PlayerRuntimeState)
        if (nowMillis - state.lastFeedbackAt < Constants.ACTION_COOLDOWN_MS) return false

        state.lastFeedbackAt = nowMillis
        return true
    }

    fun tryBeginDisplayResolution(displayUuid: UUID): Boolean {
        return pendingResolutionDisplayIds.add(displayUuid)
    }

    fun finishDisplayResolution(displayUuid: UUID) {
        pendingResolutionDisplayIds.remove(displayUuid)
    }

    fun isResolutionPending(displayUuid: UUID): Boolean {
        return displayUuid in pendingResolutionDisplayIds
    }

    private data class PlayerRuntimeState(
        var lastCreatedAt: Long = 0L,
        var lastFeedbackAt: Long = 0L,
    )
}

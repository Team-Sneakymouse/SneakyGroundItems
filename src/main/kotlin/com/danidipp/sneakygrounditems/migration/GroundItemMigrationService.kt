package com.danidipp.sneakygrounditems.migration

import com.danidipp.sneakygrounditems.SneakyGroundItems
import com.danidipp.sneakygrounditems.config.Constants
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import java.util.UUID

internal class GroundItemMigrationService(
    private val plugin: SneakyGroundItems,
    private val persistence: PersistenceService,
) {
    private var activeMigration: MigrationState? = null

    fun startMigration(player: org.bukkit.entity.Player, dryRun: Boolean = false): Boolean {
        if (activeMigration != null) {
            player.sendMessage("A ground item migration is already running")
            return false
        }

        val minChunk = Math.floorDiv(Constants.MIGRATION_MIN_BLOCK, 16)
        val maxChunk = Math.floorDiv(Constants.MIGRATION_MAX_BLOCK, 16)
        val chunkWidth = maxChunk - minChunk + 1
        val totalChunkCount = chunkWidth * chunkWidth
        val migration = MigrationState(
            senderUuid = player.uniqueId,
            worldUuid = player.world.uid,
            dryRun = dryRun,
            minChunk = minChunk,
            maxChunk = maxChunk,
            width = chunkWidth,
            totalChunkCount = totalChunkCount,
            startedAt = System.currentTimeMillis(),
        )

        activeMigration = migration
        val modeLabel = if (dryRun) "dry migration" else "ground item migration"
        player.sendMessage("Starting $modeLabel for chunk range $minChunk..$maxChunk in ${player.world.name}")
        Bukkit.getScheduler().runTask(plugin, Runnable { runMigrationBatch(migration) })
        return true
    }

    private fun runMigrationBatch(migration: MigrationState) {
        if (activeMigration !== migration) return

        val world = Bukkit.getWorld(migration.worldUuid)
        if (world == null) {
            finishMigration(migration, "Migration aborted because the world is no longer loaded")
            return
        }

        repeat(Constants.MIGRATION_BATCH_SIZE) {
            val chunkCoordinates = migration.nextChunkCoordinates() ?: return@repeat
            migrateChunk(migration, world, chunkCoordinates.first, chunkCoordinates.second)
        }

        maybeReportMigrationProgress(migration)

        if (migration.isFinished) {
            finishMigration(migration, null)
            return
        }

        Bukkit.getScheduler().runTask(plugin, Runnable { runMigrationBatch(migration) })
    }

    private fun maybeReportMigrationProgress(migration: MigrationState) {
        if (migration.visitedChunks == 0) return
        if (migration.visitedChunks % Constants.MIGRATION_PROGRESS_INTERVAL != 0) return

        val sender = Bukkit.getPlayer(migration.senderUuid) ?: return
        val modeLabel = if (migration.dryRun) "Dry migration" else "Migration"
        sender.sendMessage(
            "$modeLabel progress: ${migration.visitedChunks}/${migration.totalChunkCount} candidate chunks checked, " +
                "${migration.processedChunks} chunks processed",
        )
    }

    private fun finishMigration(migration: MigrationState, errorMessage: String?) {
        if (activeMigration !== migration) return
        activeMigration = null

        val sender = Bukkit.getPlayer(migration.senderUuid)
        if (errorMessage != null) {
            sender?.sendMessage(errorMessage)
            return
        }

        migration.finalizeInteractionCounts()
        val elapsedMs = System.currentTimeMillis() - migration.startedAt
        val modeLabel = if (migration.dryRun) "Dry migration finished" else "Migration finished"
        val displayLabel = if (migration.dryRun) "displays needing migration" else "displays migrated"
        val interactionLabel = if (migration.dryRun) "interactions that would be removed" else "interactions removed"
        val orphanInteractionLabel = if (migration.dryRun) {
            "orphan interactions that would be removed"
        } else {
            "orphan interactions removed"
        }
        sender?.sendMessage(
            "$modeLabel: ${migration.processedChunks} chunks processed, " +
                "${migration.displaysMigrated} $displayLabel, " +
                "${migration.interactionsRemoved} $interactionLabel, " +
                "${migration.orphanInteractionsRemoved} $orphanInteractionLabel, " +
                "${elapsedMs}ms elapsed",
        )
    }

    private fun migrateChunk(migration: MigrationState, world: World, chunkX: Int, chunkZ: Int) {
        if (!world.isChunkGenerated(chunkX, chunkZ)) return

        val wasLoaded = world.isChunkLoaded(chunkX, chunkZ)
        if (!world.loadChunk(chunkX, chunkZ, false)) return

        val chunk = world.getChunkAt(chunkX, chunkZ, false)
        migration.processedChunks++

        for (entity in chunk.entities) {
            when {
                entity is ItemDisplay && persistence.isGroundItemDisplay(entity) -> {
                    migration.registerDisplay(entity.uniqueId)
                    val changed = if (migration.dryRun) {
                        persistence.needsLegacyDisplayCleanup(entity)
                    } else {
                        persistence.cleanupLegacyDisplay(entity)
                    }
                    if (changed) {
                        migration.displaysMigrated++
                    }
                }

                entity is Interaction && persistence.isLegacyInteraction(entity) -> {
                    val partnerUuid = persistence.getLegacyPartnerUuid(entity)
                    if (!migration.dryRun) {
                        entity.remove()
                    }
                    migration.registerInteractionPartner(partnerUuid)
                }
            }
        }

        if (!wasLoaded) {
            world.unloadChunk(chunkX, chunkZ, true)
        }
    }

    private data class MigrationState(
        val senderUuid: UUID,
        val worldUuid: UUID,
        val dryRun: Boolean,
        val minChunk: Int,
        val maxChunk: Int,
        val width: Int,
        val totalChunkCount: Int,
        val startedAt: Long,
        var nextChunkIndex: Int = 0,
        var visitedChunks: Int = 0,
        var processedChunks: Int = 0,
        var displaysMigrated: Int = 0,
        var interactionsRemoved: Int = 0,
        var orphanInteractionsRemoved: Int = 0,
    ) {
        private val migratedDisplayIds = mutableSetOf<UUID>()
        private val unresolvedInteractionPartners = mutableMapOf<UUID, Int>()

        val isFinished: Boolean
            get() = nextChunkIndex >= totalChunkCount

        fun nextChunkCoordinates(): Pair<Int, Int>? {
            if (nextChunkIndex >= totalChunkCount) return null

            val index = nextChunkIndex++
            visitedChunks++
            val x = minChunk + (index % width)
            val z = minChunk + (index / width)
            if (z > maxChunk) return null
            return x to z
        }

        fun registerDisplay(displayUuid: UUID) {
            if (!migratedDisplayIds.add(displayUuid)) return
            interactionsRemoved += unresolvedInteractionPartners.remove(displayUuid) ?: 0
        }

        fun registerInteractionPartner(partnerUuid: UUID?) {
            if (partnerUuid == null) {
                orphanInteractionsRemoved++
                return
            }

            if (partnerUuid in migratedDisplayIds) {
                interactionsRemoved++
                return
            }

            unresolvedInteractionPartners.merge(partnerUuid, 1, Int::plus)
        }

        fun finalizeInteractionCounts() {
            orphanInteractionsRemoved += unresolvedInteractionPartners.values.sum()
            unresolvedInteractionPartners.clear()
        }
    }
}

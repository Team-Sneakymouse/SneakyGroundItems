package com.danidipp.sneakygrounditems.display.labels

import com.danidipp.sneakygrounditems.display.PlacementService
import com.danidipp.sneakygrounditems.integration.Probe
import com.danidipp.sneakygrounditems.display.labels.packet.PaperLabelPackets
import com.danidipp.sneakygrounditems.model.GroundItemState
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import org.bukkit.Bukkit
import org.bukkit.entity.ItemDisplay
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Observes real items; no commerce caller has to coordinate presentation.
 * [probe] (SneakyDiagnostics, optional) times the scan; null in tests. Section and count names are in the README.
 */
internal class GroundItemLabels(
    private val environment: LabelEnvironment,
    private val packets: LabelPackets,
    private val probe: Probe? = null,
) : AutoCloseable {
    private data class ViewerSession(
        val world: UUID,
        val connection: LabelPackets.Connection,
        var generation: Long = connection.generation,
        val visible: MutableMap<UUID, LabelSource> = mutableMapOf(),
    )

    private val sessions = mutableMapOf<UUID, ViewerSession>()
    private var task: AutoCloseable? = null
    private var tick = 0L
    private var closed = false
    private var nextWarning = 0L

    fun start() {
        check(!closed) { "A closed label module cannot be restarted" }
        if (task == null) task = environment.schedule(::observe)
    }

    private fun observe() {
        if (closed) return
        val token = probe?.start() ?: 0L
        try {
            scan(token)
        } finally {
            if (token != 0L) probe?.end("tick.labels", token)
        }
    }

    private fun scan(token: Long) {
        val timed = token != 0L
        val viewersStarted = if (timed) probe!!.start() else 0L
        val viewers = environment.viewers()
        if (timed) probe!!.end("labels.list-viewers", viewersStarted)
        val online = viewers.mapTo(mutableSetOf()) { it.id }
        for (id in sessions.keys.toList()) {
            if (id !in online) {
                try { sessions.remove(id)?.connection?.close() } catch (error: Exception) { warn(error) }
            }
        }
        var creationsLeft = MAX_CREATIONS_PER_TICK
        for (viewer in viewers) {
            try {
                var session = sessions[viewer.id]
                if (session != null && (session.world != viewer.world || !session.connection.isOpen)) {
                    session.connection.close()
                    sessions.remove(viewer.id)
                    session = null
                }
                if (session == null) {
                    session = ViewerSession(viewer.world, packets.open(viewer.id))
                    sessions[viewer.id] = session
                }
                val reset = session.generation != session.connection.generation
                if (reset) {
                    session.visible.clear()
                    session.generation = session.connection.generation
                }
                if (!reset && Math.floorMod(viewer.id.hashCode(), REFRESH_TICKS) != (tick % REFRESH_TICKS).toInt()) continue

                if (timed) probe!!.count("labels.viewers-refreshed")
                val nearbyStarted = if (timed) probe!!.start() else 0L
                val desired = try {
                    environment.nearby(viewer).asSequence()
                        .filter { it.world == viewer.world }
                        .filter { it.distanceSquared <= if (it.id in session.visible) LEAVE_RADIUS * LEAVE_RADIUS else ENTER_RADIUS * ENTER_RADIUS }
                        .associateBy { it.id }
                } finally {
                    if (timed) probe!!.end("labels.nearby", nearbyStarted)
                }
                if (timed) probe!!.count("labels.desired", desired.size.toLong())
                val updateStarted = if (timed) probe!!.start() else 0L
                try {
                    for (id in session.visible.keys.toList()) {
                        if (id !in desired) {
                            session.connection.hide(id)
                            session.visible.remove(id)
                            if (timed) probe!!.count("labels.hidden")
                        }
                    }
                    for ((id, source) in desired) {
                        val previous = session.visible[id]
                        if (previous?.samePresentation(source) == true) continue
                        if (previous == null) {
                            if (creationsLeft <= 0) {
                                if (timed) probe!!.count("labels.creations-deferred")
                                continue
                            }
                            creationsLeft--
                        }
                        try {
                            session.connection.show(source)
                            session.visible[id] = source
                            if (timed) probe!!.count(if (previous == null) "labels.created" else "labels.changed")
                        } catch (error: Exception) { warn(error) }
                    }
                } finally {
                    if (timed) probe!!.end("labels.update", updateStarted)
                }
            } catch (error: Exception) {
                warn(error)
            }
        }
        if (timed) sampleLevels(viewers.size)
        tick++
    }

    /** The plugin's single sampleDue site (this task runs every tick). */
    private fun sampleLevels(viewers: Int) {
        val p = probe ?: return
        if (!p.sampleDue()) return
        p.level("labels.viewers", viewers.toLong())
        p.level("labels.sessions", sessions.size.toLong())
        p.level("labels.visible", sessions.values.sumOf { it.visible.size }.toLong())
    }

    private fun warn(error: Exception) {
        if (tick < nextWarning) return
        nextWarning = tick + 1200
        environment.warning("Could not update a ground-item label; presentation will retry independently of commerce", error)
    }

    override fun close() {
        if (closed) return
        closed = true
        task?.close()
        task = null
        for (session in sessions.values) {
            try { session.connection.close() } catch (error: Exception) { warn(error) }
        }
        sessions.clear()
    }

    companion object {
        const val ENTER_RADIUS = 16.0
        const val LEAVE_RADIUS = 20.0
        const val REFRESH_TICKS = 10
        private const val MAX_CREATIONS_PER_TICK = 64

        fun create(plugin: Plugin, persistence: PersistenceService, placement: PlacementService, probe: Probe? = null) =
            GroundItemLabels(BukkitLabelEnvironment(plugin, persistence, placement, probe), PaperLabelPackets(plugin), probe)
    }
}

/** All live Bukkit reads happen here, on the server thread. */
private class BukkitLabelEnvironment(
    private val plugin: Plugin,
    private val persistence: PersistenceService,
    private val placement: PlacementService,
    private val probe: Probe?,
) : LabelEnvironment {
    private val labelCache = mutableMapOf<UUID, GroundItemLabel?>()
    private var nextMalformedWarning = Long.MIN_VALUE

    override fun schedule(tick: () -> Unit): AutoCloseable {
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            labelCache.clear()
            tick()
        }, 1L, 1L)
        return AutoCloseable { task.cancel() }
    }

    override fun viewers() = Bukkit.getOnlinePlayers().map { LabelViewer(it.uniqueId, it.world.uid) }

    override fun nearby(viewer: LabelViewer): List<LabelSource> {
        val player = Bukkit.getPlayer(viewer.id) ?: return emptyList()
        if (player.world.uid != viewer.world) return emptyList()
        val center = player.location
        val radius = GroundItemLabels.LEAVE_RADIUS
        // Only already-loaded entities around an online player: this is the scan itself, not extra world access.
        val queryStarted = probe?.start() ?: 0L
        val entities = player.world.getNearbyEntities(center, radius, radius, radius)
        if (queryStarted != 0L) {
            probe?.end("labels.entity-query", queryStarted)
            probe?.count("labels.entities-queried", entities.size.toLong())
        }
        return entities.asSequence()
            .filterIsInstance<ItemDisplay>()
            .filter { it.isValid && persistence.isGroundItemDisplay(it) && player.canSee(it) }
            .mapNotNull { item ->
                val state = persistence.getGroundItemState(item)
                val showoff = state == GroundItemState.NORMAL && persistence.isShowoff(item)
                if (state == GroundItemState.NORMAL && !showoff) return@mapNotNull null
                val position = item.location
                val distance = position.distanceSquared(center)
                if (distance > radius * radius) return@mapNotNull null
                if (item.uniqueId !in labelCache) {
                    // A cache miss builds the item's display name (CraftItemFactory.displayName) and derives the label.
                    val deriveStarted = probe?.start() ?: 0L
                    try {
                        val stack = item.itemStack
                        labelCache[item.uniqueId] = GroundItemLabel.derive(
                            state, stack.displayName(), stack.amount, persistence.getCurrency(item), persistence.getPrice(item),
                            persistence.getFace(item) ?: placement.deriveFace(item), showoff,
                        )
                    } finally {
                        if (deriveStarted != 0L) probe?.end("labels.derive", deriveStarted)
                    }
                }
                val label = labelCache[item.uniqueId]
                if (label == null) {
                    val now = System.nanoTime()
                    if (now >= nextMalformedWarning) {
                        nextMalformedWarning = now + 60_000_000_000L
                        plugin.logger.warning("Ground item ${item.uniqueId} has incomplete label data; its stored state was preserved")
                    }
                    return@mapNotNull null
                }
                LabelSource(
                    item.uniqueId, item.world.uid, item.entityId, position.x, position.y, position.z, distance, label,
                    item.isTrackedBy(player), item.passengers.filter { player.canSee(it) && it.isTrackedBy(player) }.map { it.entityId },
                )
            }.toList().also { if (queryStarted != 0L) probe?.count("labels.candidates", it.size.toLong()) }
    }

    override fun warning(message: String, cause: Exception) {
        plugin.logger.log(java.util.logging.Level.WARNING, message, cause)
    }
}

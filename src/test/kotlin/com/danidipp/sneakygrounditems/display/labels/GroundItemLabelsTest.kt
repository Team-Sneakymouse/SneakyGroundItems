package com.danidipp.sneakygrounditems.display.labels

import com.danidipp.sneakygrounditems.model.GroundItemState
import net.kyori.adventure.text.Component
import org.bukkit.block.BlockFace
import java.util.UUID
import kotlin.test.*

class GroundItemLabelsTest {
    private val world = UUID(0, 10)
    private val alice = LabelViewer(UUID(0, 0), world)
    private val bob = LabelViewer(UUID(1, 1), world)
    private val item = UUID(0, 100)
    private val sale = GroundItemLabel(Component.text("Sale"), BlockFace.UP)

    private fun source(distance: Double = 16.0, label: GroundItemLabel = sale, id: UUID = item) =
        LabelSource(id, world, id.leastSignificantBits.toInt(), 0.0, 0.0, 0.0, distance * distance, label)

    @Test fun proximityUsesHysteresisAndNoDuplicatePresentation() {
        val env = Environment(alice)
        val packets = RecordingPackets()
        val labels = GroundItemLabels(env, packets)
        labels.start()
        env.sources[alice.id] = listOf(source(17.0))
        env.advance()
        assertTrue(packets.connection(alice).shown.isEmpty())
        env.sources[alice.id] = listOf(source())
        env.advance(10)
        assertEquals(listOf(item), packets.connection(alice).shown.map { it.id })
        env.sources[alice.id] = listOf(source(20.0))
        env.advance(10)
        assertEquals(1, packets.connection(alice).shown.size)
        assertTrue(packets.connection(alice).hidden.isEmpty())
        env.sources[alice.id] = listOf(source(20.01))
        env.advance(10)
        assertEquals(listOf(item), packets.connection(alice).hidden)
        labels.close()
    }

    @Test fun viewersAreIndependentAndItemChangesAreObservedWithoutNotifications() {
        val env = Environment(alice, bob)
        val packets = RecordingPackets()
        val labels = GroundItemLabels(env, packets)
        env.sources[alice.id] = listOf(source())
        env.sources[bob.id] = listOf(source(10.0))
        labels.start()
        env.advance()
        val stolen = GroundItemLabel.derive(GroundItemState.STOLEN, Component.empty(), 1, null, null, BlockFace.UP)!!
        env.sources[alice.id] = listOf(source(label = stolen))
        env.sources[bob.id] = emptyList()
        env.advance(9)
        assertEquals(1, packets.connection(alice).shown.size)
        env.advance()
        assertEquals(stolen, packets.connection(alice).shown.last().label)
        assertEquals(listOf(item), packets.connection(bob).hidden)
        assertTrue(packets.connection(alice).hidden.isEmpty())
        env.sources[alice.id] = emptyList() // Source removed or changed to NORMAL.
        env.advance(10)
        assertEquals(listOf(item), packets.connection(alice).hidden)
        labels.close()
    }

    @Test fun packetFailureRetriesWithoutRequiringAnItemChange() {
        val env = Environment(alice)
        val packets = RecordingPackets(failFirstShow = true)
        val labels = GroundItemLabels(env, packets)
        env.sources[alice.id] = listOf(source())
        labels.start()
        env.advance()
        assertEquals(1, env.warnings)
        assertTrue(packets.connection(alice).shown.isEmpty())
        env.advance(10)
        assertEquals(1, packets.connection(alice).shown.size)
        labels.close()
    }

    @Test fun clientWorldResetInvalidatesCachedPresentationAndQuitClosesTheSession() {
        val env = Environment(alice)
        val packets = RecordingPackets()
        val labels = GroundItemLabels(env, packets)
        env.sources[alice.id] = listOf(source())
        labels.start()
        env.advance()
        packets.connection(alice).generation++
        env.advance() // Reset is not delayed to the viewer's ordinary refresh phase.
        assertEquals(2, packets.connection(alice).shown.size)
        env.players = emptyList()
        env.advance()
        assertEquals(1, packets.connection(alice).closes)
        labels.close()
        labels.close()
        assertTrue(env.cancelled)
    }

    @Test fun aChangedWorldGetsANewConnectionAndQueuedObservationsStopOnClose() {
        val env = Environment(alice)
        val packets = RecordingPackets()
        val labels = GroundItemLabels(env, packets)
        env.sources[alice.id] = listOf(source())
        labels.start()
        env.advance()
        val old = packets.connection(alice)
        env.players = listOf(alice.copy(world = UUID(0, 20)))
        env.advance(10)
        assertEquals(1, old.closes)
        assertNotSame(old, packets.connection(alice))
        assertTrue(packets.connection(alice).shown.isEmpty())
        labels.close()
        env.advance(20) // Even a stale scheduler callback cannot recreate a label.
        assertTrue(packets.connection(alice).shown.isEmpty())
    }

    @Test fun denseArrivalsAreBoundedAndDeferredSourcesAreRevalidated() {
        val env = Environment(alice)
        val packets = RecordingPackets()
        val labels = GroundItemLabels(env, packets)
        env.sources[alice.id] = (1L..100L).map { source(1.0, id = UUID(0, it)) }
        labels.start()
        env.advance()
        assertEquals(64, packets.connection(alice).shown.size)
        env.sources[alice.id] = env.sources.getValue(alice.id).take(80)
        env.advance(10)
        assertEquals(80, packets.connection(alice).shown.size)
        labels.close()
    }

    @Test fun aFailedLabelDoesNotBlockOtherSourcesInTheSameRefresh() {
        val env = Environment(alice)
        val packets = RecordingPackets(failFirstShow = true)
        val labels = GroundItemLabels(env, packets)
        val second = UUID(0, 200)
        env.sources[alice.id] = listOf(source(), source(id = second))
        labels.start()
        env.advance()
        assertEquals(listOf(second), packets.connection(alice).shown.map { it.id })
        env.advance(10)
        assertEquals(listOf(second, item), packets.connection(alice).shown.map { it.id })
        labels.close()
    }

    @Test fun reconnectBetweenObservationsCannotReuseTheOldConnection() {
        val env = Environment(alice)
        val packets = RecordingPackets()
        val labels = GroundItemLabels(env, packets)
        env.sources[alice.id] = listOf(source())
        labels.start()
        env.advance()
        val old = packets.connection(alice)
        old.connected = false
        env.advance(10)
        assertEquals(1, old.closes)
        assertNotSame(old, packets.connection(alice))
        assertEquals(1, packets.connection(alice).shown.size)
        labels.close()
    }

    private class Environment(vararg initial: LabelViewer) : LabelEnvironment {
        var players = initial.toList()
        val sources = mutableMapOf<UUID, List<LabelSource>>()
        var cancelled = false
        var warnings = 0
        private var action: (() -> Unit)? = null
        override fun schedule(tick: () -> Unit): AutoCloseable {
            action = tick
            return AutoCloseable { cancelled = true }
        }
        fun advance(count: Int = 1) { repeat(count) { action?.invoke() } }
        override fun viewers() = players
        override fun nearby(viewer: LabelViewer) = sources[viewer.id].orEmpty()
        override fun warning(message: String, cause: Exception) { warnings++ }
    }

    private class RecordingPackets(private var failFirstShow: Boolean = false) : LabelPackets {
        private val connections = mutableMapOf<UUID, RecordingConnection>()
        override fun open(viewer: UUID) = RecordingConnection().also { connections[viewer] = it }
        fun connection(viewer: LabelViewer) = connections.getValue(viewer.id)
        inner class RecordingConnection : LabelPackets.Connection {
            var connected = true
            override val isOpen: Boolean get() = connected
            override var generation = 0L
            val shown = mutableListOf<LabelSource>()
            val hidden = mutableListOf<UUID>()
            var closes = 0
            override fun show(source: LabelSource) {
                if (failFirstShow) { failFirstShow = false; error("Packet delivery unavailable") }
                shown += source
            }
            override fun hide(source: UUID) { hidden += source }
            override fun close() { closes++ }
        }
    }
}

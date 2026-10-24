package com.danidipp.sneakygrounditems.display.labels.packet

import com.danidipp.sneakygrounditems.config.Constants
import com.danidipp.sneakygrounditems.display.labels.LabelPackets
import com.danidipp.sneakygrounditems.display.labels.LabelSource
import com.mojang.math.Transformation
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.papermc.paper.adventure.PaperAdventure
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.phys.Vec3
import org.bukkit.Bukkit
import org.bukkit.block.BlockFace
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.plugin.Plugin
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/** All version-specific names and all outbound stream ownership live in this adapter. */
internal class PaperLabelPackets(private val plugin: Plugin) : LabelPackets {
    override fun open(viewer: UUID): LabelPackets.Connection {
        check(Bukkit.isPrimaryThread())
        val player = Bukkit.getPlayer(viewer) as? CraftPlayer ?: error("Viewer disconnected")
        return PaperConnection(player.handle.connection.connection.channel)
    }

    internal data class Prepared(
        val source: UUID,
        val parent: Int,
        val id: Int,
        val spawn: ClientboundAddEntityPacket,
        val metadata: ClientboundSetEntityDataPacket,
        val seedTracked: Boolean,
        val seedPassengers: IntArray,
    )

    private inner class PaperConnection(private val channel: Channel) : LabelPackets.Connection {
        private val closed = AtomicBoolean()
        // Main-thread-owned identities. The handler receives only immutable packet snapshots.
        private val identities = mutableMapOf<UUID, Int>()
        private val handler = LabelStream()
        override val isOpen: Boolean get() = !closed.get() && channel.isActive
        override val generation: Long get() = handler.generation

        init {
            execute {
                if (!closed.get() && channel.isActive) {
                    check(channel.pipeline().get(HANDLER_NAME) == null) { "Label stream already installed" }
                    // Outbound traversal: unbundler -> this handler -> encoder.
                    // Seeing individual packets avoids modifying/reordering intact bundles.
                    channel.pipeline().addAfter("encoder", HANDLER_NAME, handler)
                }
            }
        }

        override fun show(source: LabelSource) {
            check(Bukkit.isPrimaryThread())
            if (closed.get()) return
            val expectedGeneration = handler.generation
            val level = (Bukkit.getWorld(source.world) as? CraftWorld)?.handle ?: return
            // Construction allocates from the native global allocator. Never add this object to a world.
            val text = Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level)
            identities[source.id]?.let { text.id = it }
            identities[source.id] = text.id
            text.setPos(source.x, source.y, source.z)
            text.setText(PaperAdventure.asVanilla(source.label.text))
            text.setNoGravity(true)
            text.setViewRange(Constants.TEXT_DISPLAY_VIEW_RANGE)
            val face = source.label.face
            val wall = face != BlockFace.UP && face != BlockFace.DOWN
            val offset = if (wall) Vector3f(0f, TEXT_OFFSET, 0f)
                else Vector3f(face.modX.toFloat(), face.modY.toFloat(), face.modZ.toFloat()).mul(TEXT_OFFSET)
            text.setTransformation(Transformation(offset, Quaternionf(), Vector3f(TEXT_SCALE), Quaternionf()))
            text.setBillboardConstraints(when (face) {
                BlockFace.UP -> Display.BillboardConstraints.VERTICAL
                BlockFace.DOWN -> Display.BillboardConstraints.CENTER
                else -> Display.BillboardConstraints.FIXED
            })
            val yaw = when (face) {
                BlockFace.NORTH -> 180f
                BlockFace.EAST -> -90f
                BlockFace.WEST -> 90f
                else -> 0f
            }
            val prepared = Prepared(
                source.id, source.entityId, text.id,
                ClientboundAddEntityPacket(text.id, text.uuid, source.x, source.y, source.z, 0f, yaw,
                    EntityTypes.TEXT_DISPLAY, 0, Vec3.ZERO, yaw.toDouble()),
                ClientboundSetEntityDataPacket(text.id, text.entityData.packAll()),
                source.alreadyTracked, source.passengers.toIntArray(),
            )
            execute { if (!closed.get() && handler.generation == expectedGeneration) handler.show(prepared) }
        }

        override fun hide(source: UUID) {
            identities.remove(source)
            execute { if (!closed.get()) handler.hide(source) }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            identities.clear()
            execute {
                handler.dispose()
                if (channel.pipeline().context(handler) != null) channel.pipeline().remove(handler)
            }
        }

        private fun execute(action: () -> Unit) {
            try {
                channel.eventLoop().execute {
                    try { action() } catch (error: Exception) { handler.failed(error) }
                }
            } catch (error: RejectedExecutionException) {
                if (channel.isActive && !closed.get()) throw error
            }
        }
    }

    /** This entire object belongs to the connection's Netty event loop. No Bukkit reads here. */
    internal inner class LabelStream : ChannelOutboundHandlerAdapter() {
        @Volatile var generation = 0L
            private set
        private var context: ChannelHandlerContext? = null
        private val desired = mutableMapOf<UUID, Prepared>()
        private val sourceByParent = mutableMapOf<Int, UUID>()
        private val available = mutableSetOf<Int>()
        private val active = mutableSetOf<UUID>()
        private val passengers = mutableMapOf<Int, IntArray>()
        private val knownItems = mutableMapOf<Int, UUID>()
        private var maySeedExistingItems = true
        private var nextWarning = Long.MIN_VALUE
        private var recovering = false

        override fun handlerAdded(ctx: ChannelHandlerContext) { context = ctx }

        override fun write(ctx: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
            when (message) {
                is ClientboundSetPassengersPacket -> {
                    val source = sourceByParent[message.vehicle]
                    if (source != null) passengers[message.vehicle] = message.passengers.copyOf()
                    val label = source?.takeIf { it in active }?.let(desired::get)
                    val replacement = if (label == null) message else passengerPacket(message.vehicle, message.passengers + label.id)
                    ctx.write(replacement, promise)
                }
                is ClientboundAddEntityPacket -> {
                    ctx.write(message, promise)
                    safely {
                        if (message.type == EntityTypes.ITEM_DISPLAY) knownItems[message.id] = message.uuid
                        else knownItems.remove(message.id)
                        val source = sourceByParent[message.id] ?: return@safely
                        if (source == message.uuid) {
                            available += message.id
                            activate(source)
                        } else {
                            removeActive(source, restorePassengers = false)
                            available -= message.id
                        }
                    }
                }
                is ClientboundRemoveEntitiesPacket -> {
                    ctx.write(message, promise)
                    safely {
                        val removed = message.entityIds
                        for (index in 0 until removed.size) {
                            val parent = removed.getInt(index)
                            knownItems.remove(parent)
                            available -= parent
                            sourceByParent[parent]?.let { removeActive(it, restorePassengers = false) }
                            passengers.remove(parent)
                        }
                    }
                }
                is ClientboundRespawnPacket -> {
                    // The client discards its entity world even for a same-world respawn.
                    clear()
                    knownItems.clear()
                    maySeedExistingItems = false
                    generation++
                    ctx.write(message, promise)
                }
                else -> ctx.write(message, promise)
            }
        }

        fun show(label: Prepared) {
            val previous = desired[label.source]
            if (previous != null && (previous.parent != label.parent || previous.id != label.id)) hide(label.source)
            desired[label.source] = label
            sourceByParent[label.parent] = label.source
            if (knownItems[label.parent] == label.source ||
                (maySeedExistingItems && label.seedTracked && previous == null && label.parent !in knownItems)) {
                // Startup/range-entry seeding: main-thread tracking snapshot, queued AFTER
                // all parent pairing writes. Later availability comes from the actual stream.
                available += label.parent
                knownItems[label.parent] = label.source
                passengers[label.parent] = label.seedPassengers.copyOf()
            }
            if (label.source in active) {
                send(label.metadata, flush = true)
            } else activate(label.source)
        }

        fun hide(source: UUID) {
            val label = desired[source] ?: return
            removeActive(source, restorePassengers = true)
            desired.remove(source)
            sourceByParent.remove(label.parent)
            available.remove(label.parent)
            passengers.remove(label.parent)
        }

        private fun activate(source: UUID) {
            val ctx = context ?: return
            val label = desired[source] ?: return
            if (label.parent !in available || source in active || !ctx.channel().isActive) return
            // ctx.write starts before this handler; only individual packets are emitted.
            active += source
            val expectedGeneration = generation
            send(label.spawn)
            if (generation != expectedGeneration) return
            send(label.metadata)
            if (generation != expectedGeneration) return
            send(passengerPacket(label.parent, (passengers[label.parent] ?: intArrayOf()) + label.id), flush = true)
        }

        private fun removeActive(source: UUID, restorePassengers: Boolean) {
            if (!active.remove(source)) return
            val ctx = context ?: return
            val label = desired[source] ?: return
            if (!ctx.channel().isActive) return
            if (restorePassengers && label.parent in available) {
                send(passengerPacket(label.parent, passengers[label.parent] ?: intArrayOf()))
            }
            send(ClientboundRemoveEntitiesPacket(label.id), flush = true)
        }

        private fun send(packet: Packet<*>, flush: Boolean = false) {
            val ctx = context ?: return
            val future = if (flush) ctx.writeAndFlush(packet) else ctx.write(packet)
            future.addListener { result ->
                if (!result.isSuccess && ctx.channel().isActive && !recovering) {
                    failed(IllegalStateException("Could not write a ground-item label packet", result.cause()))
                }
            }
        }

        fun dispose() {
            for (source in active.toList()) removeActive(source, restorePassengers = true)
            clear()
        }

        private fun clear() {
            active.clear()
            desired.clear()
            sourceByParent.clear()
            available.clear()
            passengers.clear()
        }

        private inline fun safely(action: () -> Unit) {
            try { action() } catch (error: Exception) { failed(error) }
        }

        fun failed(error: Exception) {
            if (recovering) return
            recovering = true
            try {
                val now = System.nanoTime()
                if (context?.channel()?.isActive == true && now >= nextWarning) {
                    nextWarning = now + 60_000_000_000L
                    plugin.logger.log(Level.WARNING, "Ground-item label packet update failed", error)
                }
            } finally {
                // Invalidate main-thread membership even if cleanup or logging fails.
                try { dispose() } catch (_: Exception) { clear() }
                generation++
                recovering = false
            }
        }
    }

    companion object {
        private const val HANDLER_NAME = "sneakygrounditems_labels"
        private const val TEXT_OFFSET = 0.25f
        private const val TEXT_SCALE = 0.15f

        private fun passengerPacket(parent: Int, passengers: IntArray): ClientboundSetPassengersPacket {
            val buffer = FriendlyByteBuf(Unpooled.buffer())
            try {
                buffer.writeVarInt(parent)
                buffer.writeVarIntArray(passengers)
                return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer)
            } finally { buffer.release() }
        }
    }
}

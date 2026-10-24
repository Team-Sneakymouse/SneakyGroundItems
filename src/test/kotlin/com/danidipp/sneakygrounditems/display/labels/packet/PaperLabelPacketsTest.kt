package com.danidipp.sneakygrounditems.display.labels.packet

import com.danidipp.sneakygrounditems.testutil.testPlugin
import io.netty.buffer.Unpooled
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import net.minecraft.SharedConstants
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.PacketBundleUnpacker
import net.minecraft.network.protocol.BundlerInfo
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundRespawnPacket
import net.minecraft.network.protocol.game.GamePacketTypes
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket
import net.minecraft.server.Bootstrap
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.test.*

/** Exercises actual pinned Paper packets and the actual outbound handler, not a mock codec. */
class PaperLabelPacketsTest {
    private val source = UUID(0, 100)
    private val parent = 100
    private val virtual = 900

    private fun prepared(tracked: Boolean = false) = PaperLabelPackets.Prepared(
        source, parent, virtual,
        ClientboundAddEntityPacket(virtual, UUID(0, virtual.toLong()), 0.0, 0.0, 0.0, 0f, 0f, EntityTypes.TEXT_DISPLAY, 0, Vec3.ZERO, 0.0),
        ClientboundSetEntityDataPacket(virtual, emptyList()), tracked, intArrayOf(200),
    )

    private fun spawnParent(id: UUID = source) =
        ClientboundAddEntityPacket(parent, id, 0.0, 0.0, 0.0, 0f, 0f, EntityTypes.ITEM_DISPLAY, 0, Vec3.ZERO, 0.0)

    @Test fun waitsForParentAndPreservesPacketOrderAndRealPassengers() {
        val stream = PaperLabelPackets(testPlugin()).LabelStream()
        val channel = EmbeddedChannel(stream)
        stream.show(prepared())
        assertNull(channel.readOutbound<Any>())
        channel.writeOutbound(spawnParent())
        assertEquals(parent, channel.readOutbound<ClientboundAddEntityPacket>().id)
        assertEquals(virtual, channel.readOutbound<ClientboundAddEntityPacket>().id)
        assertEquals(virtual, channel.readOutbound<ClientboundSetEntityDataPacket>().id)
        assertContentEquals(intArrayOf(virtual), channel.readOutbound<ClientboundSetPassengersPacket>().passengers)

        channel.writeOutbound(passengers(parent, intArrayOf(200, 201)))
        assertContentEquals(intArrayOf(200, 201, virtual), channel.readOutbound<ClientboundSetPassengersPacket>().passengers)
        channel.writeOutbound(passengers(999, intArrayOf(300)))
        assertContentEquals(intArrayOf(300), channel.readOutbound<ClientboundSetPassengersPacket>().passengers)

        stream.hide(source)
        assertContentEquals(intArrayOf(200, 201), channel.readOutbound<ClientboundSetPassengersPacket>().passengers)
        assertContentEquals(intArrayOf(virtual), channel.readOutbound<ClientboundRemoveEntitiesPacket>().entityIds.toIntArray())
        assertNull(channel.readOutbound<Any>())
        channel.finishAndReleaseAll()
    }

    @Test fun removalDestroysTheHelperAndRetrackingRecreatesItAfterTheParent() {
        val stream = PaperLabelPackets(testPlugin()).LabelStream()
        val channel = EmbeddedChannel(stream)
        stream.show(prepared(tracked = true))
        repeat(3) { assertNotNull(channel.readOutbound<Any>()) }
        channel.writeOutbound(ClientboundRemoveEntitiesPacket(parent))
        assertContentEquals(intArrayOf(parent), channel.readOutbound<ClientboundRemoveEntitiesPacket>().entityIds.toIntArray())
        assertContentEquals(intArrayOf(virtual), channel.readOutbound<ClientboundRemoveEntitiesPacket>().entityIds.toIntArray())
        assertNull(channel.readOutbound<Any>())

        channel.writeOutbound(spawnParent())
        assertEquals(parent, channel.readOutbound<ClientboundAddEntityPacket>().id)
        assertEquals(virtual, channel.readOutbound<ClientboundAddEntityPacket>().id)
        assertIs<ClientboundSetEntityDataPacket>(channel.readOutbound())
        assertContentEquals(intArrayOf(virtual), channel.readOutbound<ClientboundSetPassengersPacket>().passengers)
        stream.dispose()
        channel.finishAndReleaseAll()
    }

    @Test fun aReusedParentIdCannotAttachAHelperToAnotherItem() {
        val stream = PaperLabelPackets(testPlugin()).LabelStream()
        val channel = EmbeddedChannel(stream)
        stream.show(prepared())
        channel.writeOutbound(spawnParent(UUID(0, 101)))
        assertEquals(parent, channel.readOutbound<ClientboundAddEntityPacket>().id)
        assertNull(channel.readOutbound<Any>())
        stream.dispose()
        channel.finishAndReleaseAll()
    }

    @Test fun enteringRangeAfterAnObservedParentSpawnNeedsNoTrackingSeed() {
        val stream = PaperLabelPackets(testPlugin()).LabelStream()
        val channel = EmbeddedChannel(stream)
        channel.writeOutbound(spawnParent())
        channel.readOutbound<Any>()
        stream.show(prepared())
        assertEquals(virtual, channel.readOutbound<ClientboundAddEntityPacket>().id)
        assertIs<ClientboundSetEntityDataPacket>(channel.readOutbound())
        assertIs<ClientboundSetPassengersPacket>(channel.readOutbound())
        stream.dispose()
        channel.finishAndReleaseAll()
    }

    @Test fun updatedMetadataDoesNotRespawnOrRemountAnExistingLabel() {
        val stream = PaperLabelPackets(testPlugin()).LabelStream()
        val channel = EmbeddedChannel(stream)
        stream.show(prepared(tracked = true))
        repeat(3) { channel.readOutbound<Any>() }
        stream.show(prepared(tracked = true))
        assertIs<ClientboundSetEntityDataPacket>(channel.readOutbound())
        assertNull(channel.readOutbound<Any>())
        stream.dispose()
        assertIs<ClientboundSetPassengersPacket>(channel.readOutbound())
        assertIs<ClientboundRemoveEntitiesPacket>(channel.readOutbound())
        stream.dispose()
        assertNull(channel.readOutbound<Any>())
        channel.finishAndReleaseAll()
    }

    @Test fun respawnRejectsOldTrackingSeedsUntilTheParentActuallyReappears() {
        val stream = PaperLabelPackets(testPlugin()).LabelStream()
        val channel = EmbeddedChannel(stream)
        stream.show(prepared(tracked = true))
        repeat(3) { channel.readOutbound<Any>() }
        val reset = org.mockito.Mockito.mock(ClientboundRespawnPacket::class.java)
        channel.writeOutbound(reset)
        assertSame(reset, channel.readOutbound())
        assertEquals(1L, stream.generation)
        stream.show(prepared(tracked = true))
        assertNull(channel.readOutbound<Any>()) // A stale server tracking snapshot cannot remount it.
        channel.writeOutbound(spawnParent())
        assertEquals(parent, channel.readOutbound<ClientboundAddEntityPacket>().id)
        assertEquals(virtual, channel.readOutbound<ClientboundAddEntityPacket>().id)
        assertIs<ClientboundSetEntityDataPacket>(channel.readOutbound())
        assertIs<ClientboundSetPassengersPacket>(channel.readOutbound())
        stream.dispose()
        channel.finishAndReleaseAll()
    }

    @Test fun theProductionPipelinePositionSeesParentsInsideVanillaBundles() {
        val stream = PaperLabelPackets(testPlugin()).LabelStream()
        val channel = EmbeddedChannel()
        channel.pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
        channel.pipeline().addLast("unbundler", PacketBundleUnpacker(BundlerInfo.createForPacket(
            GamePacketTypes.CLIENTBOUND_BUNDLE, ::ClientboundBundlePacket, ClientboundBundleDelimiterPacket(),
        )))
        channel.pipeline().addAfter("encoder", "labels", stream)
        stream.show(prepared())
        channel.writeOutbound(ClientboundBundlePacket(listOf(spawnParent(), passengers(parent, intArrayOf(200)))))
        assertIs<ClientboundBundleDelimiterPacket>(channel.readOutbound())
        assertEquals(parent, channel.readOutbound<ClientboundAddEntityPacket>().id)
        assertEquals(virtual, channel.readOutbound<ClientboundAddEntityPacket>().id)
        assertIs<ClientboundSetEntityDataPacket>(channel.readOutbound())
        assertContentEquals(intArrayOf(virtual), channel.readOutbound<ClientboundSetPassengersPacket>().passengers)
        assertContentEquals(intArrayOf(200, virtual), channel.readOutbound<ClientboundSetPassengersPacket>().passengers)
        assertIs<ClientboundBundleDelimiterPacket>(channel.readOutbound())
        assertNull(channel.readOutbound<Any>())
        stream.dispose()
        channel.finishAndReleaseAll()
    }

    @Test fun failedPacketPromisesInvalidateTheSessionForRetryWithoutEscapingIntoCommerce() {
        val stream = PaperLabelPackets(testPlugin()).LabelStream()
        val channel = EmbeddedChannel(object : ChannelOutboundHandlerAdapter() {
            override fun write(ctx: ChannelHandlerContext, message: Any, promise: ChannelPromise) {
                if (message is ClientboundSetEntityDataPacket) promise.setFailure(IllegalStateException("Encoder unavailable"))
                else ctx.write(message, promise)
            }
        }, stream)
        stream.show(prepared(tracked = true))
        assertEquals(1L, stream.generation)
        assertEquals(virtual, channel.readOutbound<ClientboundAddEntityPacket>().id)
        assertContentEquals(intArrayOf(200), channel.readOutbound<ClientboundSetPassengersPacket>().passengers)
        assertIs<ClientboundRemoveEntitiesPacket>(channel.readOutbound())
        assertNull(channel.readOutbound<Any>())
        stream.dispose()
        channel.finishAndReleaseAll()
    }

    private fun passengers(parent: Int, ids: IntArray): ClientboundSetPassengersPacket {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            buffer.writeVarInt(parent)
            buffer.writeVarIntArray(ids)
            return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer)
        } finally { buffer.release() }
    }

    companion object {
        init {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}

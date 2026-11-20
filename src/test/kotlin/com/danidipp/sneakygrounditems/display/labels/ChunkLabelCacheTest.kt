package com.danidipp.sneakygrounditems.display.labels

import net.kyori.adventure.text.Component
import org.bukkit.block.BlockFace
import java.util.UUID
import kotlin.random.Random
import kotlin.test.*

class ChunkLabelCacheTest {
    private val world = UUID(0, 10)
    private val label = GroundItemLabel(Component.text("Sale"), BlockFace.UP)

    private fun item(n: Long) = ChunkLabelItem("item-$n", UUID(0, n), n.toInt(), 0.0, 0.0, 0.0, label)

    @Test fun aChunkIsReadOnceAndSharedUntilItIsTenTicksOld() {
        val cache = ChunkLabelCache<String>(10)
        var reads = 0
        val read = { reads++; listOf(item(reads.toLong())) }
        val first = cache.get(world, 0, 0, now = 100, read)
        assertSame(first, cache.get(world, 0, 0, now = 109, read)) // Another viewer later in the same cycle.
        assertEquals(1, reads)
        assertNotEquals(first, cache.get(world, 0, 0, now = 110, read))
        assertEquals(2, reads)
    }

    @Test fun chunksAndWorldsAreSeparateEntries() {
        val cache = ChunkLabelCache<String>(10)
        var reads = 0
        val read = { reads++; emptyList<ChunkLabelItem<String>>() }
        cache.get(world, 0, 0, 0, read)
        cache.get(world, 1, 0, 0, read)
        cache.get(world, 0, 1, 0, read)
        cache.get(UUID(0, 20), 0, 0, 0, read)
        cache.get(world, 0, 0, 0, read)
        assertEquals(4, reads)
        assertEquals(4, cache.size)
    }

    @Test fun anUnloadedChunkIsNotCachedAndIsRetried() {
        val cache = ChunkLabelCache<String>(10)
        var loaded = false
        var reads = 0
        val read = { reads++; if (loaded) listOf(item(1)) else null }
        assertTrue(cache.get(world, 0, 0, 0, read).isEmpty())
        loaded = true
        assertEquals(listOf(item(1)), cache.get(world, 0, 0, 1, read))
        assertEquals(2, reads)
    }

    @Test fun pruneDropsEntriesNoViewerAskedForWithinACycle() {
        val cache = ChunkLabelCache<String>(10)
        cache.get(world, 0, 0, 0) { emptyList() }
        cache.get(world, 1, 0, 5) { emptyList() }
        cache.prune(10)
        assertEquals(1, cache.size)
        cache.prune(15)
        assertEquals(0, cache.size)
    }

    @Test fun horizontalDistanceIsToTheChunkSquareAndIgnoresHeight() {
        assertEquals(0.0, ChunkLabelCache.horizontalDistanceSquared(8.0, 8.0, 0, 0))
        assertEquals(0.0, ChunkLabelCache.horizontalDistanceSquared(16.0, 0.0, 0, 0)) // On the edge.
        assertEquals(4.0, ChunkLabelCache.horizontalDistanceSquared(18.0, 8.0, 0, 0))
        assertEquals(9.0 + 16.0, ChunkLabelCache.horizontalDistanceSquared(-3.0, 20.0, 0, 0)) // Past a corner.
        assertEquals(4.0, ChunkLabelCache.horizontalDistanceSquared(-18.0, -8.0, -1, -1))
    }

    @Test fun cornerChunksBeyondTheRadiusAreSkipped() {
        // At (15, 15) the 20-block box spans x and z -5..35, so chunks -1..2 on each axis (16 chunks).
        val chunks = ChunkLabelCache.chunksInRange(15.0, 15.0, 20.0).toSet()
        assertTrue((2 to 0) in chunks) // 17 blocks to its edge.
        assertTrue((1 to 1) in chunks)
        assertFalse((-1 to -1) in chunks) // Its nearest corner (0, 0) is 15 * sqrt 2 = 21.2 blocks away.
        assertFalse((2 to 2) in chunks) // Nearest corner (32, 32): 17 * sqrt 2 = 24 blocks.
        assertFalse((-1 to 2) in chunks) // Nearest corner (0, 32): sqrt(15^2 + 17^2) = 22.7 blocks.
        assertFalse((2 to -1) in chunks) // Nearest corner (32, 0): 22.7 blocks.
        assertEquals(12, chunks.size)
    }

    @Test fun everyPointWithinTheRadiusLiesInAChunkThatPassesTheCoarseCheck() {
        val random = Random(42)
        repeat(2_000) {
            val x = random.nextDouble(-1_000.0, 1_000.0)
            val z = random.nextDouble(-1_000.0, 1_000.0)
            val chunks = ChunkLabelCache.chunksInRange(x, z, 20.0).toSet()
            repeat(20) {
                val angle = random.nextDouble(0.0, 2 * Math.PI)
                val distance = random.nextDouble(0.0, 20.0)
                val px = x + distance * Math.cos(angle)
                val pz = z + distance * Math.sin(angle)
                val chunk = Math.floorDiv(Math.floor(px).toInt(), 16) to Math.floorDiv(Math.floor(pz).toInt(), 16)
                assertTrue(chunk in chunks, "($px, $pz) near ($x, $z) is in $chunk, which was skipped")
            }
        }
    }

    @Test fun chunksFartherThanTheRadiusArePruned() {
        repeat(200) {
            val x = Random(it).nextDouble(-500.0, 500.0)
            val z = Random(it + 1000).nextDouble(-500.0, 500.0)
            for ((chunkX, chunkZ) in ChunkLabelCache.chunksInRange(x, z, 20.0)) {
                assertTrue(ChunkLabelCache.horizontalDistanceSquared(x, z, chunkX, chunkZ) <= 400.0)
            }
        }
    }
}

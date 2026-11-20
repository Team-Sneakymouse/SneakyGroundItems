package com.danidipp.sneakygrounditems.display.labels

import java.util.UUID

/**
 * One labelled ground item as read from its chunk. [handle] is the live entity in production, anything in tests.
 * The label is built on first use, so items no viewer is near never build one; it is then shared until the chunk is
 * read again. Null when the item's label data is malformed.
 */
internal class ChunkLabelItem<E>(
    val handle: E,
    val id: UUID,
    val entityId: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    deriveLabel: () -> GroundItemLabel?,
) {
    val label: GroundItemLabel? by lazy(LazyThreadSafetyMode.NONE, deriveLabel)
}

/**
 * Labelled ground items per loaded chunk, read once and shared by every viewer near that chunk.
 * An entry is reused for [maxAgeTicks] ticks after it was read, then read again. Nothing is persisted: entries are
 * rebuilt from loaded entities, and [prune] drops entries no viewer asked for within [maxAgeTicks].
 */
internal class ChunkLabelCache<E>(private val maxAgeTicks: Long) {
    private data class Key(val world: UUID, val x: Int, val z: Int)
    private class Entry<E>(val readAt: Long, val items: List<ChunkLabelItem<E>>)

    private val entries = HashMap<Key, Entry<E>>()

    val size: Int get() = entries.size

    /**
     * The chunk's items, read with [read] when there is no entry younger than [maxAgeTicks] at [now].
     * [read] returns null when the chunk or its entities are not loaded; that result is not cached.
     */
    fun get(world: UUID, chunkX: Int, chunkZ: Int, now: Long, read: () -> List<ChunkLabelItem<E>>?): List<ChunkLabelItem<E>> {
        val key = Key(world, chunkX, chunkZ)
        entries[key]?.let { if (now - it.readAt < maxAgeTicks) return it.items }
        val items = read()
        if (items == null) {
            entries.remove(key)
            return emptyList()
        }
        entries[key] = Entry(now, items)
        return items
    }

    fun prune(now: Long) {
        entries.values.removeIf { now - it.readAt >= maxAgeTicks }
    }

    fun clear() = entries.clear()

    companion object {
        private const val CHUNK_SIZE = 16.0

        /** Horizontal squared distance from (x, z) to the chunk's square; 0 inside it. Height is ignored. */
        fun horizontalDistanceSquared(x: Double, z: Double, chunkX: Int, chunkZ: Int): Double {
            val dx = outside(x, chunkX * CHUNK_SIZE)
            val dz = outside(z, chunkZ * CHUNK_SIZE)
            return dx * dx + dz * dz
        }

        /**
         * Every chunk that can hold a point within [radius] of (x, z): the coarse check before the exact per-item one.
         * Chunks whose square is farther than [radius] horizontally are left out.
         */
        fun chunksInRange(x: Double, z: Double, radius: Double): List<Pair<Int, Int>> {
            val minX = Math.floorDiv(Math.floor(x - radius).toInt(), 16)
            val maxX = Math.floorDiv(Math.floor(x + radius).toInt(), 16)
            val minZ = Math.floorDiv(Math.floor(z - radius).toInt(), 16)
            val maxZ = Math.floorDiv(Math.floor(z + radius).toInt(), 16)
            val radiusSquared = radius * radius
            val chunks = ArrayList<Pair<Int, Int>>((maxX - minX + 1) * (maxZ - minZ + 1))
            for (chunkX in minX..maxX) {
                for (chunkZ in minZ..maxZ) {
                    if (horizontalDistanceSquared(x, z, chunkX, chunkZ) <= radiusSquared) chunks += chunkX to chunkZ
                }
            }
            return chunks
        }

        private fun outside(value: Double, min: Double) = when {
            value < min -> min - value
            value > min + CHUNK_SIZE -> value - (min + CHUNK_SIZE)
            else -> 0.0
        }
    }
}

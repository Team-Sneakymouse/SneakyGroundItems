package com.danidipp.sneakygrounditems.display.labels

import java.util.UUID

/** Internal transport seam: Paper in production, a recording adapter in tests. */
internal fun interface LabelPackets {
    fun open(viewer: UUID): Connection

    interface Connection : AutoCloseable {
        val isOpen: Boolean get() = true
        /** Changes whenever the client discards its world (including same-world respawn). */
        val generation: Long
        fun show(source: LabelSource)
        fun hide(source: UUID)
        override fun close()
    }
}

internal data class LabelViewer(val id: UUID, val world: UUID)

internal data class LabelSource(
    val id: UUID,
    val world: UUID,
    val entityId: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val distanceSquared: Double,
    val label: GroundItemLabel,
    val alreadyTracked: Boolean = false,
    val passengers: List<Int> = emptyList(),
) {
    fun samePresentation(other: LabelSource) =
        id == other.id && world == other.world && entityId == other.entityId && label == other.label
}

internal interface LabelEnvironment {
    fun schedule(tick: () -> Unit): AutoCloseable
    fun viewers(): List<LabelViewer>
    fun nearby(viewer: LabelViewer): List<LabelSource>
    fun warning(message: String, cause: Exception)
}

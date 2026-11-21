package com.danidipp.sneakygrounditems.display.labels

import java.util.UUID

/**
 * Built labels kept across chunk reads, keyed by ground item. A chunk is read again every cycle, but its items rarely
 * change, so a label is rebuilt only when the inputs it was built from differ. [inputs] are compared with equals.
 * Entries not used for more than [maxIdleTicks] are dropped by [prune]. Nothing is persisted.
 */
internal class LabelMemo(private val maxIdleTicks: Long) {
    private class Entry(val inputs: Any, val label: GroundItemLabel?, var usedAt: Long)

    private val entries = HashMap<UUID, Entry>()

    val size: Int get() = entries.size

    /** The label built from [inputs], with [build] called only when [id] has none or was built from other inputs. */
    fun get(id: UUID, inputs: Any, now: Long, build: () -> GroundItemLabel?): GroundItemLabel? {
        val entry = entries[id]
        if (entry != null && entry.inputs == inputs) {
            entry.usedAt = now
            return entry.label
        }
        val label = build()
        entries[id] = Entry(inputs, label, now)
        return label
    }

    fun prune(now: Long) {
        entries.values.removeIf { now - it.usedAt > maxIdleTicks }
    }

    fun clear() = entries.clear()
}

/** Equal only to a wrapper of the same instance: for values whose identity marks a change, like an item's stack. */
internal class SameInstance(private val value: Any) {
    override fun equals(other: Any?) = other is SameInstance && other.value === value
    override fun hashCode() = System.identityHashCode(value)
}

package com.danidipp.sneakygrounditems.display.labels

import net.kyori.adventure.text.Component
import org.bukkit.block.BlockFace
import java.util.UUID
import kotlin.test.*

class LabelMemoTest {
    private val id = UUID(0, 1)
    private fun label(text: String) = GroundItemLabel(Component.text(text), BlockFace.UP)

    @Test fun unchangedInputsReuseTheBuiltLabelAcrossReads() {
        val memo = LabelMemo(maxIdleTicks = 30)
        var builds = 0
        val first = memo.get(id, "5 Gold", now = 0) { builds++; label("a") }
        assertSame(first, memo.get(id, "5 Gold", now = 10) { builds++; label("b") })
        assertSame(first, memo.get(id, "5 Gold", now = 20) { builds++; label("c") })
        assertEquals(1, builds)
    }

    @Test fun changedInputsRebuild() {
        val memo = LabelMemo(maxIdleTicks = 30)
        memo.get(id, "5 Gold", now = 0) { label("old") }
        val repriced = memo.get(id, "7 Gold", now = 10) { label("new") }
        assertEquals(label("new"), repriced)
        assertEquals(label("new"), memo.get(id, "7 Gold", now = 20) { fail("rebuilt unchanged inputs") })
    }

    @Test fun itemsAreSeparate() {
        val memo = LabelMemo(maxIdleTicks = 30)
        memo.get(id, "x", now = 0) { label("one") }
        assertEquals(label("two"), memo.get(UUID(0, 2), "x", now = 0) { label("two") })
        assertEquals(2, memo.size)
    }

    @Test fun aMalformedLabelIsRememberedUntilItsInputsChange() {
        val memo = LabelMemo(maxIdleTicks = 30)
        var builds = 0
        assertNull(memo.get(id, "broken", now = 0) { builds++; null })
        assertNull(memo.get(id, "broken", now = 10) { builds++; null })
        assertEquals(1, builds)
        assertEquals(label("fixed"), memo.get(id, "fixed", now = 20) { label("fixed") })
    }

    @Test fun pruneDropsOnlyEntriesIdleLongerThanTheLimit() {
        val memo = LabelMemo(maxIdleTicks = 30)
        memo.get(id, "x", now = 0) { label("a") }
        memo.get(UUID(0, 2), "x", now = 0) { label("b") }
        memo.get(id, "x", now = 25) { fail("rebuilt unchanged inputs") } // Still in use.
        memo.prune(30)
        assertEquals(2, memo.size)
        memo.prune(31)
        assertEquals(1, memo.size)
        memo.prune(56)
        assertEquals(0, memo.size)
    }

    @Test fun sameInstanceComparesByIdentityNotEquality() {
        val stack = StringBuilder("stack")
        assertEquals(SameInstance(stack), SameInstance(stack))
        assertNotEquals(SameInstance(stack), SameInstance(StringBuilder("stack")))
        assertNotEquals(SameInstance("a" + stack), SameInstance("a" + stack)) // Equal strings, different instances.
    }
}

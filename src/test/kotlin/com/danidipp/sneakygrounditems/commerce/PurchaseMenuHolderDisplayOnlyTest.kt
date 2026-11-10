package com.danidipp.sneakygrounditems.commerce

import com.sneakyutils.util.DisplayOnlyInventoryHolder
import kotlin.test.Test
import kotlin.test.assertTrue

class PurchaseMenuHolderDisplayOnlyTest {
    @Test
    fun purchaseMenuHolderIsDisplayOnly() {
        assertTrue(DisplayOnlyInventoryHolder::class.java.isAssignableFrom(PurchaseMenuHolder::class.java))
    }
}

package com.danidipp.sneakygrounditems.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroundItemPolicyTest {
    @Test
    fun sellAcceptsOnlyNormalAndSaleItems() {
        assertTrue(GroundItemPolicy.canSell(GroundItemState.NORMAL))
        assertTrue(GroundItemPolicy.canSell(GroundItemState.SALE))
        assertFalse(GroundItemPolicy.canSell(GroundItemState.PAYMENT))
        assertFalse(GroundItemPolicy.canSell(GroundItemState.STOLEN))
    }

    @Test
    fun stealRequiresARecreatableFungibleSale() {
        val eligible = GroundItemPolicy.StealEligibility(
            state = GroundItemState.SALE,
            hasMagicItemId = true,
            magicItemResolves = true,
            hasExpiry = false,
            hasFullDurability = true,
        )

        assertTrue(GroundItemPolicy.canSteal(eligible))
        assertFalse(GroundItemPolicy.canSteal(eligible.copy(state = GroundItemState.NORMAL)))
        assertFalse(GroundItemPolicy.canSteal(eligible.copy(hasMagicItemId = false)))
        assertFalse(GroundItemPolicy.canSteal(eligible.copy(magicItemResolves = false)))
        assertFalse(GroundItemPolicy.canSteal(eligible.copy(hasExpiry = true)))
        assertFalse(GroundItemPolicy.canSteal(eligible.copy(hasFullDurability = false)))
    }

    @Test
    fun saleAndStolenItemsOccupySaleSlots() {
        assertTrue(GroundItemPolicy.occupiesSaleSlot(GroundItemState.SALE))
        assertTrue(GroundItemPolicy.occupiesSaleSlot(GroundItemState.STOLEN))
        assertFalse(GroundItemPolicy.occupiesSaleSlot(GroundItemState.NORMAL))
        assertFalse(GroundItemPolicy.occupiesSaleSlot(GroundItemState.PAYMENT))
    }
}

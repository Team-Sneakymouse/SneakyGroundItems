package com.danidipp.sneakygrounditems.model

internal object GroundItemPolicy {
    fun canSell(state: GroundItemState): Boolean {
        return state == GroundItemState.NORMAL || state == GroundItemState.SALE
    }

    fun canSteal(eligibility: StealEligibility): Boolean {
        return eligibility.state == GroundItemState.SALE &&
            eligibility.hasMagicItemId &&
            eligibility.magicItemResolves &&
            !eligibility.hasExpiry &&
            eligibility.hasFullDurability
    }

    fun occupiesSaleSlot(state: GroundItemState): Boolean {
        return state == GroundItemState.SALE || state == GroundItemState.STOLEN
    }

    data class StealEligibility(
        val state: GroundItemState,
        val hasMagicItemId: Boolean,
        val magicItemResolves: Boolean,
        val hasExpiry: Boolean,
        val hasFullDurability: Boolean,
    )
}

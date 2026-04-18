package com.danidipp.sneakygrounditems

internal enum class GroundItemState(val id: String) {
    NORMAL("normal"),
    SALE("sale"),
    PAYMENT("payment"),
    ;

    val isLockedLike: Boolean
        get() = this != NORMAL

    companion object {
        fun fromId(id: String): GroundItemState? = entries.firstOrNull { it.id == id }
    }
}

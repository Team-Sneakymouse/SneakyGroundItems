package com.danidipp.sneakygrounditems.commerce

import com.sneakyutils.util.DisplayOnlyInventoryHolder
import org.bukkit.inventory.Inventory
import java.util.UUID

/** Display-only purchase confirm GUI; SneakyItems skips scanning its icon slots. */
internal class PurchaseMenuHolder(
    val displayUuid: UUID,
) : DisplayOnlyInventoryHolder {
    private lateinit var inventory: Inventory

    fun bind(inventory: Inventory) {
        this.inventory = inventory
    }

    override fun getInventory(): Inventory = inventory
}

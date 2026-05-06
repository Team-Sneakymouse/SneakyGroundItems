package com.danidipp.sneakygrounditems.commerce

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import java.util.UUID

internal class PurchaseMenuHolder(
    val displayUuid: UUID,
) : InventoryHolder {
    private lateinit var inventory: Inventory

    fun bind(inventory: Inventory) {
        this.inventory = inventory
    }

    override fun getInventory(): Inventory = inventory
}

package com.danidipp.sneakygrounditems.display

import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack

/** Display-only stacks (payment coins, stolen-item notice) whose look is only their `item_model`. */
internal interface DisplayLook {
    fun modelOf(stack: ItemStack): Key?
    fun stackWithModel(model: NamespacedKey): ItemStack
}

internal object PaperDisplayLook : DisplayLook {
    override fun modelOf(stack: ItemStack): Key? = stack.getData(DataComponentTypes.ITEM_MODEL)

    override fun stackWithModel(model: NamespacedKey): ItemStack = ItemStack(Material.RABBIT_FOOT).apply {
        setData(DataComponentTypes.ITEM_MODEL, model)
    }
}

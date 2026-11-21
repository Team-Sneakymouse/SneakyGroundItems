package com.danidipp.sneakygrounditems.pricegun

import com.sneakyutils.util.DisplayOnlyInventoryHolder
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemLore
import io.papermc.paper.datacomponent.item.TooltipDisplay
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack

/**
 * The Price Gun menu (F with the gun). Two rows drawn over per-mode background art (`lom:price_gun_menu_<mode>`):
 * currency modes in slots 1-3, lock modes in 5-7, and for currency modes price steppers and a "type a price" field
 * on the second row. Clicks update the menu in place; clicking the selected mode closes it.
 */
internal class PriceGunMenu {
    /** [gunSlot]: the inventory slot of the gun being edited, so the menu keeps editing that gun. */
    class Holder(val gunSlot: Int) : DisplayOnlyInventoryHolder {
        private lateinit var inventory: Inventory

        fun bind(inventory: Inventory) {
            this.inventory = inventory
        }

        override fun getInventory(): Inventory = inventory
    }

    /** Opens the menu for the gun in [gunSlot]; does nothing when that slot no longer holds a Price Gun. */
    fun open(player: Player, gunSlot: Int) {
        val gun = PriceGunSettings.gunAt(player, gunSlot) ?: return
        val settings = PriceGunSettings.of(gun)
        val holder = Holder(gunSlot)
        val inventory = Bukkit.createInventory(holder, SIZE, LEGACY.deserialize(title(settings)))
        holder.bind(inventory)
        render(inventory, settings)
        player.openInventory(inventory)
    }

    /** Run a tick after the click: it may retitle, close or reopen the view. */
    fun handleClick(player: Player, view: InventoryView, rawSlot: Int, shift: Boolean) {
        if (player.openInventory.topInventory !== view.topInventory) return
        val holder = view.topInventory.holder as? Holder ?: return
        if (rawSlot !in 0 until view.topInventory.size) return
        val gun = PriceGunSettings.gunAt(player, holder.gunSlot) ?: run {
            player.closeInventory()
            return
        }
        val mode = PriceGunSettings.of(gun).mode

        modeSlots[rawSlot]?.let { selected ->
            if (selected == mode) {
                player.closeInventory()
                return
            }
            val settings = PriceGunSettings.update(player, holder.gunSlot) { it.withMode(selected) } ?: return
            PriceGunEffects.playModeSelect(player)
            refresh(view, settings)
            return
        }

        val currency = mode.currency ?: return
        val step = stepSlots[rawSlot]
        if (step != null) {
            val delta = if (shift) step * 10 else step
            val settings = PriceGunSettings.update(player, holder.gunSlot) {
                it.withPrice(currency, it.price(currency).toLong() + delta)
            } ?: return
            PriceGunEffects.playStep(player, delta)
            refresh(view, settings)
            return
        }

        if (rawSlot in TYPE_SLOTS) {
            player.closeInventory()
            PriceGunDialog.show(player, holder.gunSlot, currency, back = { open(it, holder.gunSlot) })
        }
    }

    private fun refresh(view: InventoryView, settings: PriceGunSettings) {
        render(view.topInventory, settings)
        @Suppress("DEPRECATION")
        view.setTitle(title(settings))
    }

    private fun render(inventory: Inventory, settings: PriceGunSettings) {
        val mode = settings.mode
        inventory.clear()
        inventory.setItem(BACKGROUND_SLOT, background(mode.menuArt))
        for ((slot, option) in modeSlots) {
            inventory.setItem(slot, modeButton(settings, option, option == mode))
        }

        val currency = mode.currency ?: return
        val price = settings.price(currency)
        val priceLine = Component.text("Price: ", NamedTextColor.GRAY)
            .append(Component.text("$price $currency", NamedTextColor.YELLOW))
        for ((slot, step) in stepSlots) {
            val sign = if (step > 0) "+" else "-"
            val small = kotlin.math.abs(step)
            inventory.setItem(
                slot,
                icon(
                    model = if (step > 0) PLUS_MODEL else MINUS_MODEL,
                    name = Component.text("$sign$small", if (step > 0) NamedTextColor.GREEN else NamedTextColor.RED),
                    lore = listOf(
                        priceLine,
                        hint("Click", "$sign$small"),
                        hint("Shift-click", "$sign${small * 10}"),
                    ),
                    amount = if (small >= 100) 99 else 1,
                ),
            )
        }
        val typeField = icon(
            model = INVISIBLE_MODEL,
            name = Component.text("Set $currency price", NamedTextColor.GOLD),
            lore = listOf(priceLine, hint("Click", "to type an exact price")),
        )
        for (slot in TYPE_SLOTS) inventory.setItem(slot, typeField)
    }

    private fun modeButton(settings: PriceGunSettings, mode: PriceGunMode, selected: Boolean): ItemStack {
        val lore = buildList {
            add(Component.text(mode.description, NamedTextColor.GRAY))
            mode.currency?.let { currency ->
                add(
                    Component.text("Price: ", NamedTextColor.GRAY)
                        .append(Component.text("${settings.price(currency)} $currency", NamedTextColor.YELLOW)),
                )
            }
            add(
                if (selected) Component.text("▶ Selected", NamedTextColor.GREEN)
                else hint("Click", "to select"),
            )
        }
        return icon(mode.icon, Component.text(mode.label, mode.color), lore, glint = selected)
    }

    private fun hint(action: String, effect: String): Component =
        Component.text(action, NamedTextColor.YELLOW).append(Component.text(" $effect", NamedTextColor.GRAY))

    private fun background(model: NamespacedKey) = ItemStack(Material.PAPER).apply {
        setData(DataComponentTypes.ITEM_MODEL, model)
        setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true))
    }

    private fun icon(
        model: NamespacedKey,
        name: Component,
        lore: List<Component>,
        amount: Int = 1,
        glint: Boolean = false,
    ) = ItemStack(Material.PAPER, amount).apply {
        setData(DataComponentTypes.MAX_STACK_SIZE, 99)
        this.amount = amount
        setData(DataComponentTypes.ITEM_MODEL, model)
        setData(DataComponentTypes.ITEM_NAME, name)
        setData(DataComponentTypes.LORE, ItemLore.lore(lore.map { it.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE) }))
        if (glint) setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)
    }

    companion object {
        private const val SIZE = 18
        private const val BACKGROUND_SLOT = 0
        private val TYPE_SLOTS = 11..15
        private val PLUS_MODEL = NamespacedKey("lom", "price_gun_plus")
        private val MINUS_MODEL = NamespacedKey("lom", "price_gun_minus")
        private val INVISIBLE_MODEL = NamespacedKey("lom", "invisible")
        private val LEGACY = LegacyComponentSerializer.legacySection()

        private val modeSlots = mapOf(
            1 to PriceGunMode.GOLD,
            2 to PriceGunMode.SILVER,
            3 to PriceGunMode.PENNY,
            5 to PriceGunMode.LOCK,
            6 to PriceGunMode.UNLOCK,
            7 to PriceGunMode.SHOWOFF,
        )

        /** Slot to the plain-click step; shift-click steps ten times as far. */
        private val stepSlots = mapOf(9 to -100, 10 to -1, 16 to 1, 17 to 100)

        /**
         * The live price sits in the art's display box: `<shift:N>` is CraftEngine's offset tag, as the prototype's
         * MenuSpell title used. Lock modes draw their own caption, so the title is blank.
         */
        fun title(settings: PriceGunSettings): String {
            val currency = settings.mode.currency ?: return " "
            return "<shift:73>§6${settings.price(currency)}"
        }
    }
}

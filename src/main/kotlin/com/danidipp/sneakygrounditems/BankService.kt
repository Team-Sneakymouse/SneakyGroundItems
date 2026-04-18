package com.danidipp.sneakygrounditems

import com.nisovin.magicspells.MagicSpells
import org.bukkit.entity.Player

internal class BankService {
    fun hasVariable(currency: Currency): Boolean {
        return MagicSpells.getVariableManager()?.getVariable(currency.variableName) != null
    }

    fun getBalance(player: Player, currency: Currency): Double? {
        val manager = MagicSpells.getVariableManager() ?: return null
        if (manager.getVariable(currency.variableName) == null) return null
        return manager.getValue(currency.variableName, player)
    }

    fun withdraw(player: Player, currency: Currency, amount: Int): Boolean {
        val manager = MagicSpells.getVariableManager() ?: return false
        if (manager.getVariable(currency.variableName) == null) return false

        val balance = manager.getValue(currency.variableName, player)
        if (balance + 1.0e-6 < amount.toDouble()) return false
        manager.set(currency.variableName, player, balance - amount.toDouble())
        return true
    }

    fun deposit(player: Player, currency: Currency, amount: Int): Boolean {
        val manager = MagicSpells.getVariableManager() ?: return false
        if (manager.getVariable(currency.variableName) == null) return false

        val balance = manager.getValue(currency.variableName, player)
        manager.set(currency.variableName, player, balance + amount.toDouble())
        return true
    }
}

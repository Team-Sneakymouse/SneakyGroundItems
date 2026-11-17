package com.danidipp.sneakygrounditems.commerce

import com.sneakyvariables.api.Scope
import com.sneakyvariables.api.SneakyVariables
import com.sneakyvariables.api.VariableOwner
import org.bukkit.entity.Player

internal class BankService {
    fun hasVariable(currency: Currency): Boolean =
        SneakyVariables.api().definition(currency.variableName) != null

    fun getBalance(player: Player, currency: Currency): Double? {
        val owner = owner(player, currency.variableName) ?: return null
        return owner.getNumber(currency.variableName)
    }

    fun withdraw(player: Player, currency: Currency, amount: Int): Boolean {
        val owner = owner(player, currency.variableName) ?: return false
        val balance = owner.getNumber(currency.variableName)
        if (balance + 1.0e-6 < amount.toDouble()) return false
        owner.setNumber(currency.variableName, balance - amount.toDouble())
        return true
    }

    fun deposit(player: Player, currency: Currency, amount: Int): Boolean {
        val owner = owner(player, currency.variableName) ?: return false
        owner.add(currency.variableName, amount.toDouble())
        return true
    }

    private fun owner(player: Player, name: String): VariableOwner? {
        val def = SneakyVariables.api().definition(name) ?: return null
        return when (def.scope()) {
            Scope.PLAYER -> SneakyVariables.api().player(player)
            Scope.CHARACTER -> SneakyVariables.api().character(player)
            Scope.GLOBAL -> SneakyVariables.api().global()
        }
    }
}

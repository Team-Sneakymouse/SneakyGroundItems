package com.danidipp.sneakygrounditems

import org.bukkit.command.CommandSender

object GroundItemPermissions {
    const val LOCK_PERMISSION = "sneakygrounditems.command.grounditem.lock"
    const val UNLOCK_PERMISSION = "sneakygrounditems.command.grounditem.unlock"
    const val POP_PERMISSION = "sneakygrounditems.command.grounditem.pop"
    const val ADMIN_PERMISSION = "sneakygrounditems.command.grounditem.admin"
    private const val LEGACY_LOCK_PERMISSION = "sneakymisc.command.grounditem.lock"
    private const val LEGACY_UNLOCK_PERMISSION = "sneakymisc.command.grounditem.unlock"
    private const val LEGACY_POP_PERMISSION = "sneakymisc.command.grounditem.pop"
    private const val LEGACY_ADMIN_PERMISSION = "sneakymisc.command.grounditem.admin"

    fun hasLockPermission(sender: CommandSender): Boolean {
        return sender.hasAnyPermission(LOCK_PERMISSION, LEGACY_LOCK_PERMISSION)
    }

    fun hasUnlockPermission(sender: CommandSender): Boolean {
        return sender.hasAnyPermission(UNLOCK_PERMISSION, LEGACY_UNLOCK_PERMISSION)
    }

    fun hasPopPermission(sender: CommandSender): Boolean {
        return sender.hasAnyPermission(POP_PERMISSION, LEGACY_POP_PERMISSION)
    }

    fun hasAdminPermission(sender: CommandSender): Boolean {
        return sender.hasAnyPermission(ADMIN_PERMISSION, LEGACY_ADMIN_PERMISSION)
    }

    private fun CommandSender.hasAnyPermission(vararg permissions: String): Boolean = permissions.any(::hasPermission)
}

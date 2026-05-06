package com.danidipp.sneakygrounditems.model

import net.kyori.adventure.text.Component

internal data class GroundItemCommandResult(
    val success: Boolean,
    val message: Component?,
)

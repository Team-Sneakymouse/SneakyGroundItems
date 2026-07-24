package com.danidipp.sneakygrounditems.model

import com.danidipp.sneakygrounditems.commerce.Currency
import org.joml.Vector3f

internal data class SaleSnapshot(
    val magicItemId: String,
    val itemAmount: Int,
    val currency: Currency,
    val price: Int,
    val originalScale: Vector3f,
)

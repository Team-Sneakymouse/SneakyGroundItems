# SneakyGroundItems

SneakyGroundItems manages player-placed ground items and their commerce lifecycle inside plots.

## Language

**Sale item**:
A ground item that is currently listed for sale on a plot. Each sale item occupies one slot in the plot sale-item limit.
_Avoid_: Sellable item, listed ground item

**Locked ground item**:
A normal ground item with any lock mode: temporary, permanent, or plot.
_Avoid_: Bound item

**Nearby ground item**:
A loaded ground-item display whose location lies within a three-dimensional spherical radius centered on the player. Each display counts once regardless of its represented stack amount, and counting never loads chunks.
_Avoid_: Horizontally nearby ground item

**Stolen item**:
A ground representation marking that a sale item has been stolen and may be restored by a plot member. It preserves the sale listing and its sale-limit slot, displays the dark-red label “Stolen Item,” and does not represent a collectible physical item.
_Avoid_: Stolen goods, stolen-goods token

**Stolen-goods token**:
The MagicItem awarded to a player who successfully steals a sale item. If the player cannot carry it, it is dropped at their feet.
_Avoid_: Stolen item

**Stealable sale item**:
A sale item that can be recreated from its MagicItem definition without losing meaningful instance state. Expiry-bearing items and partially damaged items are not stealable.
_Avoid_: Any sale item

**Sell**:
The action that lists a normal ground item for sale or reprices an existing sale item. Payment and stolen items are never valid sell targets.
_Avoid_: Reprice as a separate action

**Pop**:
A player-controlled cleanup that returns nearby unlocked normal ground items to physical item form. It never affects locked, sale, payment, or stolen items.
_Avoid_: Delete, purge

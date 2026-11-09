# Diagnostics

Measured by [SneakyDiagnostics](https://github.com/Team-Sneakymouse/SneakyDiagnostics) when it is installed (optional):
`/diagnostics plugins start 60 SneakyGroundItems`, or `/diagnostics all start 60`. Setup and rules:
[plugin-providers.md](https://github.com/Team-Sneakymouse/SneakyDiagnostics/blob/main/docs/plugin-providers.md).
Instrumentation version `sneakygrounditems-ticks-1`.

The label task (every tick) is the measured path:

| Kind | Names |
|---|---|
| Sections | `tick.labels` (the whole task), inside it `labels.list-viewers`, per refreshed viewer `labels.nearby` (with `labels.entity-query`, the loaded-entity query around the player, and `labels.derive`, building a label on a cache miss including `ItemStack.displayName()`), and `labels.update` (hide/show packets for that viewer) |
| Counts | `labels.viewers-refreshed` (viewers whose 10-tick slot came up), `labels.entities-queried` (entities returned by the query), `labels.candidates` (labelled ground items in range), `labels.desired`, `labels.created`, `labels.changed`, `labels.hidden`, `labels.creations-deferred` (over the 64 per-tick creation budget) |
| Levels | `labels.viewers` (online players), `labels.sessions`, `labels.visible` (client-only labels currently shown) |

`labels.derive` calls equal display names built: the label cache is cleared every tick, so a candidate seen by two
refreshing viewers in one tick builds once, and one seen in ten consecutive ticks builds up to ten times.

Not measured: placement, commerce, theft and chunk-load handling (only the label scan is instrumented), and
`entities.owned`: ground items are tracked by entity PDC with no in-memory registry, and counting them would need a
world scan. The entity census reports them by owner hint. The measurement reads only entities the label scan already
queries; it loads no chunks.

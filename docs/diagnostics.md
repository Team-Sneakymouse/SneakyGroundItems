# Diagnostics

Measured by [SneakyDiagnostics](https://github.com/Team-Sneakymouse/SneakyDiagnostics) when it is installed (optional):
`/diagnostics plugins start 60 SneakyGroundItems`, or `/diagnostics all start 60`. Setup and rules:
[plugin-providers.md](https://github.com/Team-Sneakymouse/SneakyDiagnostics/blob/main/docs/plugin-providers.md).
Instrumentation version `sneakygrounditems-ticks-3`.

The label task (every tick) is the measured path:

| Kind | Names |
|---|---|
| Sections | `tick.labels` (the whole task), inside it `labels.list-viewers`, per refreshed viewer `labels.nearby` (with `labels.chunk-read`, reading one chunk's labelled ground items and their ground-item PDC when its shared entry is missing or 10 ticks old, and `labels.derive`, building one label including `ItemStack.displayName()` when an item within range of a viewer has no built label or its label inputs changed), and `labels.update` (hide/show packets for that viewer) |
| Counts | `labels.viewers-refreshed` (viewers whose 10-tick slot came up), `labels.chunks-checked` (chunks passing the horizontal check, cached or not), `labels.chunks-read` (chunks actually read), `labels.chunks-unloaded` (chunks skipped because they or their entities are not loaded), `labels.entities-read` (entities in the chunks read), `labels.candidates` (labelled ground items in exact range), `labels.desired`, `labels.created`, `labels.changed`, `labels.hidden`, `labels.creations-deferred` (over the 64 per-tick creation budget), `labels.reused` (labels taken from an earlier build because their inputs were unchanged) |
| Levels | `labels.viewers` (online players), `labels.sessions`, `labels.visible` (client-only labels currently shown) |

`labels.derive` calls equal display names built. A built label is kept, by ground item, until its inputs change (stack
instance, state, showoff, currency, price, face) or no viewer has been near the item for 30 ticks; items in a read chunk
that no viewer is near build nothing. Each chunk read looks the label up once: `labels.derive` + `labels.reused` per cycle
is the number of labelled items within range of at least one viewer.
Shared reads reused = `labels.chunks-checked` − `labels.chunks-read` − `labels.chunks-unloaded`.

Not measured: placement, commerce, theft and chunk-load handling (only the label scan is instrumented), and
`entities.owned`: ground items are tracked by entity PDC with no in-memory registry, and counting them would need a
world scan. The entity census reports them by owner hint. The measurement reads only entities the label scan already
queries; it loads no chunks.

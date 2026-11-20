# Diagnostics

Measured by [SneakyDiagnostics](https://github.com/Team-Sneakymouse/SneakyDiagnostics) when it is installed (optional):
`/diagnostics plugins start 60 SneakyGroundItems`, or `/diagnostics all start 60`. Setup and rules:
[plugin-providers.md](https://github.com/Team-Sneakymouse/SneakyDiagnostics/blob/main/docs/plugin-providers.md).
Instrumentation version `sneakygrounditems-ticks-2`.

The label task (every tick) is the measured path:

| Kind | Names |
|---|---|
| Sections | `tick.labels` (the whole task), inside it `labels.list-viewers`, per refreshed viewer `labels.nearby` (with `labels.chunk-read`, reading one chunk's labelled ground items when its shared entry is missing or 10 ticks old, and inside that `labels.derive`, building one label including `ItemStack.displayName()`), and `labels.update` (hide/show packets for that viewer) |
| Counts | `labels.viewers-refreshed` (viewers whose 10-tick slot came up), `labels.chunks-checked` (chunks passing the horizontal check, cached or not), `labels.chunks-read` (chunks actually read), `labels.entities-read` (entities in the chunks read), `labels.candidates` (labelled ground items in exact range), `labels.desired`, `labels.created`, `labels.changed`, `labels.hidden`, `labels.creations-deferred` (over the 64 per-tick creation budget) |
| Levels | `labels.viewers` (online players), `labels.sessions`, `labels.visible` (client-only labels currently shown) |

`labels.derive` calls equal display names built: each labelled item in a chunk that some viewer is near is built once
per 10-tick cycle, however many viewers are near it. `labels.chunks-checked` minus `labels.chunks-read` is the number of
shared reads reused.

Not measured: placement, commerce, theft and chunk-load handling (only the label scan is instrumented), and
`entities.owned`: ground items are tracked by entity PDC with no in-memory registry, and counting them would need a
world scan. The entity census reports them by owner hint. The measurement reads only entities the label scan already
queries; it loads no chunks.

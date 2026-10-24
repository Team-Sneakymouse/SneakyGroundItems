# Client-only ground-item labels

Status: implemented, 2026-10-24, following the confirmed shared design. Automated behavior and native packet tests cover the implementation; live client rendering and representative server performance remain to be checked before deployment.

## Confirmed behavior

- Ground items remain real, persistent item displays. Native entity data and entity PDC are the only source of truth. The global display tracking range remains 128.
- Ground-item labels are client-only text displays, derived from the source item. There are no new world text entities, persisted companions, label files or databases.
- Labels appear within 16 blocks, remain until beyond 20 blocks, and observe committed item changes on the next 10-tick refresh. Distances are three-dimensional and within one world.
- Commerce and theft have no dependency on label presentation. A valid transaction succeeds independently of label visibility or packet delivery.
- Labels ride their real source on the client using vanilla passenger packets. Minecraft handles passenger positioning. There is no editor integration, model-transform mirroring or alternate positioning mode.
- Use version-pinned Paperweight access to server internals for the packet implementation. No additional runtime packet-library plugin or embedded packet library is planned.
- Labels are disposable. Their direct customization has no independent meaning; lasting state belongs on the ground item.

## Ownership and code organization

All paths below are relative to src/main/kotlin/com/danidipp/sneakygrounditems/.

### Existing owners retain real-item behavior

- persistence/PersistenceService.kt reads/writes authoritative entity PDC. Old companion links become migration-only data.
- commerce/GroundItemCommerceService.kt and GroundItemTheftService.kt retain business validation, payments, state transitions and actual transaction rollback.
- display/GroundItemDisplayService.kt retains deliberate source-item normalization, placement-face repair, scaling and expiry processing. Its label-spawning responsibilities move out.
- display/PlacementService.kt retains real-item placement.
- runtime/GroundItemRuntimeStateService.kt retains cooldowns and resolution guards. Label sessions have a different lifetime and do not belong here.
- migration/ retains legacy cleanup. Existing legacy-interaction migration behavior survives.

Separate the current syncCompanionText logic before deleting calls: it mixes validation, source rescaling, text construction and spawning behind one Boolean result. Keep necessary source-item changes and genuine validation failures. Remove only the branches that roll back because derived presentation failed. A purchase must not refund, duplicate delivery or lose payment state because its label is unavailable.

### One deep ground-item label module

Use display/labels/ as the cohesive package:

- GroundItemLabels.kt owns the shared scheduler, source observations, per-viewer membership, ephemeral sessions and label lifetime.
- GroundItemLabel.kt holds immutable derived content/layout and its formatting rules. This is an internal value, not another public module callers must coordinate.
- LabelPackets.kt defines the internal seam between label behavior and packet mechanics.
- packet/PaperLabelPackets.kt is the version-specific packet adapter.

The external interface is intentionally limited to starting and closing the label module. Bootstrap owns that lifetime. Commerce, theft and interaction callers do not call refresh, invalidate or forget methods on labels.

The module observes the world and source entity data independently. Content derivation, membership, packet sequencing and retries concentrate behind this interface. Exact private file splits can follow implementation size; do not create a general virtual-entity framework.

The packet seam is justified by two adapters: the production Paper adapter and a recording adapter used by behavioral tests. It stays internal to labels. Callers never learn packet types, metadata indices or client entity identities.

### Bootstrap retains lifetime ownership

bootstrap/GroundItemPluginBootstrap.kt creates the label module and retains an owned runtime handle. SneakyGroundItems.kt closes that handle during disable. Starting/stopping packet observation, scheduler work and sessions stays inside the label module.

This is the minimal lifecycle change required by candidates 1 and 2, rather than a wider bootstrap rewrite.

## Read-only derivation from source items

Current native entity data and existing PDC contain the inputs:

- Stack amount and item display name.
- Commerce state, currency and price.
- Placement face and existing placement conventions.
- Original MagicItem ID, amount and sale scale used by payment/restoration.
- Creator and lock metadata, which remain authoritative on the item.

Reuse existing keys and encodings. Do not persist formatted text, viewer lists, packet identities or label UUIDs.

Normal items have no label. Sale/payment text requires its own valid fields. The fixed stolen-item notice can render without restoration metadata; restoring that item still requires a valid snapshot.

Presentation reads do not normalize source items, clear malformed PDC or repair missing restoration state. An unreadable label is omitted with rate-limited diagnostics; business validation stays with its existing owner. Existing explicit source normalization must remain distinguishable from label observation.

## Sampling and proximity

One shared server-thread scheduler distributes player observations across a 10-tick cycle. Use Bukkit's spatial queries over loaded entities around each player, followed by exact spherical distance and ground-item/state filtering. No second global location index, forced chunk loads or per-item tasks.

For each viewer:

1. Discover eligible source items inside 16 blocks.
2. Retain previous sources through 20 blocks while valid, in the same world and visible to that player.
3. Read current label inputs; deduplicate derivation for sources shared by viewers.
4. Diff desired content/membership against that viewer's current session.

State transitions are observed rather than pushed by commerce. Item removal/untracking can tear down its virtual label as part of packet/entity lifecycle handling without waiting for another business call.

Queues may distribute a dense arrival across ticks. Revalidate viewer, source and session generation before emitting queued work. If creation is backlogged, labels can take longer than a normal refresh cycle to appear; that does not affect gameplay validity.

## Client sessions and virtual passengers

Each active session is scoped to a player connection/world generation and real source identity. Keep source UUID/world identity distinct from its network entity ID. Allocate virtual identities without colliding with real entity IDs; use the server's allocation mechanism rather than arbitrary guessed IDs. Runtime identities are never persisted.

A source shared by viewers shares derived content, but delivery and visibility are per viewer. Label existence on one client does not imply existence on another.

The packet adapter owns protocol details:

- Observe the ordered outgoing stream so the real parent is available before its label is spawned and mounted. Server tracking intent alone is not sufficient evidence of packet ordering.
- Spawn the client text display, send its initial metadata and attach it as a client passenger. Use the existing face-specific offset, scale, billboard and wall orientation.
- Passenger packets replace the complete list. Preserve the real passenger IDs already intended for that viewer and merge only that session's virtual label. Later vanilla passenger updates must keep the virtual passenger while the label is active.
- On label removal while the parent remains available, restore the ordinary passenger list and remove the virtual entity. If the parent has gone away, remove the virtual label without sending a stale mount.
- Reset sessions on disconnect, world reset/respawn and source untracking. Recreate eligible labels only after the parent is available again.
- Restrict interception to managed source/session identities; unrelated traffic passes through unchanged.

Do not call addPassenger on the real server item for a virtual label. No label is registered in a world, tick list, tracker or persistent passenger tree.

Minecraft supplies the normal client passenger movement. Its model-transformation semantics remain unchanged. The plugin does not simulate visual inheritance.

All Bukkit world/entity/PDC observations stay on the server thread. Packet callbacks use immutable published session information or event-loop-owned state, and marshal lifecycle observations back to the module. Define this ownership explicitly; never read live Bukkit entities from a network callback. Session generations invalidate delayed work after reconnects or world changes.

## Failures and cleanup

Packet or metadata failures are local to presentation: use rate-limited diagnostics and bounded retry frequency. Never roll back commerce or mutate source PDC to compensate.

A source deleted with a vanilla command remains deleted. The label module never recreates items. Since new labels exist only on clients, server entity selectors, /kill and /data do not target the labels themselves; the real source remains normally manageable.

On disable, stop accepting work, invalidate queued generations, restore ordinary passenger lists where applicable, remove virtual labels from connected clients, detach packet observation, cancel scheduling and release maps. Cleanup is idempotent and does not change item data.

No disk flush is needed for labels. A reconnect or restart rebuilds them from persistent source entities.

## Migration

At enable and subsequent entity loads, remove only this plugin's identified old server text companions and clear obsolete companion references on source items. Preserve all business, lock and restoration data.

Never trust copied companion UUIDs as ownership of another item's presentation. Cleanup does not load a partner's chunk. Unrelated text displays remain untouched.

Process unloaded chunks when they load; no whole-world migration or scan is required. Preserve existing legacy-interaction migration. New client-only labels cannot be confused with old world text entities because no new world text entities exist.

## Tests at the seams

Exercise the label module through its interface using controlled source observations/scheduling and a recording packet adapter. Assert observable delivery and item outcomes, not private map shapes or packet-encoding implementation details.

Meaningful label cases:

- Enter at 16, retain through 20, leave beyond 20; independent viewers.
- Sale/payment/stolen content changes while stationary and normal state removes the label.
- Missing restoration metadata still permits the fixed stolen notice.
- Parent not yet available, untracked/retracked and deleted during queued creation.
- Spawn/metadata/mount ordering and preservation of real passengers across vanilla updates.
- Quit/rejoin, respawn/world reset and stale-session work.
- Packet failure/retry and repeated close without source mutation.

Transaction cases must exercise actual sale, purchase/payment, theft and restoration behavior with presentation absent. Genuine invalid-item and payment failures still fail; valid transactions do not refund, duplicate items or lose business PDC because presentation is absent.

Use a vanilla client against the target Paper version to verify metadata, attachment and floor/wall/ceiling layout. A recording adapter cannot establish client rendering correctness. Verify ordinary movement, teleport and save/reload of the real source. No editor-specific implementation or test suite is required.

Measure before/after using comparable activity: world text counts, label task cost, packet churn, tracker time per tick and MSPT. Client-only labels remove label contributions to server entity tracking; item-display tracking remains.

## Implementation sequence

1. Separate authoritative item behavior from derived text and remove presentation-induced transaction rollback.
2. Build the deep label module and recording adapter tests around observed source state and viewer sessions.
3. Add pinned Paperweight tooling and the private Paper packet adapter; verify stream ordering, identity allocation and passenger-list merging.
4. Wire retained bootstrap lifetime and legacy companion cleanup.
5. Run meaningful behavioral tests and validate rendering with a vanilla client on target Paper.
6. Compare representative performance captures before deployment.

Exact development-bundle availability and internal packet signatures are build-time checks. Supporting multiple server versions is not part of this change.

## Decisions complete

The agreed direction combines candidates 1 and 2: independent presentation plus one deep client-only label module. The 10-tick observation model and version-pinned Paperweight approach are accepted. No further product choices are open; remaining details are implementation checks.

## References

- [Paperweight development tooling](https://docs.papermc.io/paper/dev/userdev/): development bundles and access to version-specific internals.
- [Minecraft internals](https://docs.papermc.io/paper/dev/internals/): internal code is version-sensitive.
- [Passenger packet structure](https://github.com/retrooper/packetevents/blob/2.0/api/src/main/java/com/github/retrooper/packetevents/wrapper/play/server/WrapperPlayServerSetPassengers.java): parent entity ID and complete passenger list; referenced for protocol shape, not a planned dependency.
- [Paper passenger updates](https://github.com/PaperMC/Paper/blob/main/paper-server/patches/sources/net/minecraft/server/level/ServerEntity.java.patch): vanilla passenger-list updates must coexist with the virtual label.

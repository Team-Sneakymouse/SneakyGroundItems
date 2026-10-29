# Agent instructions (SneakyGroundItems)

Team Sneakymouse / Lords of Minecraft plugin. Follow the shared conventions in EmptyPlugin's AGENTS.md.

## Repo-specific rules

- **PDC schema is a public contract.** Never rename or retype ground_item_* PDC keys or the ground_item_kind values; only add keys, and read old values defensively. SneakyBalls and live worlds depend on them.
- **Use glossary terms.** Follow CONTEXT.md terms: 'sale item', 'locked ground item', 'stolen item', 'pop'. Pop never touches locked, sale, payment or stolen items; sell never targets payment or stolen items.
- **Currency changes are atomic on main thread.** Payment flows must verify balance and withdraw in one synchronous step through BankService and roll back on failure (refund by deposit); never await or schedule between check and withdraw.
- **Never load chunks to count or search.** Lookups (nearby counts, pop, range) work on loaded displays only and must not load chunks.
- **Migration is chunk-batched and resumable.** Changes to GroundItemMigrationService must keep dry-run mode, batch size (MIGRATION_BATCH_SIZE) and the migratedDisplayIds guard so re-runs are idempotent.
- **Optional deps behind runCatching.** BagOfHolding, WorldGuard and CoreProtect calls must tolerate absence (runCatching { ... }.getOrNull()); do not make them required in paper-plugin.yml.

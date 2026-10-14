# Autonomous Villages — implementation plan

Minecraft Java 26.2, Fabric Loader 0.19.5, Fabric API 0.161.0+26.2, Java 25.
Separate server mod using the existing Navigation AI villager API. No production saves or installations are modified. All gameplay validation uses a fresh isolated dedicated server.

## Acceptance plan

Completed 2026-10-14: all six stages below are implemented. The final unchanged release JAR passed 19 real-server scenarios, seven unit tests, restart with empty-server auto-pause enabled, and corrupt-metadata preservation. See VALIDATION.md and docs/validation/2026-10-14/summary.json. The installation bundle contains the two tested release mods and documentation; the validation mod is excluded.

1. Select a populated village near the command source using beds and real villagers; bound the managed territory, reject overlapping claims, preserve vanilla identities/trades. Commands: `/avillage claim <name> [radius]`, `status`, `pause`, `resume`, `release`, `storage`.
2. Persist village metadata atomically, restore membership after restart, maintain nonpersistent chunk simulation tickets while active, safely release control/tickets on pause/release. Leave native sleep, trades and breeding available.
3. Coordinate shared work through real chests, per-block reservations and bounded incremental world scans. Residents physically walk to work/storage; no remote inventory transfers.
4. Implement renewable farming/replanting, food distribution to native villager inventories, wood collection/replanting, stone/ore mining, actual recipe crafting and furnace processing, tools and defensive equipment.
5. Add resource-paid infrastructure (storage, workbench, furnace, lighting, simple new shelters/beds), needs-based jobs, nighttime return and native sleep, threat response and population capacity limits. No free items, forced reproduction, resurrection or instant growth.
6. Verify material conservation and village lifecycle with unit tests and real-server scenarios, including restart, job cancellation, storage, farming, construction, defense and command failures. Package mod + tested library + instructions.

Autonomy is resource dependent: no simulated world can produce wood/seeds/ore/wool where none exist. Status must explain missing prerequisites. Building is a finite simple shelter design, not arbitrary town planning. Residents remain mortal. Raids and trading retain vanilla rules.

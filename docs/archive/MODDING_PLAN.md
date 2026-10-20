> **Archived (0.2.x).** Moved here on 20 October 2026 (was `MODDING_PLAN.md`); no longer maintained.
> It describes the mod before 0.3.0-dev; current docs: [README](../../README.md), [PLAN-v0.3](../PLAN-v0.3.md), [ARCHITECTURE](../ARCHITECTURE.md).

# Autonomous Villages — implementation plan

Current review-fix candidate: 0.2.1 (2026-10-16). The nine review findings and additional night-shelter/wool issues are addressed; the current immutable pair passed 29 mod unit tests, 103 Navigation AI tests and 88 full server checks. The fresh Normal/growth client was stopped at the user’s request after 51065 ticks; full acceptance remains unconfirmed. No further tests are authorized by the latest instruction; package, commit and push the current build. See VALIDATION.md for current results and exact hashes; older milestones below describe earlier builds and do not certify this candidate.

Minecraft Java 26.2, Fabric Loader 0.19.5, Fabric API 0.161.0+26.2, Java 25.
Separate server mod using the existing Navigation AI villager API. No production saves or installations are modified. Validation uses isolated dedicated servers and a real graphical client with copied mods/worlds. Runners share an exclusive lock: only one Minecraft instance at a time.

## Acceptance plan

Completed 2026-10-14: all six stages below are implemented. The final unchanged release JAR passed 19 real-server scenarios, seven unit tests, restart with empty-server auto-pause enabled, and corrupt-metadata preservation. See VALIDATION.md and docs/validation/2026-10-14/summary.json. The installation bundle contains the two tested release mods and documentation; the validation mod is excluded.

0.1.1 follow-up: corrected the library's Fabric environment so single-player can load it; required library >=0.1.1. Repeated server checks and added an actual client test with the user's copied mod set, fresh world creation and reopening, native villager tracking, and exact backpack preservation. Evidence: docs/validation/2026-10-14-0.1.1/.

1. Select a populated village near the command source using beds and real villagers; bound the managed territory, reject overlapping claims, preserve vanilla identities/trades. Commands: `/avillage claim <name> [radius]`, `status`, `pause`, `resume`, `release`, `storage`.
2. Persist village metadata atomically, restore membership after restart, maintain nonpersistent chunk simulation tickets while active, safely release control/tickets on pause/release. Leave native sleep, trades and breeding available.
3. Coordinate shared work through real chests, per-block reservations and bounded incremental world scans. Residents physically walk to work/storage; no remote inventory transfers.
4. Implement renewable farming/replanting, food distribution to native villager inventories, wood collection/replanting, stone/ore mining, actual recipe crafting and furnace processing, tools and defensive equipment.
5. Add resource-paid infrastructure (storage, workbench, furnace, lighting, simple new shelters/beds), needs-based jobs, nighttime return and native sleep, threat response and population capacity limits. No free items, forced reproduction, resurrection or instant growth.
6. Verify material conservation and village lifecycle with unit tests and real-server scenarios, including restart, job cancellation, storage, farming, construction, defense and command failures. Package mod + tested library + instructions.

Autonomy is resource dependent: no simulated world can produce wood/seeds/ore/wool where none exist. Status must explain missing prerequisites. Building is a finite simple shelter design, not arbitrary town planning. Residents remain mortal. Raids and trading retain vanilla rules.


## 0.2 settlement simulation — user correction 2026-10-15

The previous radius-limited needs loop does not meet the requested colony simulation. Do not describe a partial slice as complete. Acceptance is observable ongoing autonomous behavior in an ordinary generated village.

1. Discover the complete generated village from its structure footprint and connected home/work/meeting POIs. Migrate existing claims automatically, enroll all residents, and keep only settlement/worksite chunks simulated. No player-selected fixed radius.
2. Finish atomic mining actions before scheduled breaks, retain unfinished tree jobs, record actual interruption reasons, and show current roles/tasks in world.
3. Persist a town construction/repair ledger. Build a separate functional warehouse from real supplies, including foundation, entrance, doors, chests and work area. Physically use and animate shared chests. Automatically adopt village storage.
4. Snapshot existing houses, repair subsequent damage from actual materials, and schedule paid visible improvements. Preserve buildings across restarts and protect them from resource harvesting.
5. Coordinate farmers, builders, woodcutters, stock management and equipped solo/group mining/foraging expeditions. Find resources beyond town, return and unload; bootstrap without player item donations or manual chest assignment.
6. Validate production loops in an actual graphical client on a generated village and a copy of the user's village. Capture native screenshots/time lapse, inspect them, break a house and observe repair, observe upgrades, storage growth, trips and restart recovery. Isolated copies only. Record unmet criteria honestly.

Implementation continues until these acceptance scenarios are demonstrated. The existing 0.1.1 tests remain regression coverage, not proof of this new acceptance scope.

2026-10-15 22:02 checkpoint: in the graphical generated-world lineage, all six original adults remain alive; the separate warehouse is complete, damaged house parts were physically repaired, stone floor reinforcement completed, and an equipped resident travelled to wild sheep, sheared them, returned and unloaded five brown wool into a real chest. A brown bed was crafted from that wool. Ordinary builders now request wool, craft matching-colour beds at real workbenches and install paid paired blocks; the dedicated fixture passes. Complete new housing in the unassisted client, final unchanged-build cold-start coverage, the user's world copy, and Normal-night survival remain acceptance requirements. Full server regression is being repeated after the recipe and movement changes. No 0.2 release/install yet.

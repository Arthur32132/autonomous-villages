# Agent rules for this repository

## Current scope — 2026-10-18

Work follows [docs/PLAN-v0.3.md](docs/PLAN-v0.3.md); progress, numbers and open questions are in
[docs/WORKLOG.md](docs/WORKLOG.md). The owner allowed: breaking 0.2.x saves (they are backed up and
their residents released), removing subsystems, separate PRs for Navigation AI, Russian in-game text,
and merging to `master` once unit tests are green and soak results are not worse than before.
Releases and publishing remain the owner's.

Read the workspace-level `../AGENTS.md` when available and obey the latest human instruction.

## Testing

- Unit tests: `./gradlew :test` (mod) and `./gradlew :navigationAi:test` (library). `./gradlew test`
  runs both; `--tests` filters need the project prefix (`:test --tests ...`).
- Game behaviour is checked with the headless soak harness in `tools/soak` (see its README):
  a disposable dedicated server per run, metrics observed in the world, reports in `build/soak-reports`.
  `python3 tools/soak/digest.py <report> --workers` summarises a timeline.
- Run soak at the default 100 TPS. Navigation AI plans routes within a share of real tick time, so at
  400 TPS villagers wait far longer for routes than in real play and results look worse than they are.
- Compare builds on the same seeds, difficulty and tick rate. `--jars <folder>` runs any build;
  `build/baseline-jars` holds 0.2.2 with its original library.
- Memory: a soak server needs about 2.5 GB (G1, `-Xmx2G`). In the cloud container keep at most three
  servers running together and stop Gradle daemons (`./gradlew --stop`) before large batches.
- `tools/soak/stop.sh <batch>` stops a batch by its lab folder; do not `pkill -f` with a pattern that
  also matches your own shell.
- Labs are deleted after each run unless `--keep-lab`; remove kept labs when done.

## Hygiene

Preserve the owner's worlds, profiles, caches and uncommitted work. Do not commit game binaries,
worlds, copied profiles or complete logs. Stop Gradle daemons after builds on shared machines.

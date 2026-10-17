# Validation storage and process rules

## Latest human scope — 2026-10-17

The latest human request is to fix all three roadmap item 2 review findings,
close that roadmap item, commit and push. Preserve the human restriction:
no tests, builds or Minecraft launches for these review fixes; use static checks.
The implementation and short checks are recorded in
docs/validation/2026-10-17-roadmap-2/REPORT.md. Further long colony tests are not
authorized by that result. Keep historical PASS results tied to their own JARs;
the local 0.2.2 roadmap candidate is not full autonomy acceptance.

Read the workspace-level `../AGENTS.md` when available and obey the latest human
instruction. The mod is not release-ready; disk cleanup does not authorize
resuming gameplay development or starting Minecraft.

- Use the existing client/server validation runners and their shared exclusive
  lock. Only one test Minecraft process may run at a time.
- Both runners use `tools/test_artifacts.py`: remove old disposable labs before
  a run and delete copied profiles, saves, mods and server libraries afterward,
  including failed setup. Do not bypass this cleanup or create alternative labs.
- Limits: one active lab, 1 GiB per lab, at least 2 GiB free space; compact
  generated evidence at most 20 reports / 128 MiB combined. The budget monitor
  checks every 10 seconds and requests test shutdown on a violation.
- `--keep-lab` is an explicit temporary debugging option: retain only the current
  lab within its budget, and remove it automatically before the next run.
- Do not reuse disposable labs as `--world` inputs. Export necessary small
  evidence first; never silently accumulate
  complete game instances or move the clutter to another directory.
- Preserve the original Modrinth profile, original worlds, shared dependency
  caches, templates, backups, source files and all uncommitted work.
- Manual cleanup: `python tools/clean_validation.py` previews sizes; `--apply`
  archives bounded evidence and removes generated labs using the same lock.
  Never delete without checking the resolved target is in the managed subtree.
- Tests for this machinery: `python -m unittest discover -s tools -p test_test_artifacts.py -v`.
  These tests use tiny temporary files and do not start Minecraft.
- Preserve necessary release evidence selectively in `docs/validation`, never
  entire saves/runtime caches. Stop Gradle daemons after required builds.

## Accelerated validation: measured 400 TPS

- The human requested recording the local measurements and a code audit for
  moving validation from 40 to 400 TPS. On 2026-10-16, i5-14600KF, the normal
  disposable client/mod profile with Normal difficulty and six native villagers
  achieved 399.9 and 400.1 TPS in separate short samples. **400 TPS is the target
  for accelerated tests on this machine; 200 TPS is a conservative fallback.**
- These 10-30-second samples do not establish full-cycle autonomy, identical
  behaviour at different tick rates, or performance of larger settlements.
  The first sweep stopped after a resident died at the 1600-TPS stage; no causal
  link to tick speed was established. The second benchmark completed.
- Preserve a 40-TPS reference when adapting timing. The ordinary colony runner now defaults to 400 TPS and accepts
  `--tick-rate 40` for a reference run; `--benchmark-tps` measures throughput and is not colony
  acceptance. Do not merely replace 40 with 400 and call the mod validated.
- The persistent measurements, exact code findings and proposed migration are
  in `docs/validation/2026-10-16-tps-audit/REPORT.md`. Keep gameplay deadlines in
  game ticks and CPU/watchdog limits in real time. Do not multiply all timeouts
  or bypass the shared process lock, disposable-world rules or artifact budget.

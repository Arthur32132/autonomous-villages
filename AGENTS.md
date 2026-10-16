# Validation storage and process rules

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

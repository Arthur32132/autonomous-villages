# Contributing to Autonomous Villages

Bug reports, design discussions, forks and pull requests are welcome. English
and Russian are both welcome. The project is experimental: the latest roadmap 3
source has not been built or validated in Minecraft. Full village autonomy has
not passed acceptance. See [the roadmap](docs/ROADMAP.md) before choosing work.

## Build from a fresh checkout

Install Git and JDK 25, with `JAVA_HOME` pointing to that JDK. The Gradle wrapper
downloads Gradle and dependencies on the first build; do not use `--offline`
for a fresh checkout. Both repositories must have these exact sibling names:

```text
workspace/
  AutonomousVillages/
  MinecraftmodNavigationAi/
```

```sh
git clone https://github.com/Arthur32132/autonomous-villages.git AutonomousVillages
git clone https://github.com/Arthur32132/navigation-ai.git MinecraftmodNavigationAi
git -C MinecraftmodNavigationAi checkout 45156ef7aaac5f7889bb06907a0477aae34a27e0
cd AutonomousVillages
```

That Navigation AI revision is the dependency used by the current source. If
your change needs a newer dependency, identify the exact revision in your PR.
For a contribution, fork Autonomous Villages on GitHub, clone your fork instead
of the first URL, then create a branch with `git switch -c my-change`.

On Windows, build and run unit tests with:

```powershell
./gradlew.bat test :navigationAi:test build --no-daemon
```

On Linux/macOS:

```sh
sh ./gradlew test :navigationAi:test build --no-daemon
```

The game versions are pinned in `gradle.properties`. Build outputs are in
`build/libs` and `../MinecraftmodNavigationAi/build/libs`. Packaging is described
in [README.md](README.md). A successful build or unit test run does not establish
that a complete village works autonomously.

## Propose an improvement

1. Search [existing issues](https://github.com/Arthur32132/autonomous-villages/issues).
   Open a bug report or feature proposal, especially for large gameplay changes.
2. Create a focused branch in your fork. Keep unrelated features separate.
3. Add meaningful regression coverage for behavior changes. Record the exact
   commands you ran, results and any checks you could not run.
4. Open a pull request targeting `master`. Explain the problem, resulting
   behavior, save compatibility and validation limits. A maintainer reviews and
   decides whether to merge; opening a PR does not grant write access.

Navigation API changes belong in
[Navigation AI](https://github.com/Arthur32132/navigation-ai). Link dependent
PRs and identify which revisions need to be used together.

## Game validation and repository hygiene

Read [AGENTS.md](AGENTS.md), [VALIDATION.md](VALIDATION.md) and the
[400-TPS audit](docs/validation/2026-10-16-tps-audit/REPORT.md) first. Use the
existing runners and `tools/test_session.py` shared lock. Run only one Minecraft
test process, use disposable worlds, preserve originals, and respect artifact
and disk budgets. A private Modrinth profile is not supplied by this repository;
inspect runner options with `--help` and provide your own local installation.
Retain a 40-TPS comparison when investigating timing; 400 TPS is local throughput
evidence, not universal hardware or gameplay acceptance.

Do not commit credentials, `.env` files, game binaries, decompiled Minecraft,
world saves, copied profiles, caches or complete logs containing private data.
Keep only necessary small evidence and redact sensitive details before upload.
Use `git diff --check` before submitting. For secret scanning with Gitleaks:

```sh
gitleaks git . --log-opts="--all" --redact --max-archive-depth 2
gitleaks git . --staged --redact --max-archive-depth 2
```

The scanner configuration only suppresses exact SHA-256 manifest entries for
Java files. Review new findings; do not disable scanning to make a PR pass.

Be respectful, give reproducible technical feedback and credit others' work.
Contributions are submitted under the existing [MIT license](LICENSE); include
attribution and compatible licensing for any third-party material. Minecraft,
Fabric and other dependencies retain their own licenses. Disclose generated
code or assets and how you reviewed them when relevant.

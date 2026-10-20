# CI

`ci.yml` запускается при push в `master`, на каждый pull request и вручную (Actions → CI → Run workflow).
Одна задача на `ubuntu-24.04`, лимит 40 минут; новый push в ту же ветку/PR отменяет устаревший запуск.
Секреты не нужны: Loom скачивает Minecraft у Mojang, зависимости берутся из `maven.fabricmc.net`,
Maven Central и Gradle Plugin Portal.

## Раскладка каталогов

`settings.gradle` подключает библиотеку как `project(':navigationAi').projectDir = file('../MinecraftmodNavigationAi')`,
поэтому CI делает два checkout рядом в `$GITHUB_WORKSPACE`:

```text
$GITHUB_WORKSPACE/
  AutonomousVillages/        этот репозиторий (команды запускаются отсюда)
  MinecraftmodNavigationAi/  Arthur32132/navigation-ai, ветка по умолчанию
```

Шаги: Python-скрипты `tools/soak/*.py` и `tools/showcase/*.py` проверяются `python3 -m py_compile`,
тесты скриптов — `python3 -m unittest discover -s tools/soak/tests -v` (stdlib, без сети), затем
JDK 25 (Temurin) + кэш Gradle и `./gradlew test :navigationAi:test build --no-daemon --console=plain`.
Ревизии обоих репозиториев пишутся в summary запуска. Артефакты (выкладываются и при падении):
`jars` — `build/libs/*.jar` мода и библиотеки, `test-reports` — `build/reports/tests` обоих проектов.
Soak-прогоны в CI не запускаются: им нужен сервер Minecraft (см. `tools/soak/README.md`).

## Ревизия Navigation AI

По умолчанию берётся ветка по умолчанию библиотеки. Разово проверить другую ревизию: Run workflow с полем
`navigation_ai_ref` (ветка, тег или полный SHA). Закрепить навсегда: в шаге `Check out Navigation AI`
заменить `ref: ${{ inputs.navigation_ai_ref }}` на нужный SHA (как в разделе «Build from a fresh checkout»
файла `CONTRIBUTING.md`).

## То же самое локально

Нужны Git, Python 3 и JDK 25 (`JAVA_HOME`):

```sh
git clone https://github.com/Arthur32132/autonomous-villages.git AutonomousVillages
git clone https://github.com/Arthur32132/navigation-ai.git MinecraftmodNavigationAi
cd AutonomousVillages
python3 -m py_compile tools/soak/*.py tools/showcase/*.py
python3 -m unittest discover -s tools/soak/tests -v
sh ./gradlew test :navigationAi:test build --no-daemon --console=plain
```

Отчёты тестов: `build/reports/tests/test/index.html` и `../MinecraftmodNavigationAi/build/reports/tests/test/index.html`.

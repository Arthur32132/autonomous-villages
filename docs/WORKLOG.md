# Журнал работы

Хронология автономной работы по `docs/PLAN-v0.3.md`. Каждая запись: что сделано, цифры,
что осталось открытым. С последней записи работу можно продолжить.

## 2026-10-18 — ночь 1

### Сделано
- PR #2 (исправления code review) смёржен в `master`.
- Фаза 0: облачный стенд.
  - Headless Fabric-сервер 26.2 поднимается в контейнере (шаблон в `~/.cache/av-soak`).
  - `tools/soak`: зонд + раннер, отчёты в `build/soak-reports`. Описание метрик — `tools/soak/README.md`.
  - SessionStart-хук `.claude/hooks/session-start.sh`: JDK 25, `../MinecraftmodNavigationAi`,
    прогрев Gradle, шаблон сервера.

### Как возобновить
```sh
python3 tools/soak/run.py --days 3 --seeds 8675309,42,1337 --difficulty peaceful,normal --parallel 2
```

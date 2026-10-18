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

### Ядро v0.3 (ветка `v03/core`, в работе)
- Удалены подсистемы заказов и экспедиций: Orders, DeliveryRuns, SupplyRuns, PersonalKits,
  SettlementNeeds, ConstructionSupply, IronSupply, SheepFarm, ManagedLand, ProductionBuildings,
  PopulationPlanning, ExpeditionManager/State, Infrastructure, NetworkRoutes, RouteSearch,
  SettlementEconomy, MineSupplies, TownCrafting, AccessWorks, TownPlanner, VillageWorkshop,
  Resident, TerrainRecovery. Вместе с ними `tools/validation` и `tools/client-validation`
  (сценарии были завязаны на эти классы; их место занял soak).
- Новое ядро: `VillageStock` (все общие сундуки — один склад), `Job` + задачи
  (Chop/Farm/Mine/Build/Craft/Place/Smelt/Light/Shear/Fetch/Deposit/Home), `Worker`,
  единый `VillagePlanner`, `Projects` (захват домов деревни + новый дом 5×5),
  `Breeding` (явное размножение через ванильный `getBreedOffspring`), `Lighting`, `Chronicle`.
- Сохранение v4. Файл 0.2.x переименовывается в `autonomous_villages.v3-backup.json`,
  все его жители отпускаются в ванильную жизнь с прежними именами; деревню нужно взять заново.
- Команды: `plan <имя>` (что видит планировщик), `chronicle <имя> [стр]` (летопись);
  `orders` и `accept` удалены.
- `tools/soak/run.py`: jar-файлы снимаются один раз на пакет (`<batch>/jars`), `--jars <папка>`
  тестирует произвольную сборку. Сборка dev-версии без порчи текущих jar:
  `./gradlew :jar soakJar -x test -Pversion=0.3.0-dev`.

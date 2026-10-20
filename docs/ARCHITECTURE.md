# Autonomous Villages — архитектура кода

Руководство для разработчика. Синтез девяти карт подсистем (lifecycle, economy, gathering-jobs,
safety-town-jobs, construction, life-presentation, tooling-tests, docs-product, navigation-usage),
сверенный с кодом. Там, где карты расходились, решение принято по коду; такие места помечены
«(проверено по коду)».

> **Ревизия.** Текст сверен с веткой 0.3.0-dev `claude/max-upgrade` на `9ea5c7c` (20 октября 2026);
> хаб механик `Features` и §13 — на `000fd86`. Код указан как `Класс.метод` (найти:
> `git grep -n "метод(" -- src`), без номеров строк, которые устаревают с каждой правкой. «На `5781aae`»
> — состояние master до этой сессии (слияние PR #12). Исправленное с тех пор отмечено в §11 и
> перечислено в §12; как добавить работу, постройку, механику или переключатель — §13. Реестр
> найденных проблем со статусами — [reviews/2026-10-20-static-review.md](reviews/2026-10-20-static-review.md).

Все классы лежат в одном пакете `dev.autonomousvillages` (89 файлов вместе с миксином в подпакете
`mixin`, ~12 000 строк на `000fd86`; на `5781aae` было 67 файлов и ~7 400 строк); крупнейшие: `Projects`
938, `VillagePlanner` 625, `Village` 585, `Worker` 380, `Blueprints` 310, `OrchardFeature` 289, `Job`
280, `Landmarks` 273, `StormFeature` 265, `PlayerGoods` 260, `VillageRuntime` 252, `MineJob` 246.

---

## 0. Как читать код

- **Имена файлов.** Один класс верхнего уровня на файл, всё в одном пакете (package-private доступ —
  норма, публичного API у мода нет). `*Job` — работа жителя, подкласс `Job` (§4). `Village` — одно
  поселение, `VillageRuntime` — все поселения сервера; `VillageState` — то, что сохраняется,
  `VillageIndex` — кэш наблюдений за миром, `VillagePlanner` — выбор работы, `VillageStock` — общий
  склад, `VillagePresentation` — то, что видит игрок. Чистые помощники без доступа к миру —
  `Gathering`, `Food`, `BookLayout`, `UnreachableMemory`, `Palisade`, `Landmarks.Design`, статические
  `Projects.decide`, `MineJob.shaftCall`, `ShelterJob`/`PatrolJob`-вердикты: туда выносится логика,
  которую нужно тестировать.
- **Плотный стиль.** Методы часто в одну строку, без пробелов вокруг операторов, условия цепочками
  `&&`/`||`, ранние `return`, полные имена классов Minecraft прямо в выражениях вместо импорта.
  Комментарии и javadoc — на английском, одна-две строки, объясняют «почему» (часто ссылкой на
  soak-прогон: «rc13», «seed 8675309»). Все игровые строки (статусы, чат, книги, таблички) — на
  русском. Текст неудачи `Job.fail` одновременно служит статусом жителя и входом эвристики застревания
  (§4), поэтому менять его формулировки нужно осторожно. Новый код пишется в том же стиле, чужой код не
  переформатируется.
- **Где тесты.** `src/test/java/dev/autonomousvillages/` — JUnit 5, тот же пакет, один класс тестов на
  тему (`ProjectsTest`, `MineJobTest`, `GatheringTest`, `SafetyJobsTest`, …). Классы, которым нужен
  `ServerLevel`, `Village` или `BotHandle`, в тестах не создаются: решение выносят в статический
  помощник и тестируют его. Классы, которым нужны реестры Minecraft, сами вызывают
  `Bootstrap.bootStrap()`. Запуск: `./gradlew :test` (мод), `./gradlew test` (мод и библиотека); то же
  делает CI ([.github/workflows/ci.yml](../.github/workflows/ci.yml)). Тесты soak-скриптов:
  `python3 -m unittest discover -s tools/soak/tests` (их тоже гоняет CI).
- **Soak — не юнит-тест.** Юнит-тест проверяет одно правило за миллисекунды и детерминирован. Soak
  ([tools/soak/README.md](../tools/soak/README.md)) поднимает настоящий безголовый выделенный сервер с
  модом, библиотекой и пробой `SoakProbe` (source set `soak`), «забирает» ближайшую сгенерированную
  деревню и гоняет её несколько игровых дней на 100 TPS; на выходе — метрики мира (жители, рождения,
  смерти, построенные блоки, TPS, ошибки лога), таймлайн и карты. Он ловит то, чего юнит-тесты не видят
  (маршруты, застревания, порядок проектов), но идёт часами, зависит от сида и не видит игрока (§9).
  Слияние в master требует зелёных юнит-тестов и soak «не хуже» базового прогона
  ([AGENTS.md](../AGENTS.md)).

---

## 1. Назначение

Fabric-мод для Minecraft 26.2: оператор командой `/avillage claim <имя>` «забирает» сгенерированную
деревню, после чего её жители (через библиотеку Navigation AI — соседний репозиторий
`../MinecraftmodNavigationAi`) живут автономно: рубят лес, добывают камень и руду, пашут, крафтят,
строят дома и общественные здания настоящими предметами из общего склада, прячутся на ночь,
размножаются, ведут летопись и показывают игроку своё состояние (босс-бар, именные таблички, доска
объявлений, книга летописи, утренние новости). Принципы [PLAN-v0.3](PLAN-v0.3.md): «что-то видимо
меняется каждые несколько минут», предметы не создаются из воздуха (с оговорками — см. §11), ошибка
одной деревни или одного жителя не ломает сервер, всё проверяется многочасовыми soak-прогонами без
клиента.

---

## 2. Карта модулей

**Жизненный цикл (lifecycle).** `AutonomousVillagesMod.onInitialize` читает `ModConfig`
(`config/autonomous_villages.json`, 34 булевых переключателя — с 20.10 добавлены `chatAnnouncements` и
шесть переключателей механик;
файл перезаписывается при каждом старте), регистрирует тип чанк-тикета, хук разрешения ломать блоки
(`BlockBreakPermissions` → `VillageRuntime.mayBreak`), события `SERVER_STARTED`/`SERVER_STOPPING`/
`ENTITY_LOAD`/`ENTITY_UNLOAD`/`AFTER_DEATH`/`END_SERVER_TICK`, колбэки взаимодействия игрока и дерево
команд `/avillage` (op level 2 даже для чтения); `Blueprints` сам подписывается на
`END_DATA_PACK_RELOAD`, чтобы сбросить кэш шаблонов. `VillageRuntime` создаётся на `SERVER_STARTED`:
держит до 4 деревень (`LinkedHashMap` по имени в нижнем регистре), очередь `pendingReleases`, счётчик
чанк-аренд `chunkLeases` и `FileSnapshotWriter`. `Village` — объект одного поселения: `VillageState`
(то, что сохраняется), `VillageIndex`, `Reservations`, `rejected`, `workers`, `stock`, `chronicle`,
`town` (`Projects`), `planner`, `breeding`, `presentation`, `ambience`, `lifeEvents`.
`VillageDiscovery` выводит границы из структуры деревни и цепочки связанных POI; `VillageControls` —
команды plan/chronicle/protect/unprotect/private/expansion; `ServerEmptyPauseMixin` сбрасывает
`emptyTicks` выделенного сервера, пока есть активная деревня.

**Экономика (economy).** `VillagePlanner` — единственное место, где выбирается работа (§5).
`VillageStock` трактует все незапертые общие сундуки как один инвентарь и держит память недосягаемых
целей `UnreachableMemory` (§4); `Supplies` переносит предметы между рюкзаком бота и сундуком
копированием-с-фиксацией; `Reservations` даёт одному жителю аренду цели на 1200 тиков; `Worker` хранит
состояние жителя (работа, кулдауны, счётчик застреваний, бой/бегство); `Job` — базовый класс работ с
общими помощниками; `VillageCrafting` крафтит ванильные рецепты из рюкзака и обслуживает печи;
`Professions` даёт один бесплатный продукт в день по ванильной профессии; `PersonalKit` решает, какие
инструменты житель оставляет себе; `ResidentRoles` назначает роли (BUILDER, STEWARD, LOGGER, MINER,
FARMER, GUARD) и никогда их не перетасовывает; `Food` — единое правило «обеда» (хлеб, яблоко, жареное;
4 овоща = 1 обед) для рождений и босс-бара.

**Добывающие работы (gathering-jobs).** `ChopJob` (+`ForestWork`: дерево обследуется один раз за
работу, BFS ствола до 256 брёвен; `VillageState.treeWork` и `fellingTrees` пишутся вместе после первого
среза; бревно, касающееся досок, ступеней, дверей и т. п., не считается деревом —
`VillageIndex.treeLog`; валка подсаживает до двух старых пней из `reforestation`), `MineJob` в двух
режимах — «quarry» (до 12 открытых блоков библиотечным майнером, блоки у воды/лавы пропускаются) и
«shaft» (постоянная лестничная шахта до 64 ступеней библиотечным экскаватором,
`mineEntrance/mineDirection/mineLength`, состояние `mineStatus` digging/finished/blocked,
`mineFailures`, `mineRetryAt`, настенный факел каждые 6 ступеней при наличии факелов, руда рядом →
`VillageIndex.shaftOre`), `FarmJob` (одно действие на поле; `watered` кэшируется на 1200 тиков),
`CompostJob`, `HerdJob`, `ShearJob`, `SmeltJob` (топливо одного вида на всю партию), `CraftJob`,
`DepositJob`, `FetchJob`, `PlaceJob`, `BreathingSafety` (вытаскивает тонущего — работает раньше всего
остального). Общие чистые правила — `Gathering` (окно сбора дропа, «страйки» по овцам).

**Безопасность и городские работы (safety-town-jobs).** Ночные: `HomeJob`, `ShelterJob`
(`indoor()` — «внутри стен и под крышей»: твёрдый блок в 4 над головой, нет двери в клетке и рядом, по
полу в каждую сторону стена или дверь раньше открытого неба; ответ кэшируется на секунду), `EscapeJob`
(политика DIG; днём к открытой поверхности, ночью к домам, колокол — последней целью), `PatrolJob` (до
двух вооружённых стражей; только уличные точки — вышка, колокол, пороги, фонари, ворота). Городские:
`HazardJob` (пакует пороховой снег по чистому вердикту), `Lighting` + `LightJob` + `LanternJob`,
`PathJob`, `FlowerJob`, `GraveJob`, `GiftJob`, `NameplateJob`. Все — подклассы `Job`, ставят блоки
через `Construction.place` (исключения: `HazardJob`, `PathJob`, снятие факела в `LanternJob` — прямой
`setBlock`).

**Строительство (construction).** `Projects` (капитализация родных домов при claim, очередь проектов
как чистая функция `decide`, завершение, индекс защищённых блоков `protects()`), `TownBuilding` (план:
ячейки с BlockState в JSON, опциональная декорация, вырубки, тропинка), `Blueprints` (ванильные
NBT-шаблоны: замена камня/песчаника/терракоты на булыжник, разворот входа к колоколу, кэш списка и
разобранных шаблонов, аудит досягаемости `audit()`), `Landmarks` (собственные проекты: кузница, рынок,
мельница, часовня, таверна, конюшня, пирс; покрыты тестами на досягаемость), `Palisade` (кольцо
частокола, зажатое в границы деревни), `BuildJob`, `Construction.place`, `LandmarkGoods` (суточные
эффекты готовых зданий; делают только то, что влезает в склад), `SpareBeds`.

**Жизнь и представление (life-presentation).** `Breeding` (ребёнок каждые ≥4800 тиков при свободной
кровати, ≥3 «обедах» по `Food` и <64 жителях; сначала появляется ребёнок, потом съедается еда),
`Chronicle` (счётчики + последние 60 событий, объявления в чат игрокам в 64 блоках с бюджетом одна
строка на 100 тиков на деревню, книга на кафедру, единый номер дня `Chronicle.day(clock)`),
`MorningNews`, `LifeEvents` (музыка, ярмарка каждый 7-й день при рынке, пир только за собственную
постройку, колыбельная, фонари стражи), `Festivities` (фейерверки), `Ambience` (дым, светлячки,
приветствия — только при игроке в 48 блоках), `VillagePresentation` (босс-бар в 96 блоках, чистит
отключившихся; переименование жителей «Имя, роль • статус» раз в секунду, но именная бирка игрока
сохраняется; доска объявлений — только своя табличка, позиция в `VillageState.noticeBoard`; анимация
крышек), `VillageRequests` (просьба о материале и награда, частичным дарителям — благодарность),
`PlayerGoods` (ведомость из книги с пером через `BookLayout` — перенос по ширине книги, 14 строк на
страницу; пожертвования только полезных предметов с резервом 9 слотов склада; грамота за 128
предметов).

**Механики (features).** Самостоятельные надстройки над ядром собраны в хабе `Features`: интерфейс
`Features.Feature` (`key()`, `enabled()`, `tick(v, time)`, `job(v, w, role)`), список `Features.ALL`,
свой переключатель в `ModConfig` у каждой (имя поля = `key()`), своё JSON-место в
`VillageState.features` (`Features.load/store`; больше `Features.MAX_STATE_CHARS` = 32 768 символов —
слот выбрасывается). `Features.tick` зовётся из `LifeEvents.tick` каждый серверный тик (независимо от
`lifeEvents`), `Features.job` — из `VillagePlanner.next` шагом 4d: после разгрузки, склада, инструмента,
света, снега и подарка, до работы роли. Исключение механики логируется не чаще раза в 1200 тиков и не
останавливает ни другие механики, ни деревню. Как добавить свою — §13.

- `CampfireFeature` (`campfire`) — костёр со склада в 3–6 блоках от колокола и вечерний круг жителей у огня до ночного отбоя.
- `OrchardFeature` (`orchard`) — сад 3×3 дуба на краю деревни, ежедневный обход с костной мукой, яблоки на склад; его деревья не рубят (`OrchardFeature.isOrchardTree` в `ForestWork.usable` и `VillagePlanner`).
- `HeraldryFeature` (`heraldry`) — герб из имени деревни (`CoatOfArms`) в летописи и знамёна из лишней шерсти на вышке, амбаре, у колокола и над воротами (`BannerJob`, по одному; не пока дом ждёт кроватей).
- `StormFeature` (`storm`) — в грозу жители под открытым небом уходят под крышу (`StormShelterJob`, в темноте — обычный `ShelterJob`), на вышке (иначе на амбаре) громоотвод из трёх медных слитков склада (`LightningRodJob`).
- `MasteryFeature` (`mastery`) — звания (ученик, подмастерье, мастер) за долгую работу одним ремеслом (`Mastery`): строка в летописи и звание в подписи над головой (`VillagePresentation`); на работу и экономику не влияет.
- `LivestockFeature` (`livestock`) — скотный двор: разведение коров и кур кормом со склада, сбор яиц, забой излишка при нехватке еды (не ниже двух взрослых; именных, привязанных и чужих не трогает).

**Инструменты и тесты (tooling-tests).** Gradle: основной jar, отдельный source set `soak`
(`tools/soak/java`, упакован в `autonomous-villages-soak.jar`; с 20.10 `check` зависит от
`soakClasses`, так что сломанная проба валит сборку), задачи дистрибутива с явным списком документов
(отсутствующий файл — ошибка). Версия `0.3.0-dev`. CI — GitHub Actions
([.github/workflows](../.github/workflows/README.md)): `py_compile` и `unittest` Python-скриптов,
затем `test :navigationAi:test build` рядом с клоном navigation-ai. Хук
`.claude/hooks/session-start.sh` (JDK 25 с запасными источниками, клон navigation-ai, прогрев Gradle
вместе с `testRuntimeClasspath`, повторы при HTTP 429, шаблон soak-сервера; таймаут 1800 с).
`tools/soak`: `run.py`, `SoakProbe.java`, `digest.py`, `compare.py`, `map.py`, тесты
`tools/soak/tests`; `tools/showcase/make_timelapse.py`. 39 JUnit-классов (~3 800 строк, 266 тестов:
`000fd86` плюс `ReleaseMetadataTest`) для чистых функций, формата сохранения и метаданных релиза
(`ReleaseMetadataTest`: диапазон `navigation_ai` `>=0.2.2 <0.3.0-` — без пре-релизов 0.3.0 вроде
`0.3.0-dev` — и контакты в `fabric.mod.json`, файлы `releaseDocs` на месте, баннеры и ссылки архива). В комплект релиза (`assembleDistribution`) идут только `releaseDocs` из `build.gradle`:
README, LICENSE, CHANGELOG, CONTRIBUTING, SECURITY, PLAN-v0.3, это руководство и
`tools/soak/README.md`; архив исходников (`sourceTree`) добавляет AGENTS.md и WORKLOG.

**Документация и продукт (docs-product).** Актуальны: [README.md](../README.md), верх
[CHANGELOG.md](../CHANGELOG.md), [PLAN-v0.3.md](PLAN-v0.3.md), [WORKLOG.md](WORKLOG.md),
[AGENTS.md](../AGENTS.md), [CONTRIBUTING.md](../CONTRIBUTING.md) (частично),
[tools/soak/README.md](../tools/soak/README.md), это руководство и
[статический обзор](reviews/2026-10-20-static-review.md). Исторические (0.1/0.2) с 20.10 лежат в
[docs/archive](archive/README.md) с баннером и в дистрибутив не попадают:
[MODDING_PLAN.md](archive/MODDING_PLAN.md), [VALIDATION.md](archive/VALIDATION.md),
[MODLOG.md](archive/MODLOG.md), [ROADMAP-0.2.md](archive/ROADMAP-0.2.md),
[STATIC_AUDIT_2026-10-17.md](archive/STATIC_AUDIT_2026-10-17.md); свидетельства 0.2.2 —
[docs/release](release/0.2.2.md) и [docs/validation](validation/); там же по смыслу
[reviews/2026-10-19-external-review.md](reviews/2026-10-19-external-review.md).

**Использование навигации (navigation-usage).** Тонкий слой `Job.Approach/Walk/Harvest/Fetch/Deposit/
collect` и `Worker` поверх `BotHandle` (navigator, miner, inventory, combat, actions, stopAll);
библиотека тикает ботов на `START_SERVER_TICK`, мод — на `END_SERVER_TICK`.

---

## 3. Цикл тика и каденции

### 3.1 `VillageRuntime.tick` (каждый серверный тик)

1. `ticks++`; первые 5 тиков пропускаются.
2. `residentDecisionRemainingNanos = TickWorkBudget.residentDecisionNanos(nanosPerTick)` —
   один бюджет решений на **все** деревни (≈ interval/25: ~2 мс при 20 TPS, ~0,4 мс при 100 TPS).
3. Активные деревни тикаются по кругу (`villageRotation`), каждая в `try/catch(RuntimeException)`;
   ошибка пишет `village.problem = "ошибка планирования: …"` и логируется не чаще раза в 200 тиков.
   `Error`/`LinkageError` не ловятся.
4. `writer.takeFailure()` → `persistenceWriteFailed` (флаг никто не читает).
5. Каждые 1200 тиков `writeSave(true)` — сериализация на потоке сервера, запись на демон-потоке.

### 3.2 `Village.tick` — порядок и частоты

| Что | Когда | Стоимость / заметки |
|---|---|---|
| Повторный survey + capture + save при `bounds==null` | каждый тик, пока границ нет | ловится только `IllegalArgumentException` |
| `ticket(true)` | каждый тик (аренды пересобираются раз в 100) | до 1024 чанков + чанк последней позиции каждого жителя, радиус тикета 2 |
| `index.tick` | каждый тик | 12 колонок (8 вокруг жителей по спирали, 4 по курсору), слои surface−24…+5 → ~700–1000 чтений блоков; список жителей раз в 100; `ForestWork.prune` (устаревшая валка) раз в 200; **POI кроватей `getInRange(HOME, center, radius+384)` раз в 200 тиков или каждый тик, пока кроватей нет** |
| survey на расширение | каждые 2400 | BFS по POI, конечный запрос радиусом до 2048 |
| `sync()` | каждые 100 тиков **или каждый тик, пока `workers` пуст** | `getEntitiesOfClass` по bounds+64; `bots.attach` для каждого взрослого без try/catch; `syncRoles` (+save при изменении) |
| `town.tick()` (Projects) | каждый тик, работа раз в 100 (`nextPlanning`); после рестарта `recapture` раз в 1200, пока не выполнен | §6 |
| `breeding.tick()` | `%200==37`, днём | два POI-запроса по квадрату, подсчёт еды |
| цикл работников | каждый тик, по кругу; каждый в `try/catch` | бюджет списывается только с **простаивающих** (`job==null`) работников, и только пока `mayPlan`; тики работ не бюджетируются |
| `rescueFromPowderSnow` | `%5` | |
| `guardChildren` | `%20==7` | |
| `huntLurkers` | `%100==23` | |
| `summonGolem` | `%200==13`, в сумерках 11500–13000 | |
| книга летописи | `%6000==1234` | |
| `MorningNews` | `%100==61` | один пост в игровой день |
| `dayBells` | `%20==3` | |
| `NameplateJob.refresh` | `%600==311` | переписывает только изменившиеся таблички (строки сравниваются как строки; на `5781aae` проверка не срабатывала) |
| `presentation.tick` | каждый тик (крышки, отправка накопленного чата `chronicle.flushChat`, слежение за доской); доска `%200==100`; бар и имена `%20` + не чаще 500 мс реального времени | `Projects.built()` по всем блокам активного проекта |
| `ambience.tick` | `%20==9`, при игроке в 48 блоках | |
| `LandmarkGoods` | `%200==77` | раз в день на вид здания |
| `VillageRequests` | `%200==137` | `Projects.missing` + скан сундуков на каждый ключ |
| `lifeEvents.tick` | каждый тик | очередь нот; `Features.tick` (каждый тик, сами механики решают, когда работать); `watchCompletions/watchBirths` раз в 20 |
| `clearDoorways` | `%10==4` | |
| `closeDoors` | `%100==50` ночью | |
| чистка метаданных | `%200==0` | `plantedTrees`, `furnaces`, `lights`, истёкшие `rejected`, `problem` |

Все деревни живут в одной фазе игрового времени: их `%100`/`%200` работа ложится на один тик.

### 3.3 `Worker.tick(mayPlan)`

Порядок: бот отсутствует/не ACTIVE → статус «не загружен», **выход с сохранённой `job`** → другое
измерение → `cancel()` → `breathing.tick()` (если активен, больше ничего) → `restRecovery` (раз в
400 тиков при ранении, если без работы или на Home/Patrol; `extract` вызывает `stopAll` библиотеки) →
`secureDoors` (каждые 5 тиков ночью/при бегстве) → торгует/ребёнок/NoAI → `cancel()` → `danger()`
(каждые 10 тиков со сдвигом по хешу, каждый тик в бою/бегстве: `Monster` в 12 блоках + линия
видимости) → бой не дольше 600 тиков → расписание дня:

- `hiding()` = `nightRestWindow` (темно, или clock<1000, или ≥11500) **или** тревога деревни и житель
  без оружия; окно общения 1000 тиков с `9000+roleIndex%3*1000`; окно профессии 600 тиков с
  `2000+roleIndex%8*600` при наличии `JOB_SITE`. Днём житель «под крышей» (`covered` =
  `ShelterJob.indoor`, т. е. внутри стен — проём двери и крыльцо больше не считаются) домой не идёт.
- В этих окнах: завершить не-ночную не-атомарную работу → `trapped()` → `EscapeJob` → `watchman()` →
  `PatrolJob` → без кровати → `ShelterJob.find` (ячейки пола ближайших домов, затем вокруг кроватей,
  бюджет 640 ячеек; иначе кулдаун shelter 600) → не дома → `HomeJob` → `home` на кулдауне и не под
  крышей → `ShelterJob` → иначе `stopAll`, `professionProduct`, статус.
- Текущая работа: `timedOut = now−started > timeLimit()`; `job.tick()`; при `false` или таймауте
  («не уложился во время») → `endJob()`.
- Иначе, если `mayPlan` и `now ≥ nextDecision`: `nextDecision = now+20`; `trapped()` → `EscapeJob`;
  `pickup()`; `planner.next(this)` → `start(job)` или `idleReason` + `stopAll`.

---

## 4. Жизненный цикл работы (`Job`)

**База (`abstract class Job`).** `kind()` (ключ кулдауна и `activeJobs`), `target()` (аренда, отображение,
отклонение), `timeLimit()` (по умолчанию 2400; Farm 1200, Gift 1600, Home 3000, Build/Chop/Compost
3600, Craft 4800, Shaft 6000; Patrol и Shelter — до утра: `PatrolJob.nightLeft(clock)+600`), `tick()` →
`false` когда завершена или провалена, `stop()`, `atomic()` (расписание ждёт), `keeps(stack)` (не
сдавать на склад), `activity()`/`mayBreak(p)` (для хука библиотеки). `fail(reason)` пишет `failure =
reason (+" ("+navReason+")")`; `navReason` устанавливается в `noteRoute` при провале маршрута и с
20.10 **сбрасывается при создании каждого `Approach`/`Walk`** (на `5781aae` не сбрасывался никогда и
заражал последующие провалы). `canStart` нет: планировщик строит работу, `Worker.start` берёт аренду
на `target()` (при отказе работа молча отбрасывается).

**`Progress`** — сторож маршрута: 300 тиков без сближения с целью или 2000 тиков в `COMPUTING`.

**`Approach(target, reach, sight)`** — выбирает стоячую ячейку через `Village.stand` (5×5×8 ячеек
вокруг цели: `safeFeet`, глаз на 1,62, дистанция ≤ reach−0,65, при `sight` — рейкаст COLLIDER;
ближайшая к телу), шлёт `goTo(PositionGoal(feet, .35))` с политикой NEAR (цель в ≤10 блоках по
Чебышёву) или TRAVEL. Провал маршрута: `++attempts>=4` → FAILED; новая ячейка: `attempts++`;
`attempts>=6` → FAILED. **Из-за двойного счёта реально пробуются две ячейки** (проверено по коду; не
исправлено). Если `stand==null` → `navReason="no_stand"` (если пуст) → FAILED. Сторож `nav.cancel()`
даёт причину `cancelled`.

**`Walk(point, radius)`** — к точке (овцы, кровати, сбор); допуск `max(.35, radius*.5)`; три
проваленных маршрута → FAILED; `cancelled` тоже считается провалом.

**`Harvest(target)`** — `miner.breakBlock`, затем `collectUntil = now+60` и `collect(target, 6)`;
**`collect()` видит только `ItemEntity` без `pickUpDelay` (ванильные 10 тиков), поэтому на том же
тике ничего не находит и `Harvest` возвращает DONE сразу** (проверено по коду; «60 тиков сбора» из
карты economy — это верхняя граница, а не минимум). С 20.10 `ChopJob` и `ShearJob` держат собственное
окно сбора не меньше 20 тиков после последнего удара или стрижки (`Gathering.keepCollecting`); поле,
вырубки стройки и карьер идут через `Harvest` и по-прежнему уходят сразу. `collect()` сам вызывает
`w.pickup()` и шлёт маршрут к ближайшему дропу.

**`Fetch(needs)`** — от сундука к сундуку (`stock.nearest` без `tried`, отклонённых и пропускаемых как
недосягаемые — для деревни или для этого жителя), `Approach(4.0, sight)`, `Supplies.transfer`; при
FAILED подхода — `unreachable(chest)` и следующий сундук; при успехе пометка недосягаемости снимается;
`missing` = метка первой невыполненной нужды, `hidden` — нужное есть только в сундуке, который житель
сейчас пропускает (`FetchJob` пишет «не добраться до склада» вместо «нет на складе»). **`Deposit`** —
то же с `nearestWithRoom`, переносит все слоты вне `keptSlots()`.

**Поток провала (`Worker.endJob`).** `job.stop()`; если `failure` непуст:
`lastFailure`; `NavHygiene.reject(v.rejected, target, now+600)` (берёт больший срок: более длинный
отказ, выставленный самой работой, сохраняется — до w2-nav-hygiene был `put`, перезаписывавший его);
`cooldown(kind(), 200)`; эвристика застревания `NavHygiene.stuck` **по русским строкам** («не подойти»,
«не нашёл дорогу», «не дойти», «(unreachable)», «(stuck»; «уложился» — только если превышен лимит без
ожидания планировщика; провалы планировщика `planning_timeout`/`search_queue_full`/
`chunk_loading_timeout` не считаются) → `stuckCount`
около одного места; 3 → лог `TRAPPED` и `forgetBy(id)` памяти недосягаемых (виноват житель, а не
цели), затем `trapped()` → `EscapeJob` (кулдаун escape 1200), после `EscapeJob` → телепорт `unstick()`
(тоже `forgetBy`); `v.failures` (диагностика), `ambience.frown`, лог `JOB_FAILED` раз в 600 тиков.
Всегда: `reservations.release(id)`, `lastJob = kind()`, `job=null`, `nextDecision=now`. Кулдауны
(`Worker.cooldown`) не укорачиваются; `ready(kind)` читается планировщиком. `MineJob.kind()` =
"quarry"/"shaft", и с 20.10 планировщик проверяет именно эти ключи (`MineJob.quarryAllowed/
shaftAllowed`), а `mine` остаётся паузой самого планировщика (на `5781aae` ключи не совпадали, и
провалы добычи кулдауна не получали).

**Отклонённые цели и память недосягаемых.** `Village.rejected: Map<Long,Long>` (позиция → срок) —
общая для целей работ и сундуков; читается через `v.ready(p, worker)` = `canWork && не отклонено &&
reservations.available`. С 20.10 рядом живёт `VillageStock.unreachable` (`UnreachableMemory`, чистая
логика): цель (сундук, верстак, ячейка стройки), маршрут к которой кончился не «транзиентной» причиной
(`planning_timeout`, `chunk_loading_timeout`, `search_queue_full`, `unloaded_frontier`, смена
измерения, `cancelled` сторожа), пропускается ~1000 тиков; повтор в пределах суток удваивает срок (до 6
раз, ~32 000 тиков); не больше 256 записей. Сообщивший житель пропускает цель сразу, а общий сундук или
верстак скрывается от всей деревни (и из подсчётов склада) только после `min(2, workers)` разных
жителей. `Job.unreachable(p)` кроме того голосует в `Village.unreachableFrom` **только при `navReason ∈
{unreachable, no_stand}`**: голоса живут 2400 тиков, кворум `min(2, max(1, workers.size()))`, страйки →
`rejected = now + min(24000, 2400 << min(4, strikes−1))`; страйки не затухают (сбрасываются только при
>512 записей). Ячейки стройки идут через `unreachableCell` — только память, без голосов.

**Аренды (`Reservations`).** `acquire` всегда даёт `now+1200`; владелец может продлить — с 20.10 это
делает только `ShelterJob` (каждый тик; если ячейку занял другой, работа кончается и поиск идёт
заново); `release(key, worker)` снимает только свою аренду. Остальные длинные работы (Chop 3600, Shaft
6000) после минуты видны планировщику как свободные цели.

---

## 5. Планировщик и модель общего склада

**`VillagePlanner.snapshot()`** — кэш на 40 тиков: ~17 подсчётов `VillageStock.count` (доски, брёвна,
палки, булыжник, щебень, хлеб, пшеница, семена, двери, кровати, факелы, топливо, железо, сырое
железо), `freeSlots`, число сундуков, цвет и количество шерсти (скан всех слотов), ножницы (склад +
рюкзаки всех активных), активный проект, `missing` = сумма `Projects.missing` по всем незавершённым
зданиям, `dark` (ночью или в первой половине каждой минуты: пороги `darkDoorsteps(3)` раз в 200 тиков
и тёмные комнаты по кругу якорей `Lighting.RoomScan` — по нескольку якорей за раз, освещённый якорь
пропускается две минуты — **без предела**; уличная сетка `darkSpots(8)` — только пока `lights <
Lighting.lightCap(v)`, 160…960 по площади и числу жителей), ближайший к колоколу верстак (не
отклонённый и не скрытый памятью недосягаемых — тот же тест, что у `CraftJob`), печь (+флаг выхода;
`getBlockEntity` без проверки загрузки чанка).

**`VillagePlanner.next(w)`** — фиксированный порядок, без кэша:
1. Разгрузка: после chop/quarry/shaft/shear при ≥4 лишних, либо ≥16 лишних, либо ≤4 свободных слотов —
   `DepositJob`, если `chests>0 && freeSlots>0 && ready("deposit")`.
2. `storeJob`: верстак → сундук (при `noChest` в 10 блоках от колокола или `full`: <9 свободных слотов
   **по всем сундукам, включая отклонённые**, и <48 сундуков) → печь (`cobble≥8`) → доска объявлений
   (`boardCarried` опрашивает `o.has(...)` **всех** работников, в т.ч. без бота).
3. `toolJob` — инструмент роли.
4. `LightJob` (стражи и малая бригада — всегда, остальные после clock 8000; один на деревню),
   4b `HazardJob`, 4c `GiftJob` (хлеб ≥24), 4d `Features.job` — первая механика, предложившая работу.
5. Ветка роли (при `smallCrew`, т.е. <3 ролей, роль = null): BUILDER build→craftForProject→chop(помощь);
   STEWARD craftForProject→spareBed→workshop→build; LOGGER chop; MINER mine→sand; FARMER
   farm→bread→compost→shear→herd; GUARD chop(помощь).
6. Общий хвост: build, craftForProject, spareBed, workshop, grave, nameplate, flowers, lanterns,
   shear, chop, helperPick, mine(помощь), sand, farm, bread, pave.
7. Финальный `DepositJob` при излишках.

Взаимоисключение: `v.activeJobs(kind)` (O(workers)), `busy()/crafting()` (чужие Craft/PlaceJob),
`v.ready(p)`, кулдауны. Ранжирование целей: `distSqr + 15·dy²`. `build()` создаёт `BuildJob` на каждый
незавершённый проект (дома первыми), считает `affordable(1)` (полный `nextSteps(48)` без ячеек из
памяти недосягаемых) и **по-прежнему вызывает `town.paid(project)` при создании работы**, а не при
поставленном блоке (проверено по коду). `mine()`: ворота `MineJob.quarryAllowed/shaftAllowed`;
предикат `usable` отбрасывает блоки у воды и лавы (`MineJob.fluidAdjacent`), но всё ещё вызывает
`VillageCrafting.hasPickFor(bot, state)` (снимок инвентаря) на **каждого** кандидата из
`index.stone/ore` до дешёвых фильтров; при `ironWanted` — шахта через `mineEntrance()`, которая теперь
решает по сохранённому состоянию через чистую `MineJob.shaftCall` (копать / переждать отказ / оставить /
искать другой край), пропускает отвергнутый вход, возобновляет заблокированную шахту после
`mineRetryAt` и больше не сохраняет синхронно. `farm()`: `closest()` гоняет `FarmJob.watered` по всем
`farmSites` (ответ кэшируется на 1200 тиков на площадку, `FarmJob.WaterCache` ≤4096), а сами
`farmSites` только растут.

**`VillageStock`.** `chests()` пересобирается раз в игровой тик (или после `invalidate()`) из
`Village.chests()`: позиции `state.storage`, чей чанк загружен, не приватные, не запертые; устаревшие
позиции вычищаются; отклонённые и скрытые памятью недосягаемых (по кворуму) исключаются. `count(pred)`
кэшируется в `IdentityHashMap` по идентичности предиката — попадает только для статических лямбд;
захватывающие лямбды (шерсть, материалы `BuildJob`, `nextSteps`) всегда промахиваются и сканируют все
слоты. `put()` заполняет сундуки у хаба первыми и **возвращает невместившийся остаток**; с 20.10
`LandmarkGoods` заранее считает место (`room()`) и кладёт остаток предметом у станции (`keep()`), а
`Worker.professionProduct` по-прежнему теряет то, что не влезло и в рюкзак. `consume()` удаляет;
`nearest/nearestWithRoom` пропускают недосягаемые; `freeSlots()`; `hidden()` для текста `FetchJob`.

**`Supplies`.** `transfer(bot, chest, toChest, test, amount)` работает на копиях рюкзака и сундука,
затем `replace()` (`stopAll` → `extract` всех 36 слотов → `insert` новой раскладки; остаток `extract`
не проверяется, остаток `insert` бросает исключение после извлечения) и запись сундука. Тот же путь у
`VillageCrafting.craft`. Любая мутация инвентаря библиотеки вызывает `stopAll` — маршрут не переживает
подбор, еду или смену инструмента.

---

## 6. Конвейер строительства и порядок проектов

**Планы.** `TownBuilding{kind, origin, blocks[Plan{pos, state(JSON), optional}], cuts, design, path,
completed}`. Источники: `Blueprints` (ванильные `village/<style>/houses/*.nbt`; список id шаблонов
читается один раз на менеджер структур и заново после `END_DATA_PACK_RELOAD`, разобранные шаблоны
кэшируются вместе с ним; `houses()` — `_house_`/`_cottage_` с кроватями ≤12×12; `civic()` — без кроватей
≤13×13, исключены только жилые дома `*_small|medium|big_house`, animal_pen, meeting, accessory,
stable — дома бронника, лучника, каменщика, пастуха и картографа теперь доступны; `Blueprints.audit`
проводит строителя по плану снизу вверх, и шаблоны, требующие лесов, берутся только когда другого не
осталось), `Landmarks.Design` (кузница, рынок, мельница, часовня, таверна, конюшня, пирс — проверены
тестами на материалы, досягаемость и опоры), ручные методы `Projects.storehouse(base, rotation)` (амбар
разворачивается дверью к колоколу, проём проверяется), `well/tower/pen/cottage` (фиксированная
ориентация) и `palisade`. Вырубки записываются все, но выше досягаемости строителя от пола
(`Projects.CUT_TOP` = `reachTop(4.5)`, пол — земля перед дверью, `Projects.floor`) они необязательны:
в `remaining()` не считаются и пробуются последними.

**`Projects.capture`** — при claim, при `bounds==null`, после расширяющего survey; после каждого старта
(`captured` не сохраняется) `recapture` раз в 1200 тиков повторяет survey и захватывает, только если
появился новый кусок структуры или дом. Заливкой от каждого HOME POI записывает родные дома как
защищённые `native_house`; сундуки (до 128), печи (до 16) и верстаки усыновляются в склад только из
сгенерированного куска деревни без «меток игрока» (`Projects.adoptable(inPiece, marked)`; метки —
`foreign()`: таблички, кроме еловых настенных из шаблона тайги, редстоун, наковальни, шалкеры, …).

**`Projects.tick`** (раз в 100 тиков; на ветке 0.3.0-dev переписан):
1. Завершение: `remaining(b)==0` → `completed=true`, объявление, хуки (`housewarming`, `furnish`,
   `signboard`, `stableHorses`, `fillWell`, `stockPen`, `footpath`) — каждый в своём `try/catch`
   (`hook()`, ошибка логируется один раз), затем `save()`.
2. `closePenGate`.
3. `active` = первый незавершённый; `stage` = «не подойти к стройке: …» (`blocked`) / «ждут
   материалы: …» (`waiting`) / «строят …».
4. Пределы 256 зданий / 64 заложенных домов.
5. Факты собираются в запись `Queue` (кровати, жители, дома и ждут ли они, `bedBound`, общественные
   постройки и ждут ли они, нужна ли шерсть, вода у колокола, причитающаяся достопримечательность,
   мастерские, построенные виды, остывающие виды, флаги конфига), решение — чистая функция
   `decide(q)` → `Choice(house, civic)`.
6. `decide`: загон первым, если дому нужна кровать, а шерсти <3; иначе при `workFree()` (нет
   незавершённой общественной постройки или одна ждёт сутки) — первый подходящий вид цепочки
   `CHAIN` = амбар → колодец → вышка → загон → частокол → достопримечательность (`Landmarks.due`) →
   мастерская; дом — если `homeless` (`beds < residents+2`), `houseFree()` (нет кулдауна «house»,
   незавершённых домов нет или их <3 и все ждут) и дом не «привязан к кровати» при начатой
   общественной постройке.
7. Закладка: каждый вид ищет площадку своим курсором (кольца расходятся от колокола); без площадки —
   кулдаун `retryTicks` (2400, частокол 24000, дом 2400), и в следующий раз цепочка идёт дальше. Каждая
   закладка → объявление + `save()`.

Следствие: одна общественная постройка закладывается параллельно с домом. На `5781aae` цепочка
`else-if` с домами первыми, `free` и `return` при неудаче площадки означала, что амбар, вышка и всё
после них в soak-прогонах не появлялись ни разу (WORKLOG, проверено по коду цепочки).

**Оценка плана.** `remaining/nextSteps/pendingCuts/missing` — полные проходы по плану с чтением мира;
`nextSteps` сортирует детерминированно (optional, Y, stepOrder) — те же ячейки первыми каждый раз;
`blockedForever(p)` — чужой неубираемый блок в ячейке. `waiting(b)`: проект `blocked` (всё, что
строители взяли бы следующим, дважды подряд недосягаемо по памяти), или `now − unpaidSince > 24000`,
или `> 3000` и не хватает только не-повседневного материала. `protects()` — ленивый `HashSet<Long>` всех
ячеек планов (+верхние половины дверей, изголовья кроватей), пересобирается при `invalidate()` —
**вызывается из `Construction.place` после каждого блока**.

**`BuildJob.tick`**: вырубки (`Approach 4.5` → `Harvest`; провал подхода → ячейка
запоминается как недосягаемая) → `affordable(16)` без ячеек из памяти недосягаемых (пусто → «стройке не
хватает материалов: …») → `Fetch` (один раз; что склад дал, то и есть) → установка снизу вверх:
`Approach(4.2, без видимости)`, `Construction.available` (подмены: щебень вместо булыжника, любые доски,
любая кровать) → `Construction.place`; недосягаемая ячейка запоминается и партия идёт дальше; 6
промахов → «не подойти к стройке» / «не удаётся поставить блоки»; пустая партия без установленного
блока → «не подойти к стройке» (если мешали подходы) или «материалы не донесены». Отказы `place`
(canSurvive, лимиты, зоны) между работами не запоминаются.

**`Construction.place`**: политика зон; **лимиты метаданных — сундук при
`storage ≥128` и печь при `furnaces ≥16` не ставятся вовсе** (проверено по коду; карта docs-product
ошибочно утверждает обратное); `canWork`, дистанция глаза ≤4,5, `clear()` (только воздух, трава,
папоротник, снег), предмет в рюкзаке, граница мира, `canSurvive`; вторая половина кровати/двери;
форма с соседями; пересечение с сущностями; `select` + `Supplies.consume` **до** `setBlock`
(результат `setBlock` не проверяется); `town.invalidate()`; запись `storage/furnaces/tables`; любой
знак получает текст «СКЛАД»; саженцы → `plantedTrees`; **`runtime.save()` при каждом сундуке/печи**;
звук, частицы.

---

## 7. Персистентность v4 и путь сохранения

**Файл.** `<world>/autonomous_villages.json`: `Save{version=4, villages: List<VillageState> (≤4),
pendingReleases: Map<UUID,ResidentName> (≤16384)}`, pretty-printed GSON. Планы зданий хранят
BlockState каждой ячейки строкой JSON — до 256 зданий × 4096 ячеек, на практике ~1 МБ.

**`VillageState`** (сохраняется): идентичность (name по регэкспу `[A-Za-zА-Яа-яЁё0-9_-]{1,32}`, owner,
x/y/z, radius 24…4096, bounds ≤4096 по стороне), `active`, `residents` (≤1024), `residentRoles`,
`residentNames`, `residentPositions`, `storage` (≤128), `furnaces` (≤16), `buildings` (≤256),
`capturedPieces` (≤4096), `completedShelters`, `lights` (≤1024), `plantedTrees/plantedCrops` (512),
`reforestation` (2048; с 20.10 читается — валка подсаживает старые пни), `fellingTrees` (с 20.10
заполняется вместе с `treeWork`), `treeWork` (256), `mineEntrance/mineDirection/mineLength`, с 20.10
`mineStatus/mineFailures/mineRetryAt` и `noticeBoard` (необязательные поля, старые сохранения грузятся
с умолчаниями), `policy` (зоны, приватные сундуки, expansion), `chronicle` (счётчики + 60 событий),
`lastBirth`, `lastGolem`, `graves` (16), `cemetery`, `nameplates`, `productDays`, `goodsDays`,
`newsDay/newsTotals/newsResidents`, `fairDay`, `request/nextRequest`, `donations/donorNames/honoured`.
`compactMetadata()` подрезает коллекции, чинит состояние шахты и доску вне границ и выкидывает здания
с `!valid()` (в т.ч. с origin вне границ); `validate()` бросает `IllegalArgumentException` при нарушении
лимитов.

**Не сохраняется** (теряется при рестарте): работы, кулдауны, `Reservations`, `rejected`, голоса и
страйки недосягаемости, память `UnreachableMemory`, `failures`, `gifted`, все множества `VillageIndex`
(включая `shaftOre`), кэш `FarmJob.WaterCache`, индекс защиты, `captured`, `unpaidSince`, кулдауны
закладки проектов, состояние `LifeEvents`, кэши представления и шаблонов. Рюкзаки живут в инвентаре
navigation-ai (его `navigation_ai_bots.dat`).

**Загрузка (`VillageRuntime.load`).** Нет файла → пусто. `version` 1…3 → `retire`: копия в
`autonomous_villages.v<N>-backup[-k].json`, `legacyReleases` → `pendingReleases`, `save()`. Иначе
GSON → проверки (`version==4`, ≤4 деревень, `pendingReleases`) → для каждой деревни
`compactMetadata`, `validate`, нет дубля имени/пересечения/дубля жителя → `Village`. **Любое
исключение → `loadFailed`**: команды (`mutable()`) и `writeSave` отключены, `residentLoaded` — no-op,
файл не трогается, статус «Ошибка загрузки…».

**Запись (`writeSave(periodic)`).** Для всех деревень в одном `try`: `rememberResidents`,
`compactMetadata` (включая `TownBuilding.valid` по каждой ячейке), `validate`; `GSON.toJson`;
`periodic` → `writer.periodic(json)` (демон-поток, последняя версия выигрывает, ревизии упорядочены,
одинаковый JSON не пишется); иначе `writer.immediate(json)` — **синхронно на потоке сервера под
локом**. Любая ошибка → `persistenceWriteFailed` (флаг нигде не показан; сбрасывается только успешной
немедленной записью).

**Вызовы `save()`.** На ветке 0.3.0-dev — 21 место в 7 файлах (проверено `git grep`): `Projects` 4
(`recapture`, завершение здания, закладка общественной постройки, закладка дома), `VillageRuntime` 6
(claim/change/storage/retire/close/residentLoaded), `VillageControls` 4, `Village` 4 (survey,
syncRoles, golem, stop), и по одному в `Construction.place` (сундук/печь), `Breeding.tick` (рождение),
`AutonomousVillagesMod` (AFTER_DEATH — внутри `LivingEntity.die`). Убраны синхронные сохранения в
`VillagePlanner.mineEntrance`, `PlayerGoods.thank` (каждый подарок), `MineJob.shaft` (каждый сегмент) и
`ForestWork.complete` (каждое дерево) — их подхватывает периодический снимок. На `5781aae` было 31
место в 11 файлах (в исходной карте ошибочно «25»: её же перечень даёт 31). Плюс полная
пересериализация раз в 1200 тиков, даже без изменений. `FileSnapshotWriter.commit`: temp-файл +
`ATOMIC_MOVE`, без `fsync`, без `.bak`.

**Чанки.** `Village.ticket(true)` арендует чанки footprint (≤1024, `MAX_TICKET_CHUNKS`) и чанк
последней известной позиции каждого жителя через `VillageRuntime.lease` (счётчик ссылок →
`addTicketWithRadius(TICKET, pos, 2)`); тип тикета не персистентный, без таймаута, loading +
simulation. Житель, исчезнувший без события смерти, арендует свой чанк вечно.

---

## 8. Navigation AI: что используется и чего это стоит

**API.** `BotService.attach/find/detach`, `BotHandle` (`navigator().goTo(goal, policy)`,
`miner().breakBlock/excavate`, `inventory()` snapshot/insert/extract/select, `combat`, `actions().
setMovement/stop`, `stopAll`, `state()`), `TickWorkBudget.residentDecisionNanos`,
`BlockBreakPermissions.register` → `VillageRuntime.mayBreak(id, pos)` (ищет владельца потоком по
деревням, читает BlockState; вызывается на каждую ломаемую ячейку снимка при `allowBreaking`),
`VillagerAccess.navigationAiSavedState`. Политики мода: `NEAR = (…, 32 000 узлов, путь 1024, радиус
снимка 16, …)` для прыжков ≤10 блоков; `TRAVEL` — радиус 32; `DIG` (EscapeJob) — ломать и ставить, снимок
никогда не переиспользуется.

**Стоимость одного `goTo` (по коду библиотеки, не измерено).** `startNavigation` → `cancel()`
сбрасывает предыдущий снимок мира. Радиус захвата `min(policy, max(8, min(16, dist+4)))`, ±16 по
вертикали: r=16 ≈ 36k ячеек ≈ 9 тиков глобального бюджета 4096 ячеек/тик (2048 на бота); при пустом
результате захват растёт 8→16→32 (r=32 ≈ 143k ячеек ≈ 35 тиков) и только потом `unreachable`. Поиск:
≤4 потока, очередь 64, 40k узлов/тик на сервер. Результат приходит через `server.execute`. Любой
`insert/extract/select/transfer` вызывает `stopAll` → отмена маршрута.

**Где мод тратит лишние запросы.** Маршрут на каждый дроп (`collect`), причём `pickup()` →
`insert` → `stopAll` отменяет сам маршрут подбора — с полным рюкзаком это цикл каждый тик; новый
`Walk` при каждом сдвиге овцы/игрока на 2–3 блока (`HerdJob`, `ShearJob`, `GiftJob`) со сбросом
счётчика попыток (с 20.10 `HerdJob` и `ShearJob` ограничивают число перенацеливаний за работу, а овца,
дважды не достигнутая, пропускается; `GiftJob` — нет); новая стоячая ячейка почти на каждый блок
стройки; повтор недосягаемых деревьев, полей и опасных клеток каждые 600 тиков через самый дорогой путь
(рост захвата до 32) — для сундуков, верстаков и ячеек стройки с 20.10 действует память
`UnreachableMemory` с удвоением срока; перепланирование бегства каждый тик, пока враг виден;
`Fetch/Deposit` застрявшего жителя перебирает все сундуки (до ~32 проваленных маршрутов за работу;
следующая работа уже пропускает сундуки, где он сам провалился); лечение `restRecovery` отменяет дорогу
домой (три лечения → провал `HomeJob`, кулдаун home 6000). Опрос через `completion().isDone()` создаёт
копию future на каждый тик до завершения задачи. Время ожидания планировщика засчитывается в
`timeLimit` и в эвристику застревания → `EscapeJob` с политикой DIG → ещё больше нагрузки
(положительная обратная связь; WORKLOG: ~50 % дневных сэмплов в COMPUTING). Не используются: учёт
ожидания инфраструктуры, `NavigationDiagnostics`, `EntityFollowGoal/FollowGoal`, `NavigationMetrics`
(кроме SoakProbe).

---

## 9. Soak-инструменты и метрики

`tools/soak/run.py`: собирает jar'ы (`gradlew build soakJar :navigationAi:jar -x test`; версия берётся
из `gradle.properties`), держит кэш шаблона Fabric-сервера (`~/.cache/av-soak/template`, однократная
загрузка на порту 25599; jar'ы качаются в `*.part` и принимаются, только если открываются как zip), на
каждую пару seed×difficulty поднимает отдельный сервер в «лаборатории» (hard-link, случайный порт
26000–29999, `soak.properties`), ждёт `max_real_seconds+300`, забирает `soak-result.json`,
`soak-timeline.jsonl`, `map-*.json`, хвост лога (gz), `events.log` (строки с маркерами `DEATH`,
`TRAPPED`, `UNSTUCK`, `SETTLEMENT`, `JOB_FAILED`, `HUNT` и с 20.10 — строки самой пробы `SOAK`),
`errors.txt` (первые 200 строк, полное число — `logErrorsTotal`); пишет `summary.json`. С 20.10
исключение в одном прогоне записывается как `runner_error`, а код выхода — 1, если хоть один прогон не
завершился; `--jars` кладёт по одному jar'у на мод и отказывается от двух версий одного мода.

`SoakProbe` (мод в source set `soak`, пакет `dev.autonomousvillages`, читает ~30 package-private
полей; не менялась): на `END_SERVER_TICK` FIND (ближайшая деревня, тикет радиуса 9) → LOAD
(камера-бот SoakCam) → WAIT_VILLAGERS (стабильное число жителей 10 с, ≤120 с) → claim «Soak» (радиус
игнорируется), стартовая перепись, `map-start.json`, скорость тика (по умолчанию 100 TPS) → RUN:
каждые 1200 тиков `sample()` (жители, POI домов, `modView()` с вызовом `planner.snapshot()` — эффект
наблюдателя, предметы в сундуках, `NavigationMetrics`); каждые 6000 — перепись (результат не
используется); каждые 12000 — карта (без проверки загрузки чанков). `ENTITY_LOAD`/`AFTER_DEATH` считают
рождения и смерти. `finish()`: перепись, карта, `outcome` (`builtBlocks` по категориям, deaths,
births, villagers, avgTps, logErrors, score), команды status/plan/chronicle/release,
`soak-result.json` (не атомарно), `halt`.

Пост-обработка: `digest.py` (текстовая сводка), `compare.py` (таблица Markdown между двумя батчами —
фактический merge-gate «soak не хуже»; с 20.10 итоги считаются только по меткам, завершённым в обоих
батчах, провалившиеся прогоны показываются отдельно, а не нулями), `map.py` (PNG/GIF; импортируется и
без Pillow), `tools/showcase/make_timelapse.py` (ffmpeg по жёстким Windows-путям). Скрипты покрыты
тестами `tools/soak/tests` (36, stdlib `unittest`, синтетические фикстуры). Контракт форматов между
`SoakProbe` и скриптами — только по именам полей, без версии. Soak-сервер безголовый: `playerNear()`
всегда ложно, поэтому просьбы, подарки, ambience, ведомость и чат в soak не работают никогда.

---

## 10. Подход к тестированию и его границы

29 классов JUnit 5 (~2 400 строк, 181 тест; на `5781aae` — 18 классов, ~885 строк, 81 тест), без фейков
и общих утилит; 12 классов сами вызывают `Bootstrap.bootStrap()`. Покрыто: геометрия и валидация
`VillageState`, GSON-раундтрипы (`Save`, роли, пожертвования, просьбы, `goodsDays`, свет, летопись,
состояние шахты, legacy-релиз 0.2→0.3), `Reservations`, `ResidentRoles`, `ModConfig`, упорядочение
`FileSnapshotWriter`, проекты `Landmarks` (материалы, досягаемость, опоры, раскладки), мелкие
помощники (`LandmarkGoods` с полным складом, `MorningNews`, `Palisade`, `Blueprints` поворот/стиль/
фильтр мастерских, `Projects.trade/label/materialKey`, `Construction.matches`, символы рецептов,
строки табличек, доска просьб, `Chronicle.sanitize`, `Worker.nightRestWindow`,
`VillagePlanner.toolFamily`, `Ambience`, `LifeEvents`). С 20.10 добавлены: очередь проектов
`Projects.decide`, предикаты площадки, частокол и усыновление (`ProjectsTest`); геометрия шахты,
вердикты отказов, `shaftCall`, факелы, соседние жидкости (`MineJobTest`); окно сбора, топливо печи,
выбор овец, распознавание дерева, кэш воды, однородные доски (`GatheringTest`); «под крышей» на карте
блоков, маршрут патруля, вердикты снега, кладбище, цели бегства (`SafetyJobsTest`); выбор проб света,
предел, круговой скан комнат (`LightingTest`); память недосягаемых, кворум, отсрочка ячеек
(`UnreachableMemoryTest`); все 152 ванильных шаблона домов из jar'а игры с аудитом досягаемости
(`BlueprintsAuditTest`, единственный класс со связанными ванильными тегами); `BookLayout`, `Food`,
бюджет чата, доска и бирки (`VillagePresentationTest`), таблички (`NameplateTest`).

Не покрыто всё, что требует `ServerLevel`, `Village` или `BotHandle`: решения `VillagePlanner.next`,
`Village.tick`, автомат `Worker` и застревание, счётчики `Job.Approach/Walk/Progress`, `VillageStock` и
`Supplies` (сохранение количества предметов), `VillageCrafting.craft`, `Breeding` (кроме правила еды),
`VillageDiscovery/VillageIndex`, пути ошибок `load/writeSave`, миксины и события Fabric. В остальных
тестах теги предметов не связаны, так что предикаты по тегам (доски, кирки) проверяются только в soak.
С 20.10 есть CI (GitHub Actions на push в master и PR: `py_compile` и `unittest` скриптов soak, затем
`test :navigationAi:test build`), `check`
компилирует пробу soak, хук прогревает `testRuntimeClasspath`, а Gradle повторяет загрузки при HTTP
429 (6 попыток с удвоением паузы); на `5781aae` CI не было, и базовый `./gradlew test` в контейнере
падал на разрешении `junit-jupiter-engine`. Поведение в мире проверяется только многочасовым soak,
который не измеряет потерянные предметы, повторные циклы «работа+цель», причины провалов
экскаватора, маршруты по видам работ и стоимость фаз тика.

---

## 11. Известные ограничения

Состояние на 20 октября 2026 указано после каждого пункта; подробности и статус каждой находки — в
[реестре](reviews/2026-10-20-static-review.md).

1. **Недосягаемые цели.** Голос «недосягаемо» только при `unreachable/no_stand` (не при стопе
   сторожа), отклонение всего на 600 тиков с перезаписью длинных сроков, нет памяти на работника, нет
   бэкоффа для целей кроме сундуков/верстаков, `Village.stand` не учитывает занятые ячейки, циклы
   `Fetch/Deposit/Patrol/Shear/бегство` без терминального состояния. Это общий корень стойла дома
   136/140 (seed 8675309) и значительной доли COMPUTING. *20.10:* для сундуков, верстаков и ячеек
   стройки есть `UnreachableMemory` с удвоением срока и кворумом (E1), патруль и стрижка получили
   терминальные состояния (G3, G4); `endJob` по-прежнему ставит 600 тиков с перезаписью, `stand`,
   бегство и перебор сундуков внутри одной работы не изменены.
2. **Синхронные полные сохранения** из 31 места (на `5781aae`), включая `die()`, каждое дерево, каждый
   подарок, и персистентность «всё или ничего» на запись и чтение; флаг ошибки записи никому не виден.
   *20.10:* осталось 21 место — убраны дерево, подарок, сегмент и вход шахты; остальное открыто.
3. **Изоляция ошибок** есть только между деревнями и между работниками; нет между фазами
   `Village.tick`, между жителями в `sync()` (один `attach` замораживает деревню), в хуках
   завершения `Projects` и в колбэках Fabric (`AFTER_DEATH`, `ENTITY_LOAD`, `SERVER_STOPPING`).
   *20.10:* хуки завершения изолированы (C1), `restoreName`/`close()` не бросают (E6); фазы, `sync()`
   и тела обработчиков событий — нет.
4. **Сканы на потоке сервера**: POI кроватей `radius+384` (каждый тик без кроватей), BFS survey с
   дублями, `darkRooms` по всем якорям раз в 200 тиков, `ShelterJob.find` вне бюджета, снимок
   инвентаря на кандидата в `mine()`, O(workers²) снимки в `owned()`, пересортировка списка брёвен в
   `ChopJob` каждый тик, `BreathingSafety` 8125 ячеек/тик, `listTemplates()` на каждый цикл, перестройка
   индекса защиты после каждого блока, `Plan.matches` с аллокациями. *20.10:* комнаты сканируются по
   кругу (G2), `ShelterJob.find` ограничен 640 ячейками (G3), дерево обследуется раз за работу (G4),
   список шаблонов кэшируется (G5), ответ `watered` кэшируется (G4); остальное открыто.
5. **Циклы повторов без терминального состояния**: добыча (ключ кулдауна `quarry/shaft` ≠ `mine`,
   шахта упирается в границу навечно, вход выбирается заново), амбар/колодец по 32 фиксированным точкам
   с `return`, поиск мастерской, кладбище, овцы, бегство, патруль, стройка (нет памяти о ячейках,
   `paid()` при создании работы). *20.10:* добыча (G1), площадки и поиск мастерской (C1), кладбище и
   патруль (G3), овцы (G4), недосягаемые ячейки стройки (E1) исправлены; бегство и `paid()` — нет.
6. **Утечки сохранения материи**: игнорируемый остаток `VillageStock.put` (таверна, мельница,
   продукт профессии, фонарь), еда съедается до появления ребёнка, `Supplies.replace` без отката,
   `place` не проверяет `setBlock`, дропы с задержкой подбора остаются на земле (урожай, малые деревья,
   шерсть) — вопреки обещанию README. *20.10:* таверна, мельница, пирс (G5) и фонарь (G2) больше не
   уничтожают предметы, рождение сначала создаёт ребёнка (E6), рубка и стрижка ждут дроп (G4); продукт
   профессии, `Supplies.replace`, `setBlock` и сбор урожая — открыты.
7. **Нет тестовых швов**: логика привязана к `Village/ServerLevel/BotHandle`, нет фейков, нет CI,
   soak — единственный gate, сам не тестируется и не видит игровых фич. *20.10:* CI есть (E2), soak-
   скрипты покрыты тестами (E4), ~100 новых тестов чистых помощников; фейков бота и инвентаря нет.
8. **Строковая семантика**: ключи кулдаунов по `kind()`, классификация застревания по русским
   сообщениям, причины провала как управление потоком, липкий `navReason`. *20.10:* `navReason`
   сбрасывается на каждый подход (E1), ключи добычи согласованы (G1); классификация по строкам осталась.
9. **Расходящиеся определения**: три предиката еды (`Supplies.food`, `Breeding.MEAL`, продукты
   `LandmarkGoods`), «под крышей» = проём двери/крыльцо, номер дня (gameTime vs часы), лимит сундуков 16
   vs 128, README vs код (8 из 27 переключателей не описаны, «не создают ресурсы из воздуха»).
   *20.10:* `Food` для рождений и босс-бара (E6; статус, `restRecovery` и `keptSlots` всё ещё на
   `Supplies.food`), настоящее «под крышей» (G3), один номер дня для летописи и новостей (E6; могилы —
   по gameTime); лимит 16 vs 128 и README открыты (переключателей теперь 34).
10. **Порядок проектов**: дома-первыми + `free` + `return` при неудаче площадки → после домов ничего
    не строится; видимые изменения (принцип 1) не наступают. *20.10:* исправлено (C1): общественная
    цепочка идёт параллельно с домами, площадки ищутся с кулдауном и переходом к следующему виду.

---

## 12. Исправлено 20 октября 2026

Слито в `claude/max-upgrade` (по `git log --first-parent master..claude/max-upgrade`, в порядке слияния;
в скобках — коммиты ветки):

- `task/e4-soak-tools` (efe3279) — soak-скрипты: честные провалы и код выхода 1, один jar на мод, атомарные загрузки шаблона, строки `SOAK` в `events.log`, `compare.py` без нулей за провалы, 36 тестов скриптов.
- `task/e5-build-hygiene` (d702d37, 8581092) — версия 0.3.0-dev, `soakClasses` в `check`, строгий список документов дистрибутива, без `Task.project` на этапе выполнения, таймаут хука 1800 с, рабочие свойства повторов Gradle 9.5.1.
- `task/e2-ci` (21e017c) — GitHub Actions: сборка, юнит-тесты мода и библиотеки, проверка Python-скриптов.
- `task/e6-presentation-fixes` (a2fe6cf) — бирки игрока сохраняются, доска только своя (`VillageState.noticeBoard`), бюджет чата и `chatAnnouncements`, единый `Food`, ребёнок раньше еды, `Chronicle.day`, `restoreName/close` не бросают, босс-бар чистит отключившихся.
- `task/e7-player-goods` (fc2a633) — `BookLayout` для ведомости, фильтр подарков и резерв 9 слотов, без синхронного сохранения на подарок, просьбы вместе с пожертвованиями и благодарность частичным дарителям, пир только за свои постройки, один удар колокола.
- `task/e3-hook-fallback` (9c76608, 388db19) — SessionStart-хук: запасные источники JDK, повторы клона, прогрев `testRuntimeClasspath`, повторы при 429 без ложных срабатываний, нефатальный прогрев и soak-шаблон.
- `task/e1-unreachable-stall` (aad6c60) — `UnreachableMemory` (~1000 тиков с удвоением до ~32 000), стройка откладывает недосягаемые ячейки и вырубки, `Projects.blocked`, сброс `navReason` на каждый подход, кворум для общих сундуков, «не добраться до склада» в `FetchJob`.
- `task/g2-lighting` (478ffd6) — без факелов на крышах и тропинках, предел уличного света по площади (160…960), пороги и комнаты без предела, круговой скан комнат, столбы не у дверей, откат забора, `LanternJob` только при месте, `PathJob` отвергает недосягаемые ячейки.
- `task/g1-mine-safety` (6a81e5d, ac97172) — кулдауны quarry/shaft, состояния шахты digging/finished/blocked и `mineRetryAt`, обрезка по границе, карьер без блоков у жидкостей, факелы в шахте, прогресс при `stop()`, без синхронных сохранений, русские причины отказа.
- `task/g3-shelter-patrol` (bdaf1db, 981abe2) — настоящее «под крышей» и поиск с бюджетом, аренда убежища каждый тик, патруль по уличным точкам до утра, безопасная упаковка снега без зацикливания, бегство к поверхности/домам, новое кладбище, таблички без лишних перезаписей и с подгонкой ширины.
- `task/g5-blueprints-goods` (ef2b1a5, 84f9894) — кэш списка шаблонов, все мастерские профессий, аудит досягаемости шаблонов (152 ванильных), `LandmarkGoods` не уничтожает товары при полном складе.
- `task/g4-forest-farm-craft` (dc8f00f, 6fdf6af) — согласованное состояние валки и подсадка пней, деревья не из построек, окно сбора 20 тиков, овцы с пропуском и парой, топливо одного вида на партию, однородные доски в крафтах, кэш воды полей.
- `task/c1-projects-flow` (3dedf07, 4d0a2a6) — общественная цепочка параллельно с домами (`Projects.decide`), кулдауны и курсоры площадок, улицы и крыши не площадки, амбар дверью к колоколу, необязательные высокие вырубки, частокол в границах, захват только новых кусков без меток игрока, изолированные хуки завершения.
- `task/d0-arch-docs` (3d92c48) — это руководство и реестр статического обзора со статусами.
- `3f68a21` — хаб механик `Features`: интерфейс, переключатели, слот `VillageState.features`, изоляция ошибок.
- `task/f5-mastery` (b1e0f39, fdce416) — звания мастерства, запись в летописи, звание в подписи жителя.
- `task/f6-livestock` (c0725eb) — скотный двор: разведение коров и кур, яйца, забой излишка.
- `task/f1-campfire` (dd88c30) — костёр на площади и вечерний круг.
- `task/f2-orchard` (abf959a, 4197bf0) — яблоневый сад, обход и яблоки на склад; лесорубы не трогают его деревья.
- `task/f3-heraldry` (160ad2b) — герб деревни из её имени, знамёна на вышке, амбаре, у колокола и над воротами.
- `task/f4-storm` (2ccf842, 25b430e) — укрытие жителей в грозу, громоотвод на вышке, строка в летописи.

Слияния `task/w2-*` и более поздние здесь не перечислены — см. `git log --first-parent master..claude/max-upgrade`.

---

## 13. Как добавить работу, постройку, механику или переключатель

Общие правила для любого нового кода: предметы не появляются из воздуха (берутся со склада или из
рюкзака через `Supplies`/`VillageStock.consume`; остаток `VillageStock.put` кладётся предметом в мир,
а не теряется), исключение не уходит в серверный тик, мир читается только в загруженных чанках
(`v.level.hasChunkAt(p)`), игровые строки — на русском. Решение выносится в статический помощник без
`ServerLevel`, и на него пишется тест в `src/test/java/dev/autonomousvillages/` (§0, §10).

**Работа жителя.**
1. Класс `XxxJob extends Job`: `kind()` — ключ кулдауна (`Worker.cooldown/ready`) и счётчика
   `Village.activeJobs`; `target()` — на него `Worker.start` берёт аренду `Reservations` (цель занята —
   работа молча отбрасывается); при нужде `timeLimit()`, `keeps(stack)` (что не сдавать на склад),
   `atomic()`, `activity()/mayBreak(p)` (если ломает блоки библиотечным майнером).
2. `tick()` возвращает `true`, пока работа идёт, и `false` по окончании; провал — `return fail("…")`.
   Строка причины — статус жителя и вход эвристики застревания `Worker.endJob` («не подойти», «не
   дойти», «уложился» считаются застреванием), её выбирают сознательно. Ходьба — `Approach`/`Walk`,
   ломание — `Harvest`, склад — `Fetch`/`Deposit`; их `cancel()` вызывается из `stop()`. Блоки ставятся
   только через `Construction.place` (зоны, досягаемость, списание предмета из рюкзака).
3. Недосягаемую цель отметить `Job.unreachable(p)` (память и голоса) или
   `NavHygiene.reject(v.rejected, p.asLong(), now()+…)` (не `put`: он укоротил бы более длинный отказ),
   иначе планировщик выберет её снова.
4. Подключить в `VillagePlanner.next` — в ветку роли или в общий хвост (порядок = приоритет) — с
   проверками `w.ready(kind)`, `v.activeJobs(kind)` и `v.ready(p, w.id)`. Работа, которую владелец может
   не хотеть, получает переключатель (ниже) или становится механикой.
5. Тест — предикаты выбора цели и вердикты (как `SafetyJobsTest`, `GatheringTest`); поведение в мире —
   soak ([tools/soak/README.md](../tools/soak/README.md)), строки `JOB_FAILED` в `events.log`.

**Постройка.**
- Своя достопримечательность: вид в `Landmarks.ORDER` (порядок) и `Landmarks.residents` (сколько
  жителей нужно), `label/title/feminine`, план в `Landmarks.design` из `Piece` (слой 0 — пол, вход на
  стороне z=0). `LandmarksTest` проверяет каждый дизайн на материалы, которые деревня делает сама,
  досягаемость строителя и опоры — новый вид проходит те же тесты. Суточный эффект готового здания —
  `LandmarkGoods.tick` (только то, что влезает: `room()`/`keep()`).
- Общественная постройка по ручному плану: вид в `Projects.CHAIN` (место в цепочке), условие в
  `Projects.eligible` (переключатель и число жителей), закладка в `Projects.lay` (площадка —
  `findSite`/`place`, без площадки вид ждёт `retryTicks`, строка — `seeking`), хуки завершения — в
  `Projects.complete` через `hook(...)`. Очередь решает чистая `Projects.decide(Queue)` — тест в
  `ProjectsTest`.
- Ванильные шаблоны подхватываются сами (`Blueprints.houses/civic`) и проходят `Blueprints.audit`
  (`BlueprintsAuditTest`).
- Строятся все виды одинаково — `TownBuilding` → `BuildJob` → `Construction.place`; материалы считаются
  по плану (`Projects.missing`), отдельный учёт не нужен.

**Механика (feature)** — самостоятельная надстройка со своим состоянием (§2, «Механики»).
1. Класс `XxxFeature implements Features.Feature`: `key()`, `enabled()` →
   `AutonomousVillagesMod.CONFIG.<key>`; экземпляр добавить в `Features.ALL`.
2. Переключатель `public boolean <key>=true;` в `ModConfig` — `FeaturesTest` требует поле с именем
   `key()`, `boolean`, включённое по умолчанию.
3. Состояние — класс для GSON: `Features.load(v, KEY, State.class, State::new)` и
   `Features.store(v, KEY, state)`, кэш на деревню — `WeakHashMap<Village, …>`. На диск его пишет
   периодический снимок: `runtime.save()` не вызывать; размер — до `Features.MAX_STATE_CHARS`.
4. `tick(v, time)` идёт каждый серверный тик — частоту механика ограничивает сама (`time % N`,
   `Features.throttled`). `job(v, w, role)` спрашивается у каждого простаивающего жителя на шаге 4d
   планировщика и почти всегда должен возвращать `null` (окна времени, `w.ready`, предел
   `v.activeJobs`), иначе механика съест работу ядра. `role == null` — малая бригада, где все делают всё.
5. Тесты — чистые правила механики (`CampfireFeatureTest`, `OrchardFeatureTest`, `LivestockTest`,
   `MasteryTest`).

**Переключатель.** `public boolean <имя>=true;` с однострочным javadoc в `ModConfig`; читается как
`AutonomousVillagesMod.CONFIG.<имя>` там, где решается поведение. `ModConfig.load` переписывает
`config/autonomous_villages.json` при каждом старте, поэтому новый ключ появляется у владельцев со
значением по умолчанию, а их прежний выбор сохраняется (`ModConfigTest`). Описание — в таблице
переключателей README.

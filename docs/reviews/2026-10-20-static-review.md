# Статический обзор кода — 20 октября 2026

Реестр проблем Autonomous Villages, найденных статическим чтением ревизии master `5781aae`, со статусом
исправления на интеграционной ветке `claude/max-upgrade` (0.3.0-dev). Устройство кода описано в
[руководстве по архитектуре](../ARCHITECTURE.md); предыдущий обзор —
[внешнее ревью 19 октября](2026-10-19-external-review.md).

## Метод

- **Девять читателей.** Девять агентов-читателей, каждый на свою подсистему (lifecycle, economy,
  gathering-jobs, safety-town-jobs, construction, life-presentation, tooling-tests, docs-product,
  navigation-usage), прочитали код ревизии master `5781aae` (слияние PR #12) и составили карту
  подсистемы: устройство, находки, возможности.
- **Синтез.** Агент-синтезатор свёл 179 находок в 151 уникальную (одинаковые `файл:строка` или общая
  первопричина слиты; слитые источники перечислены у записи), разложил их по серьёзности (10 высоких,
  79 средних, 62 низких) и перепроверил чтением кода все высокие и часть средних и низких. Там, где
  карты расходились, решение принято по коду.
- **Только чтение.** Код читали, но не запускали; данные soak-прогонов (`docs/WORKLOG.md`) использованы
  лишь как подтверждение симптомов. Номера строк — для `5781aae` (смотреть через
  `git show 5781aae:<путь>`); на ветке 0.3.0-dev строки сдвинулись.
- **Пометки достоверности.** **[verified]** — подтверждено чтением кода при синтезе; **[doubtful]** —
  вероятно ложное срабатывание или неверная деталь (с причиной); **[unverified]** — не проверялось или
  требует запуска. Без пометки — находка карты, синтезом не перепроверенная.
- **Формат записи.** Заголовок, статус, `файл:строка` · вид · источник(и), описание, предложенное
  исправление. Описания оставлены в формулировке читателей (частью на английском), чтобы не исказить
  технические детали; заголовки, статусы и пояснения к ним — на русском. Ни одна находка не удалена.

## Статусы

Проверено 20 октября 2026 по `claude/max-upgrade` на `9ea5c7c`: сообщения и диффы слитых коммитов
(`git log master..claude/max-upgrade`) и выборочная проверка кода.

- **исправлено** — слитая ветка закрывает находку (оговорки — в пояснении);
- **частично** — слитая ветка закрывает часть находки, остаток открыт (что осталось — в пояснении);
- **в работе** — есть ветка `task/*`, ещё не слитая в `claude/max-upgrade`; на момент проверки таких
  веток нет: все 13 веток `task/*` слиты;
- **открыто** — ни одна ветка находку не трогала.

| Код | Ветка | Коммиты | Тема |
|---|---|---|---|
| E1 | `task/e1-unreachable-stall` | aad6c60 | память недосягаемых целей |
| E2 | `task/e2-ci` | 21e017c | CI на GitHub Actions |
| E3 | `task/e3-hook-fallback` | 9c76608, 388db19 | SessionStart-хук |
| E4 | `task/e4-soak-tools` | efe3279 | soak-скрипты |
| E5 | `task/e5-build-hygiene` | d702d37, 8581092 | сборка, версия, повторы Gradle |
| E6 | `task/e6-presentation-fixes` | a2fe6cf | представление: бирки, доска, чат, еда |
| E7 | `task/e7-player-goods` | fc2a633 | ведомость, подарки, просьбы, пиры |
| G1 | `task/g1-mine-safety` | 6a81e5d, ac97172 | добыча и шахта |
| G2 | `task/g2-lighting` | 478ffd6 | освещение |
| G3 | `task/g3-shelter-patrol` | bdaf1db, 981abe2 | ночная безопасность |
| G4 | `task/g4-forest-farm-craft` | dc8f00f, 6fdf6af | лес, поле, печь, крафт |
| G5 | `task/g5-blueprints-goods` | ef2b1a5, 84f9894 | шаблоны и товары зданий |
| C1 | `task/c1-projects-flow` | 3dedf07, 4d0a2a6 | очередь проектов |

| Серьёзность | Всего | исправлено | частично | в работе | открыто |
|---|---|---|---|---|---|
| Высокая | 10 | 4 | 4 | 0 | 2 |
| Средняя | 79 | 38 | 9 | 0 | 32 |
| Низкая | 62 | 22 | 9 | 0 | 31 |
| **Итого** | **151** | **64** | **22** | **0** | **65** |

## Реестр

| ID | Находка | Статус | Ветки |
|---|---|---|---|
| H1 | Обработчики событий Fabric без try/catch: исключение уходит в vanilla `die()`, загрузку сущностей чанка или остановку сервера | частично | E6 |
| H2 | Один житель, чей `attach` в библиотеке падает, замораживает всю деревню | открыто | — |
| H3 | Планировщик бросает NPE на работниках без загруженного бота — ни одно решение не проходит, пока нет доски объявлений | открыто | — |
| H4 | Недосягаемый сундук склада не распознаётся при стопе маршрута, а его содержимое считается доступным — строители вечно «материалы не донесены» | исправлено | E1 |
| H5 | Свежие дропы невидимы для `collect()`: урожай, малые деревья и шерсть остаются на земле | частично | G4 |
| H6 | Работы добычи обходят кулдаун провала и отклонение входа: проваленный карьер/шахта перезапускается каждые 20 тиков | исправлено | G1 |
| H7 | Лестничная шахта не имеет терминального состояния для постоянных препятствий и повторяет один и тот же провал бесконечно | исправлено | G1 |
| H8 | Непостроимые ячейки (недосягаемые, неустанавливаемые, невырубаемые) повторяются вечно — проект никогда не завершается и блокирует всё после себя | частично | E1, C1 |
| H9 | `paid()` вызывается при создании работы, а не при установленном блоке — суточный fallback никогда не срабатывает | частично | E1, C1 |
| H10 | Поиск площадки амбара и колодца — 32 фиксированные точки без таймера повтора, и провал блокирует все последующие проекты | исправлено | C1 |
| M1 | Синхронное полное сохранение на потоке сервера из 25 горячих мест | частично | E7, G1, G4 |
| M2 | Персистентность «всё или ничего»: одна невалидная деревня блокирует сохранение всех, одна плохая запись отключает мод при загрузке | открыто | — |
| M3 | `Village.tick` не изолирует фазы: одна бросающая фича пропускает цикл работников или ночную безопасность дверей | открыто | — |
| M4 | POI-запрос кроватей покрывает квадрат в ~23 раза больше территории и выполняется каждый тик, пока кроватей нет | открыто | — |
| M5 | Периодический survey: BFS по POI с дублями во фронтире, цепочки до 1800 блоков, финальный запрос радиусом до 2048 | открыто | — |
| M6 | `sync()` выполняется каждый тик у деревень без загруженных взрослых | открыто | — |
| M7 | Жители, исчезнувшие без события смерти, никогда не вычищаются: держат 25 чанков и завышают спрос на жильё | открыто | — |
| M8 | `/avillage storage` отказывает в новых сундуках, когда деревня сама владеет 16 | открыто | — |
| M9 | Повреждённый конфиг молча перезаписывается значениями по умолчанию | открыто | — |
| M10 | Файл сохранения не fsync-ится, нет резервной копии и отката | открыто | — |
| M11 | Лимиты метаданных (печи 16, сундуки 128) блокируют саму установку блока — шаг чертежа с печью/сундуком никогда не ставится | открыто | — |
| M12 | `Approach` считает каждую проваленную стоячую ячейку дважды — реально пробуются 2 ячейки вместо 4 | открыто | — |
| M13 | Аренда цели истекает через 1200 тиков, не продлевается и покрывает только первую цель работы | частично | G3 |
| M14 | `storeJob` крафтит второй верстак/сундук, пока работник уже несёт один | открыто | — |
| M15 | Склад «не полон» из-за сундуков, до которых никто не дойдёт — депозиты останавливаются, добыча копится в рюкзаках | открыто | — |
| M16 | Скрафченные стройматериалы (кровати, двери, плиты) остаются в рюкзаке крафтера и стопорят строителей | открыто | — |
| M17 | `mine()` делает снимок инвентаря на каждого кандидата камня/руды до дешёвых фильтров | открыто | — |
| M18 | Снимки инвентаря на каждый вызов: `owned()` O(workers²), `Fetch/Deposit` копируют рюкзак каждый тик, кэш `count` промахивается на захватывающих лямбдах | открыто | — |
| M19 | Выгруженный/отсоединённый работник держит работу вечно и блокирует гейты `activeJobs`/`busy` | открыто | — |
| M20 | Провал первого сегмента шахты заново выбирает тот же вход, спамит летопись и форсит синхронное сохранение | исправлено | G1 |
| M21 | Провалы Harvest в карьере молчаливы: цели не отклоняются, работа не фейлится, тот же карьер планируется снова | исправлено | G1 |
| M22 | Карьер ломает блоки без проверки соседних жидкостей — шахты и ямы затапливаются или впускают лаву | исправлено | G1 |
| M23 | Состояние валки несогласовано: `fellingTrees` никогда не заполняется, `treeWork` пишется до среза, `reforestation` только пишется | исправлено | G4 |
| M24 | Детекция деревьев и валка могут разобрать постройки игрока из брёвен и игнорируют зоны политики для листвы | исправлено | G4 |
| M25 | Печь получает слишком мало топлива для партии, остаток сырья застревает | исправлено | G4 |
| M26 | `ShearJob` гоняется за одной недосягаемой овцой до таймаута, а таймаут выглядит как застревание | исправлено | G4 |
| M27 | Неосвещённая 64-ступенчатая шахта внутри территории — спаунер монстров | исправлено | G1 |
| M28 | `farm()` читает 162 состояния жидкости на каждую площадку; `farmSites` и `shaftOre` только растут | частично | G4 |
| M29 | Проёмы дверей и крытые крыльца считаются «под крышей» — укрывающиеся жители стоят в двери или на крыльце | исправлено | G3 |
| M30 | Тревога, переподнимаемая спрятавшимися жителями, замораживает работу всех безоружных по всей деревне | открыто | — |
| M31 | `HazardJob` пакует пороховой снег в полный блок поверх сущностей и игнорирует зоны политики | исправлено | G3 |
| M32 | Сеточные пробы света попадают на плоские крыши — факелы и фонарные столбы ставятся на крышах | исправлено | G2 |
| M33 | Всё освещение останавливается на 160 записанных огнях, включая спальни и пороги новых домов | исправлено | G2 |
| M34 | `darkRooms` пересканирует все якоря каждые 200 тиков, когда деревня уже освещена | исправлено | G2 |
| M35 | Точки патруля — кровати внутри домов: стражи открывают двери ночью и повторяют проваленные точки каждый круг | исправлено | G3 |
| M36 | «Бездомность» (`beds < residents+2`) идёт первой — растущая деревня никогда не доходит до амбара и вышки | исправлено | C1 |
| M37 | Дверь амбара зафиксирована на северной стене, проём не проверяется — сундуки склада могут оказаться недосягаемы | исправлено | C1 |
| M38 | Вырубки выше досягаемости строителя считаются в завершение (амбар и вышка режут до y=6) | исправлено | C1 |
| M39 | `siteAt` принимает улицы деревни и плоские крыши незахваченных структур | исправлено | C1 |
| M40 | Частокол: origin может оказаться вне границ (стена выбрасывается при загрузке и перезакладывается в цикле), столбы на крышах, кольцо никогда не расширяется | частично | C1 |
| M41 | Партии частокола разбросаны по всему кольцу: долгие переходы, таймауты, очередь заблокирована днями | открыто | — |
| M42 | `capture` перезапускается после каждого рестарта и усыновляет сундуки любого здания с кроватью, включая дома игрока | исправлено | C1 |
| M43 | Листинг шаблонов пересканирует ресурсы при каждом вызове; поиск мастерской повторяет дорогой скан каждые 100 тиков | исправлено | G5, C1 |
| M44 | Остаток `VillageStock.put()` игнорируется — предметы уничтожаются при полном складе | частично | G5, G2 |
| M45 | `clear()`/`removable()` — рукописные списки, не знающие растений 26.x | открыто | — |
| M46 | Ванильные шаблоны домов никогда не проверяются на досягаемость и опоры | исправлено | G5 |
| M47 | Именные бирки игрока на жителях перезаписываются в течение секунды и теряются | исправлено | E6 |
| M48 | `restoreName` может бросить из путей ENTITY_LOAD и SERVER_STOPPING | исправлено | E6 |
| M49 | Босс-бар держит отключившихся `ServerPlayer` навсегда | исправлено | E6 |
| M50 | Доска объявлений захватывает любую табличку у колокола, включая игрока и вывески зданий | исправлено | E6 |
| M51 | Страницы ведомости переполняются: ~4 из 10 жителей видны, жители после 40 отброшены; build/store/donor страницы >14 строк; строка просьбы стоит под «Не хватает:» | исправлено | E7 |
| M52 | Предикаты еды расходятся: рождения игнорируют еду таверны, босс-бар «еда» считает другое, `restRecovery` не ест рыбу/мясо | частично | E6 |
| M53 | Мусорные пожертвования заполняют склад, и работники перестают сдавать добычу | исправлено | E7 |
| M54 | Просьба молча снимается после частичной доставки; даритель ничего не получает | исправлено | E7 |
| M55 | Просьбы включены при выключенных пожертвованиях — просьбу невозможно выполнить | частично | E7 |
| M56 | Объявления в чат без лимита и без переключателя | исправлено | E6 |
| M57 | Причины смерти показываются сырыми id сообщений или английскими именами мобов | открыто | — |
| M58 | Базовый провал тестов: прогрев не разрешает `testRuntimeClasspath`, затем Maven Central 429 валит обе задачи `test` | исправлено | E3, E5 |
| M59 | SessionStart-хук без таймаута делает минуты работы | исправлено | E5, E3 |
| M60 | Source set `soak` не компилируется `build`/`check` — рефакторинги мода молча ломают soak-gate | исправлено | E5 |
| M61 | Версия проекта всё ещё 0.2.2, хотя код — ломающая сохранения линия 0.3.0 | исправлено | E5 |
| M62 | `compare.py` считает провалившиеся прогоны нулями и суммирует разные наборы меток | исправлено | E4 |
| M63 | Перепись `builtBlocks` не видит кладку и другие блоки шаблонов — метрика смещена по биому | открыто | — |
| M64 | `run.py`: один упавший job молча убивает своего воркера; код выхода всегда 0 | исправлено | E4 |
| M65 | `run.py --jars` может положить два jar'а navigation-ai в `mods/` | исправлено | E4 |
| M66 | Код перемещения предметов и ядро экономики не покрыты юнит-тестами (сохранение количества) | открыто | — |
| M67 | README обещает «не создают ресурсы из воздуха, не телепортируют груз» — код делает и то и другое | открыто | — |
| M68 | Справочник конфига в README пропускает 8 из 27 переключателей | открыто | — |
| M69 | `navReason` никогда не сбрасывается: старые провалы маршрута заражают последующие несвязанные провалы | исправлено | E1 |
| M70 | Подбор предмета отменяет маршрут подбора; с полным рюкзаком это перепланирование каждый тик | открыто | — |
| M71 | Лечение на отдыхе отменяет дорогу домой или патруль; три лечения проваливают `HomeJob` и считаются застреванием | открыто | — |
| M72 | Задачи опрашиваются через `completion().isDone()`, каждый опрос аллоцирует и удерживает копию future до завершения | открыто | — |
| M73 | Время ожидания планировщика маршрутов засчитывается в лимит работы и в детекцию «застрял» | открыто | — |
| M74 | Занятая стоячая ячейка → маршрут падает `unreachable` сразу, и это питает голоса о недосягаемости сундуков | открыто | — |
| M75 | `Fetch`/`Deposit` застрявшего работника перебирают все сундуки по очереди | частично | E1 |
| M76 | `PatrolJob` никогда не фейлится при недосягаемых точках и запрашивает маршруты всю ночь | исправлено | G3 |
| M77 | Ходьба к движущимся целям сбрасывает бюджет повторов при каждом перенацеливании | частично | G4 |
| M78 | Бегство перепланируется каждый тик, пока угроза в поле зрения | открыто | — |
| M79 | `Worker.endJob` отклоняет цель лишь на 600 тиков, перезаписывает более длинные сроки и не даёт бэкоффа недосягаемым целям | открыто | — |
| L1 | `safeFeet`/`feetPosition` читают состояния блоков до проверки загрузки чанка | открыто | — |
| L2 | `mayBreak`, вызываемый библиотекой на каждую ячейку снимка, аллоцирует стримы и читает BlockState каждый раз | открыто | — |
| L3 | Диапазон зависимости допускает несовместимые сборки navigation-ai; `LinkageError` минует catch тика | открыто | — |
| L4 | Ошибки сохранения никогда не показываются операторам; периодический успех не сбрасывает флаг | открыто | — |
| L5 | Валидация claim и обработка частичного провала | открыто | — |
| L6 | Нет тестов для персистентности рантайма, claim, аренд чанков, расширения и путей ошибок load/writeSave | открыто | — |
| L7 | Планировщик предлагает крафты при включённом gamerule `doLimitedCrafting` | открыто | — |
| L8 | Крафты досок без гейтов «уже крафтится» и доступности | открыто | — |
| L9 | Fetch семян и таблички игнорируют кулдаун «fetch» и зацикливаются на недосягаемом сундуке | открыто | — |
| L10 | `Supplies.replace` без отката: может дублировать или уничтожить предметы | открыто | — |
| L11 | Snapshot ищет block entity печей без проверки загрузки чанка | открыто | — |
| L12 | `CompostJob` переиспользует один `Approach` для повторных походов — счётчик попыток исчерпывается ложным провалом | исправлено | G4 |
| L13 | `ChopJob` перепроверяет и пересортировывает весь список брёвен на каждом тике подхода | исправлено | G4 |
| L14 | `BreathingSafety` сканирует 8125 ячеек с рейкастами каждый тик, пока не находит воздух / после каждого провала маршрута | открыто | — |
| L15 | Когерентные рецепты считаются доступными при смешанных досках без брёвен — крафт проваливается снова и снова | исправлено | G4 |
| L16 | Остановка работы шахты выбрасывает уже вырытые сегменты | исправлено | G1 |
| L17 | `HerdJob` выбирает вторую овцу по расстоянию до работника — пара может никогда не размножиться | исправлено | G4 |
| L18 | Нет тестов для добывающей логики; алгоритмы нельзя тестировать, так как они берут живую `Village` | исправлено | G1, G4 |
| L19 | Лимит времени `PatrolJob` короче ночного окна — каждый страж «проваливается» каждое утро | исправлено | G3 |
| L20 | `ShelterJob.find` — неограниченный скан ячеек вне бюджета решений | исправлено | G3 |
| L21 | `EscapeJob` копает до самого колокола, в том числе ночью | исправлено | G3 |
| L22 | Проверка «табличка не изменилась» никогда не срабатывает: каждая табличка переписывается и пересылается каждые 30 с | исправлено | G3 |
| L23 | Строки табличек шире 90-px знака: счётчик «и ещё N» и длинные имена обрезаются | исправлено | G3 |
| L24 | `PathJob` никогда не помечает недосягаемые ячейки тропинки — они предлагаются вечно | исправлено | G2 |
| L25 | `HazardJob` (и `FlowerJob` по таймауту) теряют счётчики летописи | частично | G3 |
| L26 | Кладбище не перевыбирается, когда заполнено/заблокировано; неудачный поиск повторяется | исправлено | G3 |
| L27 | Фонарный столб остаётся голым забором, если факел не удалось поставить после забора | исправлено | G2 |
| L28 | Фонарные столбы могут перекрыть единственный проход к двери или в переулок | исправлено | G2 |
| L29 | Фильтр мастерских выбрасывает дома бронника, лучника, каменщика и пастуха | исправлено | G5 |
| L30 | Каждая установка блока форсит полную перестройку индекса защиты | открыто | — |
| L31 | Сопоставление плана аллоцирует в горячих циклах и обходит кэш подсчёта склада | открыто | — |
| L32 | Исключение в хуке завершения навсегда пропускает поздние хуки здания и сохранение | исправлено | C1 |
| L33 | `Construction.place` игнорирует результат `setBlock` после списания предмета и подписывает каждый знак «СКЛАД» | открыто | — |
| L34 | Нет тестов автомата проектов, `siteAt`, `palisade()`, подмен и рукописных построек | частично | C1 |
| L35 | Номер дня различается между летописью и утренними новостями | частично | E6 |
| L36 | Пир может праздновать захваченный родной дом или не то здание | исправлено | E7 |
| L37 | Рождение звонит в колокол трижды; колокола ярмарки и рождения звучат там, где колокола нет | исправлено | E7 |
| L38 | Еда съедается до того, как ребёнок существует; неудачный спаун всё равно записывает рождение | исправлено | E6 |
| L39 | Текст доски обрезан, кэш не привязан к позиции знака, английские id материалов | частично | E6 |
| L40 | Рутинные события вытесняют важную историю из 60-записной летописи | частично | G4 |
| L41 | Неверный грамматический род и дубли имён | открыто | — |
| L42 | Хук клонирует navigation-ai HEAD и «последнюю» JDK без пина и проверки | открыто | — |
| L43 | Задачи релиза жёстко ссылаются на файлы эпохи 0.2.2 и молча пропускают отсутствующие | частично | E5 |
| L44 | `Task.project` на этапе выполнения (несовместимо с Gradle 10, уже репортится) | исправлено | E5 |
| L45 | Периодическая перепись вычисляется и выбрасывается, съедая время тика внутри измеряемого прогона | открыто | — |
| L46 | `writeMap` читает heightmap возможно незагруженных чанков, форсируя синхронную загрузку/генерацию | открыто | — |
| L47 | Загрузки шаблона не атомарны: усечённый jar навсегда проходит проверку готовности | частично | E4 |
| L48 | Крайние случаи CLI роняют инструменты пост-обработки | частично | E4 |
| L49 | `events.log` не содержит собственных строк пробы; `logErrors` насыщается на 200 | исправлено | E4 |
| L50 | `make_timelapse.py` работает только на одной Windows-машине | открыто | — |
| L51 | Некоторые тесты ничего не проверяют о моде | открыто | — |
| L52 | Поведение големов описано противоречиво в комментариях и документах | открыто | — |
| L53 | Устаревшие документы 0.2 дают неверные инструкции и попадают в дистрибутив | открыто | — |
| L54 | CONTRIBUTING противоречит реальной настройке (ревизия библиотеки, имена каталогов, упаковка) | открыто | — |
| L55 | PLAN-v0.3 частично устарел и без статуса | открыто | — |
| L56 | Раздел CHANGELOG 0.3 устарел и неполон | открыто | — |
| L57 | Отчёт внешнего ревью не говорит, какие рекомендации ещё применимы к 0.3 | открыто | — |
| L58 | Нет тестов, охраняющих документированные правила и синхронизацию документов с кодом | открыто | — |
| L59 | Маршруты подбора переживают `Harvest` и саму работу | открыто | — |
| L60 | Классификация застревания — сопоставление русских UI-строк | открыто | — |
| L61 | Бой сбрасывается через 600 тиков без отмены | открыто | — |
| L62 | Нет юнит-тестов логики маршрутов | частично | E1 |

---

## Высокая серьёзность

### H1. Обработчики событий Fabric без try/catch: исключение уходит в vanilla `die()`, загрузку сущностей чанка или остановку сервера

**Статус: частично** (E6) — `restoreName` и `close()` больше не бросают (пути ENTITY_LOAD и SERVER_STOPPING); тела обработчиков AFTER_DEATH/ENTITY_LOAD/SERVER_STARTED по-прежнему без try/catch, синхронный `save()` внутри `die()` остался.

`src/main/java/dev/autonomousvillages/AutonomousVillagesMod.java:51` · robustness · lifecycle, docs · **[verified]**

AFTER_DEATH (51-69) runs `worker.job.stop()`, reservations, `chronicle.died` and a synchronous `runtime.save()` inside `LivingEntity.die`; ENTITY_LOAD → `residentLoaded` → `bots.attach` (throws on duplicate UUID / existing controller / failed restore), `handle.detach`, `restoreName` (`getOrThrow`), `save()`; SERVER_STARTED runs the whole VillageRuntime constructor; SERVER_STOPPING → `runtime.close()` (a throw skips ticket release and leaves `runtime` non-null). Review fix #9 (try/catch) was lost in the 0.3 rewrite although CHANGELOG implies it is present. Any throw becomes a "Ticking entity" crash or a crash loop per chunk load.

**Предложенное исправление:** wrap each handler body in `try{…}catch(RuntimeException)` with a rate-limited `LOG.error`; in `close()` guard each village separately and null `runtime` in `finally`; in `residentLoaded` keep the pending entry for retry.

### H2. Один житель, чей `attach` в библиотеке падает, замораживает всю деревню

**Статус: открыто** — `sync()` по-прежнему вызывает `bots.attach` без try/catch.

`src/main/java/dev/autonomousvillages/Village.java:217` · robustness · lifecycle · **[verified]**

`sync()` calls `runtime.bots.find(id).orElseGet(()->runtime.bots.attach(v))` for every loaded adult inside one loop with no try/catch. `BotRuntime.attach` throws for "Duplicate controlled UUID", "Villager already has a controller" or a saved-state restore failure (likely after a navigation-ai format change). The exception aborts `sync()` and `Village.tick` before the worker loop; while `workers` is empty `sync()` runs every tick (line 121) and throws every tick. Only trace: "ошибка планирования" in status.

**Предложенное исправление:** per-resident try/catch; on failure `failedAttach.put(id, now+1200)`, one WARN, skip until retry.

### H3. Планировщик бросает NPE на работниках без загруженного бота — ни одно решение не проходит, пока нет доски объявлений

**Статус: открыто** — `boardCarried` и `Worker.has/bag()` не проверяют отсутствие бота.

`src/main/java/dev/autonomousvillages/VillagePlanner.java:171` · robustness · economy · **[verified]** (`boardCarried` → `o.has(...)` → `Supplies.find(bot(),…)` → `bot.inventory()` with `bot()==null`)

`boardCarried()` streams all workers without `o.active()`. `Worker.has` → `Supplies.find(bot(),…)` dereferences a null bot once navigation-ai detached the villager (unload, dimension change, restore failure); `Village.sync` keeps such Workers. The board branch of `storeJob` runs for every idle worker while `v.noticeBoard()==null`, so every `next()` throws, the worker is cancelled, and 20 ticks later it throws again — nobody gets a job. `Projects.java:496` has the same pattern (`o.count(...)` without `active()`).

**Предложенное исправление:** filter `o.active()` in both; make `Worker.bag()/count()/has()/freeSlots()` return empty/0 when `bot()==null`; unit-test planner helpers with a bot-less Worker.

### H4. Недосягаемый сундук склада не распознаётся при стопе маршрута, а его содержимое считается доступным — строители вечно «материалы не донесены»

**Статус: исправлено** (E1) — `UnreachableMemory`: сундук, к которому не дошёл маршрут, сообщивший житель пропускает сразу, а деревня (и подсчёт склада для `affordable`) — после двух разных жителей; `FetchJob` различает «нет на складе» и «не добраться до склада». Остановка сторожа (`cancelled`) намеренно не считается недосягаемостью.

`src/main/java/dev/autonomousvillages/Job.java:64` · bug · economy (construction opp.) · **[verified]**

`Job.unreachable` votes only when `navReason ∈ {unreachable, no_stand}`. When `Approach` gives up because the body stalls (`Progress.stalled` → `nav.cancel()`), `noteRoute` records `cancelled`, so no vote. `Village.unreachableFrom` also needs two different voters within 2400 ticks, and usually only builders fetch building goods. Meanwhile `BuildJob.affordable` (BuildJob:40) and `CraftJob.affordable` (CraftJob:78) count items in that chest via `VillageStock.count`, so the planner keeps issuing BuildJobs whose Fetch fails. WORKLOG seed 8675309: house 136/140 for 1.5 days, "материалы не донесены (unreachable)" ×22.

**Предложенное исправление:** per-worker `Map<Long,Long>` of chests it failed to reach (any Approach FAILED in Fetch/Deposit, stalls included); Fetch/Deposit skip them; `VillageStock.count(test, worker)` excludes them and is used by `BuildJob.affordable`/`CraftJob.affordable`; count stalled/cancelled approaches as votes.

### H5. Свежие дропы невидимы для `collect()`: урожай, малые деревья и шерсть остаются на земле

**Статус: частично** (G4) — `ChopJob` и `ShearJob` собирают дроп не меньше 20 тиков после последнего удара (`Gathering.keepCollecting`); `Job.Harvest` (поле, вырубки стройки, карьер) по-прежнему заканчивает сбор сразу.

`src/main/java/dev/autonomousvillages/Job.java:235` · bug · gathering · **[verified]** (filter `e.isAlive()&&!e.hasPickUpDelay()`; `Harvest` → `collectUntil=now+60` but returns DONE as soon as `collect()` is false)

Vanilla gives every drop a 10-tick pickup delay. `Harvest` calls `collect(target,6)` on the tick the miner finishes, sees nothing and returns DONE (FarmJob ends at once). ChopJob COLLECT starts ~7-9 ticks after the first log fell; all drops still delayed → ends at once; REPLANT finds no sapling. ShearJob checks one tick after `shear()`. Items are picked up only by accidental body contact or a later harvest within 6 blocks; the last batch despawns. MODLOG already noted "keep collecting for 80 ticks" — regressed.

**Предложенное исправление:** treat delayed drops as pending (query `isAlive()` only, walk to nearest, expect pickup after delay); enforce a minimum window (`now >= breakTick+15`) in Harvest, ChopJob COLLECT and ShearJob; optionally limit to drops not older than the job.

### H6. Работы добычи обходят кулдаун провала и отклонение входа: проваленный карьер/шахта перезапускается каждые 20 тиков

**Статус: исправлено** (G1) — Планировщик проверяет кулдауны quarry/shaft (`MineJob.quarryAllowed/shaftAllowed`, в т.ч. для песка), отвергнутый вход пропускается через `MineJob.shaftCall`.

`src/main/java/dev/autonomousvillages/MineJob.java:23` · bug · gathering · **[verified]** (`kind()` = "quarry"/"shaft"; `Worker.endJob` → `cooldown(job.kind(),200)`; planner `mine()`/`sand()` gate on `w.ready("mine")`; `mineEntrance()` returns the stored entrance without `v.ready`)

A failed mining job gets no cooldown at all, and `Worker.endJob` puts the shaft entrance in `v.rejected` but `VillagePlanner.mine()` (474-475, 481-483) never checks `v.ready(entrance)` — the same failing shaft is handed back on the next decision.

**Предложенное исправление:** `Job.cooldownKey()` (default `kind()`, "mine" for MineJob) used by `endJob`; skip the entrance when `!v.ready(entrance, w.id)`.

### H7. Лестничная шахта не имеет терминального состояния для постоянных препятствий и повторяет один и тот же провал бесконечно

**Статус: исправлено** (G1) — `mineStatus` digging/finished/blocked, длина прогона обрезается по границе и дну, 2 постоянных (6 временных) провала → blocked и поиск другого края; шахта у границы возобновляется, когда границы выросли; срок повтора в `mineRetryAt`.

`src/main/java/dev/autonomousvillages/MineJob.java:60` · robustness · gathering · **[verified]** (`if(!v.state.contains(request.positionAt(length)))return fail(...)` every attempt; `mineEntrance()` returns the entrance while `mineLength<64`)

Once `mineLength+12` runs past the bounds (any village narrower than ~72 blocks on that axis) every attempt walks to the entrance and fails "шахта упёрлась в границу деревни". Permanent library reasons (falling_block, unstable_ceiling, unsafe_fluid, world_bounds, protected_block_entity, not_allowed) fail at the same segment every time. The `ironWanted` path (iron → shears → wool → beds) prefers the shaft, so a miner is lost to an endless walk-and-fail loop (compounded by H6).

**Предложенное исправление:** clamp the request length to the last in-bounds segment and treat `s==done` as exhausted; after two permanent failures at one segment record it (e.g. `mineBlocked`), start a new flight from the last landing in a rotated direction or set `mineLength=64`; announce once.

### H8. Непостроимые ячейки (недосягаемые, неустанавливаемые, невырубаемые) повторяются вечно — проект никогда не завершается и блокирует всё после себя

**Статус: частично** (E1, C1) — Недосягаемые ячейки и вырубки откладываются памятью `UnreachableMemory`, проект «заблокирован» (`Projects.blocked`) и не держит очередь; вырубки выше досягаемости не считаются в завершение. Ячейки, которые `Construction.place` отказывается ставить (`canSurvive`, лимиты, зоны), памяти не имеют.

`src/main/java/dev/autonomousvillages/Projects.java:557` · bug · construction · **[verified]** (`remaining/nextSteps/pendingCuts` deterministic; BuildJob drops a failed cell only from its own `cuts`/`steps`, no memory)

`remaining()` counts every unmatched structural cell and removable cut; `nextSteps`/`pendingCuts` return the same cells first every time. BuildJob drops a failed cell only for the current job (approach failed, harvest failed, place refused) and ends after 6 misses, often before reaching buildable cells. Causes: no stand within reach (eye ≤3.55 → nothing above layer 5), `canSurvive` failing for a torch/ladder on a never-placed decoration block, a door's upper cell blocked, a bed on a non-sturdy block, a chest at the 128 cap, a policy zone added later, a cut another village protects. A stuck house makes line 137 return every cycle; a stuck wall/tower/landmark keeps `free` false.

**Предложенное исправление:** transient `Map<Long,Integer>` of strikes per cell in Projects filled from BuildJob failures; cells with ≥3 strikes skipped for a day and excluded from `remaining()`; treat `!policy.allows("build",p)` as blocked; optionally close a project after N days without progress.

### H9. `paid()` вызывается при создании работы, а не при установленном блоке — суточный fallback никогда не срабатывает

**Статус: частично** (E1, C1) — `paid()` по-прежнему вызывается при создании работы, но `waiting()` истинно для заблокированного проекта, а общественные постройки закладываются параллельно с домами.

`src/main/java/dev/autonomousvillages/VillagePlanner.java:248` · bug · construction · **[verified]** (`v.town.paid(project); return job;` in `build()`; `waiting()` measures from `unpaidSince`)

`waiting(b)` measures idle time from `unpaidSince`, which `paid()` resets whenever a BuildJob is created (whenever anything is affordable or a cut is pending). A house that keeps receiving jobs that place nothing (unreachable cells, a store chest that comes back after each rejection, refused placements) never counts as waiting; line 137 keeps returning and construction stops.

**Предложенное исправление:** track `lastProgress` per project origin updated when a block of that project is placed; base `waiting()` on it (keep the 3000-tick rule for non-everyday materials); call `unpaid()` only when nothing is affordable.

### H10. Поиск площадки амбара и колодца — 32 фиксированные точки без таймера повтора, и провал блокирует все последующие проекты

**Статус: исправлено** (C1) — Каждый вид ищет площадку своим курсором с расходящимися кольцами, без площадки — кулдаун 2400 тиков и переход цепочки к следующему виду.

`src/main/java/dev/autonomousvillages/Projects.java:153` · bug · construction · **[verified]** (`for(attempt<32)…; if(site==null){stage=…;return;}` for barn and well; tower also returns)

Barn (153-157) and well (163-167) try the same deterministic positions (rings 5-11 / 4-10 around the bell) every 100 ticks and `return` when none fits; the chain is else-if with barn first, so while `storehouse()==null` nothing after it can be reached. `siteAt` also rejects any base within 10 blocks of a native house origin, common in a dense centre.

**Предложенное исправление:** rotating cursor with wider rings, `barnRetry/wellRetry` timers like `palisadeRetry`, and fall-through to the next branch instead of `return`.

---

## Средняя серьёзность

### M1. Синхронное полное сохранение на потоке сервера из 25 горячих мест

**Статус: частично** (E7, G1, G4) — Убраны синхронные сохранения на каждый подарок, сегмент и выбор входа шахты, сваленное дерево. Осталось 21 место в 7 файлах (на `5781aae` их было 31 в 11 файлах: «25» в тексте ниже не учитывает 6 вызовов `VillageRuntime`), включая `die()`, рождение, сундук/печь, закладку и завершение здания; периодическая пересериализация без изменений осталась.

`src/main/java/dev/autonomousvillages/VillageRuntime.java:148` (+ `ForestWork.java:28`, `MineJob.java:74`, `VillagePlanner.java:552`, `PlayerGoods.java:100`, `Construction.java:134`, `Breeding.java:67`, `Village.java:237`, `AutonomousVillagesMod.java:68`, `Projects` ×10) · perf · lifecycle, gathering, life-pres, docs · **[verified]** (25 call sites in 11 files by `git grep`; docs map says 31)

`save()` → `writeSave(false)`: `rememberResidents`, `compactMetadata` (incl. `TownBuilding.valid` over every plan cell), `validate`, pretty GSON of every village including every BlockState-as-JSON plan cell (~1 MB), then `writer.immediate` under a lock. Triggers: every felled tree, every shaft segment, mine entrance choice, every chest/furnace placed, every death (an explosion → N saves in one tick), every gift (128 clicks to honour → 128 writes), every birth, role change, project laid/completed, released resident loading. `writeSave(true)` also re-serialises every 1200 ticks even when nothing changed. Contradicts PLAN v4 "запись не чаще раза в тик, тяжёлая работа вне потока сервера"; external review #1 still open.

**Предложенное исправление:** `save()` sets a dirty flag; at the end of `VillageRuntime.tick`, if dirty and ≥100 ticks since the last snapshot, serialise once (compact Gson) and `writer.periodic(json)`; keep `saveNow()` for claim/release/retire/close; skip periodic serialisation when not dirty.

### M2. Персистентность «всё или ничего»: одна невалидная деревня блокирует сохранение всех, одна плохая запись отключает мод при загрузке

**Статус: открыто**

`src/main/java/dev/autonomousvillages/VillageRuntime.java:154` (load: 167-179) · robustness · lifecycle, docs, tooling · **[verified]**

`writeSave` validates every village inside one try; one `validate()` failure aborts the snapshot for all, every time until restart (then the stale file loads). `load()` throws on any invalid village, overlap or duplicate resident and sets `loadFailed`, disabling all villages, commands and saves. PLAN 1.7 promises "без «всё или ничего»"; review #2 open. No current runtime path was found to break `validate()` (caps are enforced in Construction/Projects), but there is no margin.

**Предложенное исправление:** validate each village in its own try and reuse its last good `JsonElement` on failure; parse `data.villages` element by element and keep invalid/conflicting entries verbatim in `Save.quarantined`; extract a pure `SaveCodec` and unit-test it (corrupt file, duplicate resident, old version, one invalid village).

### M3. `Village.tick` не изолирует фазы: одна бросающая фича пропускает цикл работников или ночную безопасность дверей

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Village.java:111` (phases at 112-123, periodic 137-160) · robustness · lifecycle, docs · **[verified]**

Only the worker loop has per-item try/catch. A deterministic exception in `ticket()`, `index.tick`, `sync`, `town.tick` (Projects, 860 lines: malformed plan, template that fails to decode) or `breeding.tick` aborts every worker on every tick; one in `rescueFromPowderSnow`, `presentation.tick`, `ambience.tick`, `LandmarkGoods` etc. skips everything after it (`clearDoorways`, `closeDoors`, the 200-tick cleanup). `VillageRuntime` catches it but the rest of the tick is lost each time, with only "ошибка планирования" in status.

**Предложенное исправление:** `guard(String phase, Runnable r)` with a per-phase rate-limited log and `problem='ошибка: '+phase`; wrap ticket/index/survey/sync/town/breeding and each periodic feature.

### M4. POI-запрос кроватей покрывает квадрат в ~23 раза больше территории и выполняется каждый тик, пока кроватей нет

**Статус: открыто**

`src/main/java/dev/autonomousvillages/VillageIndex.java:64` · perf · lifecycle (safety notes it) · **[verified]** (`getInRange(HOME, center, state.radius+384)`; `time%200==0||beds.isEmpty()`)

3D distance padding of +384 for height gives a ~65×65-chunk square for radius ~100, mostly outside the ticketed area where `PoiManager` may read POI sections synchronously. Runs every tick while `beds.isEmpty()` (raid/fire, POI not yet loaded after start).

**Предложенное исправление:** `getInSquare(h->h.is(HOME), center, max(halfWidth,halfDepth)+1, ANY)` (X/Z only) with `filter(state::contains)`; rate-limit the empty case (`time%100==0||beds.isEmpty()&&time%20==0`).

### M5. Периодический survey: BFS по POI с дублями во фронтире, цепочки до 1800 блоков, финальный запрос радиусом до 2048

**Статус: открыто**

`src/main/java/dev/autonomousvillages/VillageDiscovery.java:134` · perf · lifecycle

Every 2400 ticks per village even when nothing changed; each popped POI runs `getInRange(…,56)` (81 chunks, all sections); `frontier.add(q)` for every not-yet-visited neighbour (O(N²) in dense clusters, only pops bounded at 2048); may follow POIs 1800 blocks into unloaded land; final `getInRange` radius `min(2048, extent+64)`. `Village:112` re-surveys every tick while `bounds==null` if survey throws, and only `IllegalArgumentException` is caught (a missing villages tag throws `IllegalStateException`).

**Предложенное исправление:** `queued` LongSet dedupe; skip POIs in unloaded chunks or beyond bounds+margin; `getInSquare` over the bounds for the final query; rate-limit the `bounds==null` retry to 1200 ticks; catch `RuntimeException` around the periodic survey.

### M6. `sync()` выполняется каждый тик у деревень без загруженных взрослых

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Village.java:121` · perf · lifecycle · **[verified]**

`if(time%100==0||workers.isEmpty())sync();` — a full-height `getEntitiesOfClass` over bounds+64, brain-memory checks, entity lookups and `syncRoles` every tick, forever, for a village with only children, unloaded residents, or one wiped out at night.

**Предложенное исправление:** `time%100==0||workers.isEmpty()&&time%20==0`, backing off further once `residents` is empty.

### M7. Жители, исчезнувшие без события смерти, никогда не вычищаются: держат 25 чанков и завышают спрос на жильё

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Village.java:219` · bug · lifecycle

`sync()` removes a resident only when its entity is loaded and dead or its bot state is DEAD. A villager discarded without dying (lightning → witch, another mod's discard, dimension move) stays in `residents` forever: its `residentPositions` chunk is leased (radius-2 ticket ≈ 25 chunks), it counts toward `golemsWanted` and `beds<residents.size()+2` (Projects:141), and its Worker shows "не загружен".

**Предложенное исправление:** transient lastSeen per resident; when its last chunk is entity-ticking but entity and bot are absent on 3 consecutive passes, `residentLost` + remove from residents/workers/reservations + chronicle "<name> покинул деревню".

### M8. `/avillage storage` отказывает в новых сундуках, когда деревня сама владеет 16

**Статус: открыто** — Команда по-прежнему отвечает «Достигнут лимит 16 сундуков».

`src/main/java/dev/autonomousvillages/VillageRuntime.java:122` · ux/bug · lifecycle, docs · **[verified]**

`v.state.storage.size() >= 16` counts captured (Projects:95, up to 128) and built (Construction:124, up to 128) chests; `validate` allows 128. A grown village rejects a player's chest with "Достигнут лимит 16 сундуков" and cannot undo `/avillage private` as README promises.

**Предложенное исправление:** one `VillageState.MAX_STORAGE=128` used by validate, Construction, Projects and the command; or track player-designated chests separately.

### M9. Повреждённый конфиг молча перезаписывается значениями по умолчанию

**Статус: открыто**

`src/main/java/dev/autonomousvillages/ModConfig.java:78` · robustness · lifecycle, docs

On a parse error `load()` logs WARN, falls back to defaults and rewrites the file: every switch the owner turned off comes back on and their file is gone.

**Предложенное исправление:** rewrite only when parsing succeeded or the file was missing; back up to `.broken` on failure; add a malformed-file `ModConfigTest` case.

### M10. Файл сохранения не fsync-ится, нет резервной копии и отката

**Статус: открыто**

`src/main/java/dev/autonomousvillages/FileSnapshotWriter.java:40` · robustness · lifecycle · **[verified]** (`Files.writeString(temp)` + `ATOMIC_MOVE`, no `force`, no `.bak`)

After a power loss a filesystem without rename-replace heuristics can leave a zero-length or garbage file; `load()` then sets `loadFailed` with no fallback and every village is lost unless repaired by hand.

**Предложенное исправление:** write through a `FileChannel` with `force(true)` before the move; rotate `.bak`; fall back to `.bak` in `load()` with a loud log.

### M11. Лимиты метаданных (печи 16, сундуки 128) блокируют саму установку блока — шаг чертежа с печью/сундуком никогда не ставится

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Construction.java:102` (+ `VillageState.java:181`) · bug · lifecycle, docs · **[verified]**; the docs-product variant ("place adds every chest/furnace with no limit, so validate() fails forever") is **[doubtful]** — lines 102-103 refuse the placement, so `validate()` cannot trip; the real consequence is the stall below.

`Construction.place` returns false for `CHEST` once `storage.size()>=128` and for `FURNACE` once `furnaces.size()>=16`. `Blueprints.structural` treats FURNACE and CHEST as required template blocks, so a village captured with 16 furnaces that then builds a template house/landmark with a non-optional furnace stalls one block short forever (also feeds H8). Whether any template's furnace step is optional was not checked.

**Предложенное исправление:** separate metadata from placement — always place, skip recording when the set is full (or make such steps optional at the cap); optionally trim `storage/furnaces` in `compactMetadata` as a belt-and-braces guard.

### M12. `Approach` считает каждую проваленную стоячую ячейку дважды — реально пробуются 2 ячейки вместо 4

**Статус: открыто** — Счётчик `attempts` в `Approach` не изменён.

`src/main/java/dev/autonomousvillages/Job.java:113` · bug · economy, gathering, navigation · **[verified]** (`if(!success&&++attempts>=4)return FAILED;` plus `attempts++` on a new stand)

After the first failed stand `attempts==3`, after the second `4` → FAILED. Every Fetch, Deposit, Build, Craft and Mine approach gives up after two stands (each up to 300 stalled / 2000 computing ticks). False "не подойти"/unreachable failures feed the stuck counter (EscapeJob, teleports), target rejection and chest votes. Nobody reading the code would infer the real limit; no test covers it.

**Предложенное исправление:** separate `routesStarted` (cap 6) and `routesFailed` (cap N) with named constants; move into a small pure state machine with a unit test.

### M13. Аренда цели истекает через 1200 тиков, не продлевается и покрывает только первую цель работы

**Статус: частично** (G3) — `ShelterJob` продлевает аренду ячейки каждый тик и уступает её занявшему; остальные длинные и многоцелевые работы аренду не продлевают.

`src/main/java/dev/autonomousvillages/Reservations.java:12` (map cited `:179`; the file has 20 lines) + `Worker.java:152`, `ShelterJob.java:39` · bug · economy, safety · **[verified]**

`acquire()` always grants `now+1200`; `Worker.start` acquires once; nothing renews. Chop/Compost run up to 3600, shaft 6000, Shelter 14000 — after a minute `v.ready(p)` treats the tree/composter/cell as free and a second worker is sent (two residents share one shelter cell; the first worker's release no longer removes the new lease). Multi-target jobs (MineJob, PathJob, HazardJob, LightJob, LanternJob, FlowerJob) reserve only the first target; quarry has no `activeJobs` guard.

**Предложенное исправление:** in `Worker.tick` re-acquire `job.target()` every ~200 ticks and whenever it changes (or lease length = `timeLimit()`); extend `VillageRulesTest`.

### M14. `storeJob` крафтит второй верстак/сундук, пока работник уже несёт один

**Статус: открыто**

`src/main/java/dev/autonomousvillages/VillagePlanner.java:137` · bug · economy · **[verified]** (`if(w.has(TABLE)&&w.ready("place")){spot=utilitySpot();if(spot!=null)return PlaceJob}` then falls through to `CraftJob`)

If `utilitySpot()` is null (crowded hub) or `ready("place")` is false (200-tick cooldown after a failed PlaceJob), control falls to `new CraftJob(w, TABLE, 1)`; the chest branch (142) does the same. Each decision turns 4-8 planks into another table/chest that is later deposited, draining wood the houses need.

**Предложенное исправление:** when `w.has(item)`: PlaceJob if possible, otherwise return null for that branch (or short "place" cooldown), never craft a second; same for the furnace branch.

### M15. Склад «не полон» из-за сундуков, до которых никто не дойдёт — депозиты останавливаются, добыча копится в рюкзаках

**Статус: открыто**

`src/main/java/dev/autonomousvillages/VillagePlanner.java:134` · bug · economy · **[verified]** (`all=v.chests()` incl. rejected for `allFree`/`full`; deposits require `s.freeSlots()>0` from reachable chests)

If the reachable store is full but an unreachable chest has ≥9 free slots, `full==false` and no new chest is crafted; meanwhile step 1 and the final deposit need `s.freeSlots()>0`, so nobody deposits, `pickup` stops, drops despawn.

**Предложенное исправление:** base `full` on reachable free slots (keep the 48-chest cap), or count rejected chests only on their first strike.

### M16. Скрафченные стройматериалы (кровати, двери, плиты) остаются в рюкзаке крафтера и стопорят строителей

**Статус: открыто**

`src/main/java/dev/autonomousvillages/VillagePlanner.java:78` · bug · economy

The hauling set is only chop/quarry/shaft/shear; a bag is emptied at ≥16 surplus, ≤4 free slots or when idle. `BuildJob.affordable` counts only the store and the builder's own bag, while `owned()` counts every bag — so nobody crafts a replacement and builders skip the step. A bed crafted by a farmer can ride along through many farm jobs (feeds the WORKLOG bed bottleneck).

**Предложенное исправление:** after a CraftJob whose output matches a key in `s.missing()` (and is not a store block), return a DepositJob at once, or give CraftJob a final Deposit phase.

### M17. `mine()` делает снимок инвентаря на каждого кандидата камня/руды до дешёвых фильтров

**Статус: открыто** — `usable` в `mine()` получил проверку соседних жидкостей (G1), но `hasPickFor` по-прежнему вызывается на каждого кандидата.

`src/main/java/dev/autonomousvillages/VillagePlanner.java:466` · perf · economy · **[verified]** (`usable` calls `VillageCrafting.hasPickFor(w.bot(),state)` per candidate)

`hasPickFor` → `Supplies.find` → new `InventorySnapshot` (72 ItemStack copies) for every candidate in `index.stone`/`ore` before the `distSqr<80²` check; the stream filters the whole uncapped set and sorts afterwards. Mountain villages: thousands of candidates, ~10⁵ copies, several ms per decision against a 0.4-2 ms shared budget.

**Предложенное исправление:** find the best usable pickaxe once and test `isCorrectToolForDrops` per candidate; order predicates cheapest first; nearest-k via bounded heap.

### M18. Снимки инвентаря на каждый вызов: `owned()` O(workers²), `Fetch/Deposit` копируют рюкзак каждый тик, кэш `count` промахивается на захватывающих лямбдах

**Статус: открыто**

`src/main/java/dev/autonomousvillages/VillagePlanner.java:256` (+ `Worker.java:218`, `Job.java:187,210`) · perf · economy, navigation · **[verified]** (`bag()` = `snapshot().slots()`; `Fetch.outstanding` per tick; `Deposit.hasSurplus`+`keptSlots`)

`owned()` calls `o.count` for every active worker (72 copies each); `craftForProject` makes ~20 `owned()` calls per decision; `BuildJob.affordable` one `w.count` per step (≤48); `Fetch.tick` snapshots every walking tick, `Deposit.tick` ~144 copies per tick. `VillageStock.count` keys on predicate identity, so the wool loop and BuildJob material predicates rescan every chest.

**Предложенное исправление:** cache `Worker.bag()` per tick (invalidate on transactions/pickup/craft); per-tick `Map<Item,Integer>` histograms of store and bags; static predicate constants or per-key cached material predicates; re-check needs/surplus only on chest change.

### M19. Выгруженный/отсоединённый работник держит работу вечно и блокирует гейты `activeJobs`/`busy`

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Worker.java:98` · robustness · economy · **[verified]** (`status="не загружен";return;` leaves `job`)

`activeJobs("light"/"hazard"/"shaft"/"smelt"/…)==0` and `busy()/crafting()` keep counting that job: an unloaded worker mid-LightJob blocks all lighting; a CraftJob(TABLE) blocks everyone's workbench. Its reservation stays until expiry.

**Предложенное исправление:** once the bot is missing/inactive, `job.stop()` in try/catch, `job=null`, release reservations; add `Worker.loaded()` to `activeJobs`/`busy`.

### M20. Провал первого сегмента шахты заново выбирает тот же вход, спамит летопись и форсит синхронное сохранение

**Статус: исправлено** (G1) — Отвергнутый и тот же вход при поиске пропускаются, синхронный `save()` убран.

`src/main/java/dev/autonomousvillages/MineJob.java:71` · bug · gathering · **[verified]** (`segments<=mineLength&&segments==0` ≡ `segments==0`; `mineEntrance()` fixed direction order + `save()`)

On a 0-segment failure (unsafe_entrance, entrance_unreachable, no_suitable_tool…) MineJob clears `mineEntrance/mineLength`; `mineEntrance()` walks the 4 directions in fixed order and normally picks the same column, writing "Выбрано место для шахты…" and calling `runtime.save()` each time; with H6 this repeats indefinitely.

**Предложенное исправление:** bounded set of failed entrance columns (VillageState or long-expiry `rejected`) skipped in `mineEntrance()`; clear the entrance only after a second failure; drop the `save()`.

### M21. Провалы Harvest в карьере молчаливы: цели не отклоняются, работа не фейлится, тот же карьер планируется снова

**Статус: исправлено** (G1) — Не сломанный блок отвергается на 2400 тиков, неудачный подход — провал; работа без единого успеха завершается `fail()` и получает кулдаун.

`src/main/java/dev/autonomousvillages/MineJob.java:36` · bug · gathering · **[verified]** (Harvest FAILED → `targets.poll()` only; job ends `return false` with empty failure)

Obstructed (Approach checks COLLIDER sight, miner aims OUTLINE — grass/torches differ), cannot_break, tool_worn_out, not_allowed, block_changed: the target is neither rejected nor reported; if every target fails the job ends without failure → no rejection, no cooldown; the planner's `usable` filter re-accepts the same nearest ores.

**Предложенное исправление:** on Harvest FAILED `v.rejected.put(target, now+1200)`; when the job ends with `broken==0` and ≥1 failed harvest, `fail("не добыть: "+error)`.

### M22. Карьер ломает блоки без проверки соседних жидкостей — шахты и ямы затапливаются или впускают лаву

**Статус: исправлено** (G1) — Блоки рядом с водой/лавой пропускаются и отвергаются на день — и в планировщике, и в работе.

`src/main/java/dev/autonomousvillages/MineJob.java:48` · robustness · gathering

The excavator refuses faces with adjacent fluid, but quarry targets (incl. `shaftOre` beside the staircase) go through `MinerImpl.breakBlock` with no fluid-neighbour or falling-ceiling check; neither `mineable()` nor the planner's `usable` checks.

**Предложенное исправление:** reject targets with any non-empty neighbour `FluidState` or a FallingBlock above (sand excepted) in `mineable()` and `usable`; long rejection.

### M23. Состояние валки несогласовано: `fellingTrees` никогда не заполняется, `treeWork` пишется до среза, `reforestation` только пишется

**Статус: исправлено** (G4) — `treeWork` и `fellingTrees` пишутся вместе сразу после среза и чистятся вместе (устаревшая валка — раз в 200 тиков); `reforestation` читается: валка подсаживает до двух старых пней.

`src/main/java/dev/autonomousvillages/ChopJob.java:55` (+ `ForestWork.java:21`) · bug · gathering

Nothing adds to `fellingTrees` (read by planner/index, removed by `complete`). `danger()`/`defend()` call `job.stop()` even for atomic jobs; if FALL is interrupted after the stump is cut, `treeCandidate` needs `naturalLog(base)` (gone) or `fellingTrees.contains(base)` → canopy floats forever. `ForestWork.remaining()` persists the full BFS log list (≤256 longs) at the first APPROACH tick; every unreachable tree leaves a stale entry. `reforestation` is filled but never read.

**Предложенное исправление:** add `base` to `fellingTrees` right after the cut succeeds and in the canopy-case `startFall`; keep the BFS in a ChopJob field until the cut, remove on failure; consume `reforestation` (ReplantJob) or stop persisting it.

### M24. Детекция деревьев и валка могут разобрать постройки игрока из брёвен и игнорируют зоны политики для листвы

**Статус: исправлено** (G4) — Бревно, касающееся досок, ступеней, дверей и т. п., не считается деревом (`VillageIndex.treeLog`); листва и падающие брёвна уважают зоны и защищённые планы, persistent-листва не рубится.

`src/main/java/dev/autonomousvillages/ForestWork.java:17` (+ `VillageIndex.naturalLog`, `ChopJob.java:99`) · bug · gathering

`naturalLog` accepts any `BlockTags.LEAVES` within 2 blocks (persistent player leaves included) above a log on dirt/grass; the BFS follows every 26-connected `BlockTags.LOGS` block (stripped logs, `*_wood`) up to 256 within 10 horizontal / 40 up. A log cabin with leaf decoration in the 48-block outskirts is destroyed via `level.destroyBlock`, which bypasses `VillageRuntime.mayBreak` (only this village's protects/policy apply). The leaves BFS checks neither `policy.allows("wood",q)` nor `v.canWork(q)`.

**Предложенное исправление:** require a non-persistent leaf; skip stripped/wood blocks; stop at logs touching persistent leaves or placed blocks; check policy and `canWork` for leaves; consider all villages' protects.

### M25. Печь получает слишком мало топлива для партии, остаток сырья застревает

**Статус: исправлено** (G4) — Топливо одного вида на всю партию (уголь — 8 предметов, доска/бревно — 1,5), загружается только покрытое топливом сырьё, остаток возвращается; без топлива — сразу провал с кулдауном.

`src/main/java/dev/autonomousvillages/SmeltJob.java:27` · bug · gathering · **[verified]** (fetch up to 16 inputs but `coal?2:4` fuel)

4 planks smelt 6 items → up to 10 raw iron/sand stay in the input slot; the follow-up "забирает готовое" job fetches nothing, `serviceFurnace` tops up fuel only from what the worker carries, and the snapshot counts raw iron in the store only, so once the store is empty the batch sits forever. Hits the 2-ingot shears path.

**Предложенное исправление:** fetch `ceil(amount/8)` coal or `ceil(amount*2/3)` planks (≤16); in the collect-only path fetch fuel when input is non-empty and fuel empty; count furnace input as pending in the snapshot.

### M26. `ShearJob` гоняется за одной недосягаемой овцой до таймаута, а таймаут выглядит как застревание

**Статус: исправлено** (G4) — Овца, дважды не достигнутая, пропускается; повторные перенацеливания ограничены и считаются провалом.

`src/main/java/dev/autonomousvillages/ShearJob.java:41` · robustness · gathering

On Walk FAILED `sheep=null`, then `nearestSheep()` picks the same sheep again until the 2400-tick timeout; "не уложился во время" matches the stuck heuristic → after 3 timeouts a healthy farmer gets EscapeJob and a teleport. `stop()` never cancels its Fetch.

**Предложенное исправление:** per-job failed-sheep set, fail after 2 misses; short village-level UUID blacklist; `fetch.cancel()` in `stop()`.

### M27. Неосвещённая 64-ступенчатая шахта внутри территории — спаунер монстров

**Статус: исправлено** (G1) — Шахтёр берёт факелы на прогон и ставит настенный факел каждые 6 ступеней (без факелов на складе копает в темноте).

`src/main/java/dev/autonomousvillages/MineJob.java:61` · robustness · gathering

1-wide, 3-high staircase descending toward the centre; nothing lights it (`darkSpots` samples heightmap surface only). Steps below ~8 have sky light ≤7 and no block light → zombies/skeletons spawn by day and walk up. PLAN target: ≤1 death in 5 days.

**Предложенное исправление:** wall torch every 6 steps after a successful excavation (carry torches via `keeps()`+Fetch), or add step cells to Lighting; optionally a door/gate at the entrance.

### M28. `farm()` читает 162 состояния жидкости на каждую площадку; `farmSites` и `shaftOre` только растут

**Статус: частично** (G4) — Ответ `FarmJob.watered` кэшируется на 1200 тиков на площадку; `farmSites` и `shaftOre` по-прежнему только растут, `closest()` проверяет все площадки.

`src/main/java/dev/autonomousvillages/VillagePlanner.java:570` (+ `VillageIndex.java:59`, `MineJob.java:82`) · perf · gathering, lifecycle · **[verified]** (`farmSites.add(plant)` never paired with removal; `closest()` over every site)

`closest()` runs its predicate (incl. `FarmJob.watered`, a 9×2×9 fluid scan) on every site within 80 blocks before `min()`; in a river village every grass cell near water is a site → up to ~300k fluid reads per farmer decision. `shaftOre` is never pruned either. External review #4 open.

**Предложенное исправление:** sort by distance first and test `watered()` lazily; cache per site with a timestamp; `update(farmSites, plant, condition)` so failing cells are removed; prune inside `closest()`; cap both sets (~1024).

### M29. Проёмы дверей и крытые крыльца считаются «под крышей» — укрывающиеся жители стоят в двери или на крыльце

**Статус: исправлено** (G3) — `ShelterJob.indoor`: стены и крыша, без двери в клетке и рядом; поиск предпочитает клетки вдали от стен и дверей.

`src/main/java/dev/autonomousvillages/ShelterJob.java:32` · bug · safety · **[verified]** (predicate: `protects(p.below()) && !canSeeSky(p) && !canSeeSky(p.above()) && not bed && safeFeet(p)`; `find()` picks the nearest candidate; `tick()` holds at `away<=1.2` without `byDoor`)

A door cell under a lintel passes (door collision box 3 px at the cell edge misses the 0.6-wide body box); a porch under an awning passes. `find()` picks the candidate nearest the resident arriving from outside, so the door cell wins and `tick()` holds there; while a living entity stands in the door cell, `closeDoors`, `clearDoorways` and `secureDoors` cannot shut the door. Same predicate drives `Worker.covered` and the `danger()` roof rule. Matches doorstep/porch deaths (8675309) and "zombie inside a lit room" (1337).

**Предложенное исправление:** reject cells where `p`/`p.above()` is a DoorBlock or within 2 blocks of `v.town.doors()`; better, cache per home an interior cell set by flood fill from beds that crosses neither doors nor sky-visible cells, used by `find()`, `covered` and `danger()`; prefer cells farthest from a door.

### M30. Тревога, переподнимаемая спрятавшимися жителями, замораживает работу всех безоружных по всей деревне

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Worker.java:333` (+ `Village.java:362`, `Village.java:388`) · bug · safety · **[verified]** (`raiseAlarm` called in the covered branch too; `alarmUntil=now+400` each call; `hiding()` = alarm && !armed for everyone)

Any resident seeing a threat within 12 blocks (including through a window from indoors) re-raises every 40 ticks; while `alarm()` is true `hiding()` is true for every unarmed resident wherever the monster is and line 118 ends their job. A husk (does not burn) or a zombie under a porch roof keeps the village idle until an armed resident or golem kills it. `guardChildren` raises for any Monster except creepers (spiders by day, endermen, zombified piglins).

**Предложенное исправление:** count only outdoor witnesses or a monster within ~16 blocks of a resident; per-monster alarm budget; store the alarm position and send home only residents within ~48 blocks; reuse `Worker.threat()` in `guardChildren`.

### M31. `HazardJob` пакует пороховой снег в полный блок поверх сущностей и игнорирует зоны политики

**Статус: исправлено** (G3) — Снег не пакуется поверх живой сущности и в закрытых зонах; доступ к чанку проверяется; каждый отказ запоминается.

`src/main/java/dev/autonomousvillages/HazardJob.java:30` (+ `VillagePlanner.java:88`) · robustness · safety

Direct `level.setBlock(SNOW_BLOCK)` skips `Construction.place`'s entity check; walkway-adjacent drifts are packed first — exactly where children freeze. A child/sheep/player in the cell ends up inside a snow block. Neither job nor planner checks `policy.allows("build")`.

**Предложенное исправление:** skip and briefly reject the cell when any alive LivingEntity box touches it (expanded 1 up); add the policy check in job and planner.

### M32. Сеточные пробы света попадают на плоские крыши — факелы и фонарные столбы ставятся на крышах

**Статус: исправлено** (G2) — Пробы сетки — только открытая естественная земля вне планов и тропинок; столбы не на крышах.

`src/main/java/dev/autonomousvillages/Lighting.java:96` · bug · safety

`darkSpots` samples `MOTION_BLOCKING_NO_LEAVES` height (air above a plank roof); `needsLight` rejects `protects(p)` but not `protects(p.below())`; LightJob's Approach uses `sight=false` and `place` has no LoS check, so the torch goes through the ceiling; a sky-visible roof cell not next to a door gets a fence-and-torch lamp post. Roof lights count toward the 160 cap.

**Предложенное исправление:** reject outdoor samples where `protects(p.below())`; keep samples within |dy|≤6 of the anchor; never build a lamp post on a building block.

### M33. Всё освещение останавливается на 160 записанных огнях, включая спальни и пороги новых домов

**Статус: исправлено** (G2) — Пороги и комнаты без предела; уличная сетка ограничена по площади и жителям (160…960).

`src/main/java/dev/autonomousvillages/VillagePlanner.java:48` · bug · safety · **[verified]** (`if(v.state.lights.size()<160&&…)` gates rooms and doorsteps too)

The set includes room torches, lamps and roof torches; a growing village reaches the cap and never lights new bedrooms/doorsteps — the cases `darkRooms`/`darkDoorsteps` were written for.

**Предложенное исправление:** always allow `darkRooms`/`darkDoorsteps`; cap only the outdoor grid by footprint area (≤~1000 to stay under the 1024 save cap).

### M34. `darkRooms` пересканирует все якоря каждые 200 тиков, когда деревня уже освещена

**Статус: исправлено** (G2) — Комнаты сканируются по нескольку якорей по кругу, освещённый якорь пропускается две минуты.

`src/main/java/dev/autonomousvillages/Lighting.java:38` · perf · safety · **[verified]** (anchors = beds + all job-site POIs + every storage chest; 7×3×7 per anchor; early exit only after `limit`=2 found)

Each cell costs ~10-15 lookups; a well-lit village scans every anchor every 10 s within one worker's planning call and one tick; overlapping anchors rescan the same cells.

**Предложенное исправление:** dedupe anchors on a 4-block grid; drop chests in the open; rotating cursor over a few anchors per call; remember lit rooms for a few minutes.

### M35. Точки патруля — кровати внутри домов: стражи открывают двери ночью и повторяют проваленные точки каждый круг

**Статус: исправлено** (G3) — Только уличные точки (вышка, колокол, пороги, фонари, ворота), точка снимается на ночь после двух провалов маршрута.

`src/main/java/dev/autonomousvillages/PatrolJob.java:18` · ux · safety

`v.index.beds` are bed heads indoors; Walk radius 4 / tolerance 2 takes guards through doors the village just shut, letting zombies follow; a FAILED stop (43) just moves on and costs up to 3 route attempts again every lap.

**Предложенное исправление:** stops = outside step of each home door (sky-visible side, as `NameplateJob.next` does); drop a stop for the night after one failure.

### M36. «Бездомность» (`beds < residents+2`) идёт первой — растущая деревня никогда не доходит до амбара и вышки

**Статус: исправлено** (C1) — Одна общественная постройка закладывается рядом с домом (`Projects.decide`).

`src/main/java/dev/autonomousvillages/Projects.java:141` · bug · construction · **[verified]** (`homeless` branch first; `free = houses.size()==unfinished.size()&&(houses.isEmpty()||bedBound)`)

Each new bed is taken by a newborn, so `homeless` stays true and line 148 lays another house every cycle; `free` is false while any house is unfinished, so non-house projects start only in the narrow `bedBound` case. WORKLOG: no soak village reached the tower in 3 days. Breaks principle 1.

**Предложенное исправление:** allow one non-house project alongside houses (`free` = no unfinished non-house project), or alternate house and queue item after each completed house; or measure homelessness by free beds.

### M37. Дверь амбара зафиксирована на северной стене, проём не проверяется — сундуки склада могут оказаться недосягаемы

**Статус: исправлено** (C1) — Амбар ставится как дом: дверью к колоколу, проём проверяется. Вышка, колодец и загон по-прежнему с фиксированной ориентацией.

`src/main/java/dev/autonomousvillages/Projects.java:454` · bug · construction

`storehouse(base)` always puts the door at (3,1,0); the barn never passes `doorwayOpen()` (only template/landmark plans do); `siteAt` checks heights only inside the footprint. A cliff/fence 1-2 blocks north of the door is accepted → the barn's three store chests are unreachable → "unreachable" fetch failures. Tower, well and pen also have fixed orientations.

**Предложенное исправление:** express storehouse/tower/well/pen as `Landmarks.Design` data placed through `place(size, NORTH, fromDesign, hub)`; at minimum check `walkable()` for the two cells in front of the door.

### M38. Вырубки выше досягаемости строителя считаются в завершение (амбар и вышка режут до y=6)

**Статус: исправлено** (C1) — Вырубки выше досягаемости от пола перед дверью (`Projects.floor`, `CUT_TOP`) необязательны и не держат завершение.

`src/main/java/dev/autonomousvillages/Projects.java:434` (+ 816, `fromTemplate` 716) · bug · construction

From the floor a builder reaches at most layer 5 (3.88 > 3.55 at y=6). Leaves/snow at y=6 over the barn are removable, so `siteAt` accepts them, `remaining()` counts them, nobody can cut them; the barn never completes, `free` stays false, every BuildJob starts with failing approaches.

**Предложенное исправление:** add cuts only up to `Landmarks.REACH_TOP` (or needed headroom), or exclude cuts above reach from `remaining()`/`pendingCuts()`; also covered by H8's strike memory.

### M39. `siteAt` принимает улицы деревни и плоские крыши незахваченных структур

**Статус: исправлено** (C1) — Площадка требует естественной земли; тропинки, гладкий песчаник улиц, крыши и чужие планы отвергаются; отступ от линии дверей больше.

`src/main/java/dev/autonomousvillages/Projects.java:636` · bug · construction

Layer 0 may hold any block: DIRT_PATH is removable and replaced by the floor, so houses are laid across streets. Non-home pieces (smithy, farms, well, lamp posts) are never captured, so neither `protects()` nor the 10-block origin check sees them; a flat sandstone roof passes the height test and the dy 0..6 scan only looks above it.

**Предложенное исправление:** require most layer-0 cells to be natural ground and reject footprints containing DIRT_PATH; or record non-home piece bounding boxes as no-build zones at capture.

### M40. Частокол: origin может оказаться вне границ (стена выбрасывается при загрузке и перезакладывается в цикле), столбы на крышах, кольцо никогда не расширяется

**Статус: частично** (C1) — (a) кольцо зажато в границы деревни, старая запись с углом снаружи переносится внутрь; (b) столбы пропускают крыши и брёвна; (c) кольцо по-прежнему не расширяется.

`src/main/java/dev/autonomousvillages/Projects.java:253` · bug · construction, docs · **[verified]** (origin `new BlockPos(minX,y,minZ)` with `minX -= Palisade.MARGIN`; `TownBuilding.valid` requires `village.contains(origin)`; `compactMetadata` drops invalid buildings; `save()` right after laying)

(a) `siteAt` keeps buildings only 2-3 columns inside bounds, so the −4 margin corner can lie outside; the wall is dropped by `compactMetadata` on the very save that follows, `wall()==null` lays a new one next cycle → "Заложен частокол" spam and synchronous saves. (b) Columns use the heightmap top and skip only protected cells, so fences land on roofs of uncaptured structures. (c) Laid once, never extended: later houses outside get no gates, footpaths run into fences.

**Предложенное исправление:** clamp the origin into bounds or use the hub; make `valid()` require only that plan blocks lie inside; skip non-natural/roof columns; re-plan missing segments as extra "wall" projects with gates at footpath crossings; unit test for a wall whose corner is outside.

### M41. Партии частокола разбросаны по всему кольцу: долгие переходы, таймауты, очередь заблокирована днями

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Projects.java:580` · perf · construction

`nextSteps` sorts by optional/Y/stepOrder only; a 300+ column ring gives 16-step batches scattered round the perimeter, BuildJob hits its 3600-tick limit; the unfinished wall keeps `free=false` for days at ~400-1000 fences.

**Предложенное исправление:** pass the worker position and sort ties by distance or ring index; split the ring into ~40-column segment projects.

### M42. `capture` перезапускается после каждого рестарта и усыновляет сундуки любого здания с кроватью, включая дома игрока

**Статус: исправлено** (C1) — После рестарта захват только при новом куске или доме; сундуки усыновляются только из сгенерированного куска без меток игрока (`Projects.adoptable`).

`src/main/java/dev/autonomousvillages/Projects.java:80` · robustness · construction

`captured` is transient, so capture runs after every start (111) and on expansion; `captureHouse` flood-fills from every HOME POI not within 2 blocks of a plan bed. A player's house built inside the village after the claim becomes a protected `native_house` and `adopt()` adds its chests/furnaces to the store — residents take the player's items.

**Предложенное исправление:** persist captured home positions or a "capture done" flag; after the claim record new homes as protected without `adopt()`, or adopt only inside generated structure pieces.

### M43. Листинг шаблонов пересканирует ресурсы при каждом вызове; поиск мастерской повторяет дорогой скан каждые 100 тиков

**Статус: исправлено** (G5, C1) — Список шаблонов кэшируется до перезагрузки датапаков (G5); неудачный поиск мастерской остывает 2400 тиков (C1).

`src/main/java/dev/autonomousvillages/Blueprints.java:50` (+ `Projects.java:369`) · perf · construction · **[verified]** (`civic()`/`houses()` call `listTemplates()` each time)

`listTemplates()` lists every resource pack and walks the generated directory; `templateHouse`/`civicBuilding` call it every planning cycle. `civicBuilding` tries every civic design × 16 `place()` attempts, each a `siteAt` of up to ~2k block reads, and builds a full JSON-encoded plan before `doorwayOpen` is checked; on failure Projects:197 returns and repeats every 100 ticks with no backoff.

**Предложенное исправление:** cache template id lists per style (clear on datapack reload); `civicRetry` backoff; run the cheap distance check before the block scan; check the doorway on template coordinates before `fromTemplate`.

### M44. Остаток `VillageStock.put()` игнорируется — предметы уничтожаются при полном складе

**Статус: частично** (G5, G2) — Таверна, мельница и пирс делают только то, что влезает, остаток кладут предметом у станции (G5); `LanternJob` снимает факел только при свободном месте (G2). `Worker.professionProduct` по-прежнему теряет то, что не влезло в рюкзак.

`src/main/java/dev/autonomousvillages/LandmarkGoods.java:129` (tavern), `:167` (mill); `Worker.java:311` (`professionProduct`); `LanternJob.java:44` · bug · construction, docs, economy, safety · **[verified]** (LandmarkGoods uses only `rest.getCount()`; LanternJob ignores the return)

Tavern consumes fuel and raw food then drops whatever cooked food did not fit; mill consumes 3n wheat and loses bread; `professionProduct` inserts the rest into the bag and silently drops what the bag refuses (day already marked paid); LanternJob loses the removed torch. Breaks material conservation exactly in the "store full" state M15 makes likely; README advertises "Всё — настоящими предметами".

**Предложенное исправление:** `VillageStock.putOrDrop(stack, pos)` spawning leftovers as ItemEntity; or check `freeSlots()`/capacity first and consume only what was inserted; `LandmarkGoodsTest` with a full store.

### M45. `clear()`/`removable()` — рукописные списки, не знающие растений 26.x

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Construction.java:145` (+ `Projects.java:568`) · bug · construction · **[verified]** (`clear` = air, short/tall grass, fern, large fern, snow)

Dead bush, short/tall dry grass, leaf litter, mushrooms, vines, sweet berry and firefly bushes are neither clear nor removable: in plan cells they make `blockedForever()` true (holes in walls/floors), in `siteAt` they reject sites — common around desert villages (seed 1337).

**Предложенное исправление:** `clear = isAir() || (canBeReplaced() && fluid empty)`; add `BlockTags.REPLACEABLE`/`REPLACEABLE_BY_TREES` and plant blocks to `removable()`; keep one list.

### M46. Ванильные шаблоны домов никогда не проверяются на досягаемость и опоры

**Статус: исправлено** (G5) — `Blueprints.audit` и `BlueprintsAuditTest` проверяют все 152 ванильных шаблона из jar игры; шаблоны, требующие лесов, берутся последними.

`src/main/java/dev/autonomousvillages/Blueprints.java:69` · test-gap · construction · **[unverified]** (the map itself says real templates were not checked)

Only `Landmarks` designs are tested (`LandmarksTest`). `houses()` filters by size and beds only; a template may have roof cells above layer 5 or torches/ladders hanging on decoration blocks placed only when the store holds the exact item → permanently unplaceable cell (H8).

**Предложенное исправление:** unit test reading `data/minecraft/structure/village/*/houses/*.nbt` from the game jar through `Blueprints.parse/buildable`, asserting reach and support; runtime filter for templates whose structural cells fail.

### M47. Именные бирки игрока на жителях перезаписываются в течение секунды и теряются

**Статус: исправлено** (E6)

`src/main/java/dev/autonomousvillages/VillagePresentation.java:40` · bug · life-pres · **[verified]** (`residentNames.computeIfAbsent` once; `setCustomName(personName+role+status)` every refresh)

`personName()` is documented "a name tag the player gave wins", but the original is captured only once; `donatable()` lets NAME_TAG through so vanilla applies the tag, then the next refresh replaces it. The tag is used up and never appears; on release `restoreName` restores the pre-claim name.

**Предложенное исправление:** remember the last component written per worker; if `getCustomName()` differs, treat it as a player rename and store it in `residentNames` (untouched=false) before overwriting.

### M48. `restoreName` может бросить из путей ENTITY_LOAD и SERVER_STOPPING

**Статус: исправлено** (E6)

`src/main/java/dev/autonomousvillages/VillagePresentation.java:95` · robustness · life-pres (also H1)

`CODEC.parse(...).getOrThrow()` with no try/catch (`personName` catches, this does not); called from `VillageRuntime.residentLoaded` (ENTITY_LOAD and the constructor) and `close()` (Village.stop, VillageRuntime.close). A malformed or version-incompatible stored name aborts entity loading or shutdown before other villages release tickets.

**Предложенное исправление:** try/catch(RuntimeException) → `setCustomName(null)` and log once; per-resident catch in `close()`.

### M49. Босс-бар держит отключившихся `ServerPlayer` навсегда

**Статус: исправлено** (E6)

`src/main/java/dev/autonomousvillages/VillagePresentation.java:31` · robustness · life-pres (plausible: a mod-owned `ServerBossEvent` is not cleaned by `CustomBossEvents.onPlayerDisconnect`)

Membership is updated only for players in `getPlayerList()`; a player who logs out near the village stays in `ServerBossEvent.players`; no disconnect handler exists. Each logout leaks a ServerPlayer graph.

**Предложенное исправление:** remove `hasDisconnected()` / other-level players from the bar each refresh, or register `ServerPlayConnectionEvents.DISCONNECT`.

### M50. Доска объявлений захватывает любую табличку у колокола, включая игрока и вывески зданий

**Статус: исправлено** (E6) — Позиция своей доски хранится в `VillageState.noticeBoard`; чужие таблички не трогаются.

`src/main/java/dev/autonomousvillages/Village.java:525` · bug · life-pres · **[verified]** (first `SignBlockEntity` within ±6/±2 not a nameplate)

`updateBoard` rewrites all four front lines; a player's sign, a landmark signboard or a hanging sign is overwritten whenever the material list changes.

**Предложенное исправление:** store the board position in `VillageState` when the village's own PlaceJob("табличка") places it and write only there; exclude hanging signs.

### M51. Страницы ведомости переполняются: ~4 из 10 жителей видны, жители после 40 отброшены; build/store/donor страницы >14 строк; строка просьбы стоит под «Не хватает:»

**Статус: исправлено** (E7) — `BookLayout`: перенос по ширине книги, 14 строк на страницу, все жители, просьба над «Не хватает:».

`src/main/java/dev/autonomousvillages/PlayerGoods.java:193` (+ `:171`, `:163`, `:201`) · ux · life-pres (2 map entries merged)

Each resident line is ~40-55 chars (~3 book lines of ~19 Cyrillic chars); 10 per page → ~30 lines on a 14-line page; `out.size()<4` drops everyone after 40 (MAX_RESIDENTS 64). `build()` writes ~18-19 lines; the request is appended after "Не хватает:" so it reads as a material; `store()` 2+12 lines wrap; `donors()` 10 entries wrap to 2 lines.

**Предложенное исправление:** a pure `BookLayout` helper (width estimate, 14 lines/page, no entry split) used by every ledger page and `Chronicle.book()`; put the request above "Не хватает:"; allow up to 16 people pages; unit-test page heights.

### M52. Предикаты еды расходятся: рождения игнорируют еду таверны, босс-бар «еда» считает другое, `restRecovery` не ест рыбу/мясо

**Статус: частично** (E6) — `Food` (обед, 4 овоща = 1 обед) используется рождениями и босс-баром; статус деревни («еды на складе»), `restRecovery` и `keptSlots` по-прежнему на `Supplies.food`.

`src/main/java/dev/autonomousvillages/Breeding.java:28` + `Supplies.java:26` · bug · life-pres, economy · **[verified]** (MEAL = bread, apple, cooked cod/salmon/porkchop/beef/mutton/chicken; `Supplies.food` = bread + raw crops)

`Breeding.MEAL` lacks BAKED_POTATO and COOKED_RABBIT, both made by the tavern from store potatoes/rabbit → cooking turns breeding food into nothing and births stall on "мало еды". Boss bar and status show `stock.count(Supplies::food)` (bread + raw veg only): "еда 0" while births happen or "еда 30" while breeding says short. `restRecovery` cannot heal with cooked fish/meat and `keptSlots` deposits them at once.

**Предложенное исправление:** one `Supplies.mealValue/meals` (bread, apple, baked potato, all cooked meat/fish, pumpkin pie, raw vegetables at 1/4) used by Breeding, boss bar, status, `VillageRequests.reward`, `restRecovery`; unit test.

### M53. Мусорные пожертвования заполняют склад, и работники перестают сдавать добычу

**Статус: исправлено** (E7) — Берутся только полезные деревне предметы; подарок не занимает последние 9 слотов склада.

`src/main/java/dev/autonomousvillages/PlayerGoods.java:61` · robustness · life-pres

Any donatable stack (dirt, gravel, rotten flesh) goes into the shared chests until no slot is free; DepositJob needs `freeSlots()>0`, so production stops. Exploit: 128 dirt given and taken back out earns permanent MAJOR_POSITIVE gossip.

**Предложенное исправление:** refuse/cap donations that would leave <~9 free slots; accept only items the village uses and count only those toward honour.

### M54. Просьба молча снимается после частичной доставки; даритель ничего не получает

**Статус: исправлено** (E7) — Частичные дарители запоминаются и получают благодарность при снятии просьбы.

`src/main/java/dev/autonomousvillages/VillageRequests.java:31` · ux · life-pres

When the build no longer lists the request key, the request is set to null with no message or reward; the tally is per request, so the player bringing the last item gets everything.

**Предложенное исправление:** on withdrawal with `delivered>0` announce thanks and pay proportionally to a `Map<UUID,Integer>` of contributors (validated in `VillageState`).

### M55. Просьбы включены при выключенных пожертвованиях — просьбу невозможно выполнить

**Статус: частично** (E7) — Просьбы выключаются вместе с пожертвованиями; объявление по-прежнему обещает доску «у колокола», даже если её нет.

`src/main/java/dev/autonomousvillages/VillageRequests.java:26` · bug · life-pres

`tick` checks only `CONFIG.requests`; delivery happens only through `PlayerGoods.useOnResident`, which returns PASS when `!CONFIG.donations`. The announcement also promises a board "у колокола" even when `noticeBoard()` is null.

**Предложенное исправление:** treat `!donations` like `!requests`; word the announcement by `noticeBoard()!=null`.

### M56. Объявления в чат без лимита и без переключателя

**Статус: исправлено** (E6) — Бюджет — одна строка на 100 тиков на деревню, пачка сливается; переключатель `chatAnnouncements`.

`src/main/java/dev/autonomousvillages/Chronicle.java:25` · ux · life-pres

`announce()` sends to every player within 64 blocks of the bounds: every building laid/completed (with coordinates), each mine deepening, golems, births, deaths, requests, fairs, feasts, honours, horses. `herald()` is rate-limited, chat is not; several villages multiply it; no `ModConfig` flag.

**Предложенное исправление:** per-village chat budget (1 line/30 s, merged "Также: …"); `ModConfig.chatAnnouncements`; routine lines → `event()` only.

### M57. Причины смерти показываются сырыми id сообщений или английскими именами мобов

**Статус: открыто**

`src/main/java/dev/autonomousvillages/AutonomousVillagesMod.java:65` · ux · life-pres · **[verified]** (`killer.getType().getDescription().getString()` or `source.getMsgId()`)

On a dedicated server `getString()` resolves in en_us: "Погиб Иван, фермер: Zombie", or raw "fall"/"inWall"/"drown"/"generic" — stored in chronicle and book, broadcast in chat. `PlayerGoods.java:91` does the same with item ids.

**Предложенное исправление:** small Russian maps for msgIds and common hostile mobs with a neutral fallback; label items via the presentation table.

### M58. Базовый провал тестов: прогрев не разрешает `testRuntimeClasspath`, затем Maven Central 429 валит обе задачи `test`

**Статус: исправлено** (E3, E5) — Хук прогревает `testRuntimeClasspath` и повторяет прогрев при 429 (E3); Gradle повторяет загрузки 6 раз с удвоением паузы (E5).

`.claude/hooks/session-start.sh:41` · robustness · tooling

Warm-up runs `compileJava compileTestJava soakClasses` only (no `junit-jupiter-engine`/launcher); the first `./gradlew test` downloaded the engine and got HTTP 429; `:test` and `:navigationAi:test` failed in dependency resolution before any test JVM started (0 XML). Nothing raises Gradle's retry/backoff.

**Предложенное исправление:** warm `:test :navigationAi:test` or a `warmCaches` task resolving test/soak runtime classpaths; `systemProp.org.gradle.internal.repository.max.retries=8`, `initial.backoff=2000`; optional Central mirror.

### M59. SessionStart-хук без таймаута делает минуты работы

**Статус: исправлено** (E5, E3) — Таймаут хука 1800 с (E5); без `set -e`, запасные источники JDK, понятные сообщения (E3).

`.claude/settings.json:7` · robustness · tooling

Downloads a ~200 MB JDK, clones navigation-ai, runs Gradle up to 4× with sleeps and boots a Fabric template (900 s subprocess timeout) under a 60 s default hook timeout → half-provisioned containers; `set -e` aborts everything after a failed curl (403 seen) with no guidance.

**Предложенное исправление:** `"timeout": 1800`; catch download failures with actionable messages; continue with independent steps.

### M60. Source set `soak` не компилируется `build`/`check` — рефакторинги мода молча ломают soak-gate

**Статус: исправлено** (E5)

`build.gradle:14` · test-gap · tooling

`compileSoakJava` runs only via `soakClasses`/`soakJar`; `SoakProbe.java:234-281` reads ~30 package-private internals; a rename passes `./gradlew build test` and fails only when a soak starts.

**Предложенное исправление:** `tasks.named('check'){dependsOn tasks.soakClasses}`; longer term a package-private `SoakView` record on Village.

### M61. Версия проекта всё ещё 0.2.2, хотя код — ломающая сохранения линия 0.3.0

**Статус: исправлено** (E5)

`gradle.properties:8` · bug · tooling, docs

Dev jars build as `autonomous-villages-0.2.2.jar` with 0.2.2 in `fabric.mod.json`, indistinguishable from the released jar in `build/baseline-jars`; `run.py` dedupes by filename; WORKLOG records the first rc3 batch running the old jar because of this; `bundleDistribution` would ship 0.3 code as 0.2.2.

**Предложенное исправление:** `version=0.3.0-dev`; update `compare.py --candidate-name` default and README soak example.

### M62. `compare.py` считает провалившиеся прогоны нулями и суммирует разные наборы меток

**Статус: исправлено** (E4)

`tools/soak/compare.py:121` · bug · tooling

A run with `completed=false` adds 0 deaths/births/blocks/villagers to the bold "итого" row quoted in WORKLOG for merge decisions; labels are the union but each side sums only its own.

**Предложенное исправление:** total only labels completed on both sides; list failed/missing runs with reasons; optional `--fail-if-worse` non-zero exit.

### M63. Перепись `builtBlocks` не видит кладку и другие блоки шаблонов — метрика смещена по биому

**Статус: открыто** — `SoakProbe` в этой волне не менялась.

`tools/soak/java/dev/autonomousvillages/SoakProbe.java:283` · bug · tooling

`BUILT` misses sandstone family, terracotta, stone bricks, walls, ladders, trapdoors, signs, hay, log frames; desert/savanna houses (Construction.masonry; seed 1337) are under-counted; `outcome.builtBlocks` and `score` drive comparisons.

**Предложенное исправление:** add those categories; emit the mod's own `chronicle.totals["placed"]` as a cross-check; document both.

### M64. `run.py`: один упавший job молча убивает своего воркера; код выхода всегда 0

**Статус: исправлено** (E4)

`tools/soak/run.py:199` · robustness · tooling

`worker()` has no try/except (cp -al failure, missing jar, truncated non-atomic `soak-result.json`); with `--parallel 1` all remaining jobs are skipped; `main()` exits 0 regardless; Ctrl-C leaves servers running.

**Предложенное исправление:** per-job try/except recording `runner_error`; `cp -a` fallback; defensive JSON; terminate Popen handles on SIGINT/atexit; `sys.exit(1)` on incomplete runs; atomic result write in SoakProbe.

### M65. `run.py --jars` может положить два jar'а navigation-ai в `mods/`

**Статус: исправлено** (E4)

`tools/soak/run.py:88` · bug · tooling

Folder jars are copied first, then NAV and fabric-api are always appended; dedupe is by exact filename only; a 0.2.1 library beside a 0.2.2 build makes Fabric refuse to start, showing up only as `no_result_file`.

**Предложенное исправление:** dedupe by artifact prefix (`navigation-ai-*`, `fabric-api-*`); fail early on duplicate prefixes.

### M66. Код перемещения предметов и ядро экономики не покрыты юнит-тестами (сохранение количества)

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Supplies.java:30-76` (+ `VillageStock.java:67-88`, `CraftJob.needs`, `PersonalKit.rank`, `Professions.product`, `keptSlots`, Approach counter) · test-gap · tooling, economy

`insertPartial/transfer/consume` and `put/consume` are the only paths moving items between bots and the store; the "partial insertion never consumes the source" invariant and stack math are never exercised; no fake `BotHandle`/`BotInventory`; Bootstrap setup duplicated in 4 classes; no planner decision covered.

**Предложенное исправление:** `testutil` with a Bootstrap JUnit extension, `FakeInventory`/`FakeBot`; `SuppliesTest` (totals conserved across transfer/deposit/withdraw/consume, full chests, non-stackables, max stack); `CraftNeedsTest` after refactoring `needs()` to a count lookup; tests for PersonalKit, Professions, the extracted Approach counter.

### M67. README обещает «не создают ресурсы из воздуха, не телепортируют груз» — код делает и то и другое

**Статус: открыто**

`README.md:141` · docs · docs

Profession products appear in chests (Worker:306-310, Professions:16-24), the pier creates 3 cod + 1 salmon a day (LandmarkGoods:175), the pen creates shears and sheep (Projects:497), the stable spawns horses, `furnish()` places workstations; donations and rewards move items instantly. README:83-84 itself describes the guild furnishing.

**Предложенное исправление:** rewrite the paragraph: gathering/building/crafting use real items; listed extras are deliberate gifts, each with its switch.

### M68. Справочник конфига в README пропускает 8 из 27 переключателей

**Статус: открыто** — Переключателей теперь 28 (добавлен `chatAnnouncements`).

`README.md:116` · docs · docs

Missing: lanterns, plaza, ambience, ledger, donations, landmarkGoods, lifeEvents, requests — all live and on by default; several hand items to players or touch trade reputation. README also omits that the config is read at startup only and rewritten.

**Предложенное исправление:** key/default/effect table from the ModConfig javadocs; `DocsConsistencyTest` reflecting over ModConfig fields.

### M69. `navReason` никогда не сбрасывается: старые провалы маршрута заражают последующие несвязанные провалы

**Статус: исправлено** (E1) — `navReason` обнуляется при создании каждого `Approach`/`Walk`.

`src/main/java/dev/autonomousvillages/Job.java:54` · bug · navigation · **[verified]** (set in `noteRoute` only on failure; never reset)

`fail()` appends it to every later failure; `unreachable()` votes against a chest using it; Approach keeps the old reason instead of `no_stand`. A BuildJob whose first cut stand failed `unreachable` and later runs out of material fails "материалы не донесены (unreachable)"; `Worker.endJob` counts any failure ending in "(unreachable)" as stuck → false TRAPPED → EscapeJob → teleport. The 8675309 "×22" report cannot be told apart from this artifact.

**Предложенное исправление:** reset `navReason=""` before each `goTo` and after SUCCESS; better, keep the reason on the Approach/Walk instance and pass it explicitly.

### M70. Подбор предмета отменяет маршрут подбора; с полным рюкзаком это перепланирование каждый тик

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Job.java:238` (+ `Worker.java:224`) · perf · navigation

`collect()` calls `w.pickup()` every tick; `pickup()` calls `BotInventory.insert` for every drop within 1.4 blocks, and the library's insert always `stopAll()`s first, even when nothing fits → cancels `pickupWalk`, `collect()` sends a fresh `goTo` whose capture is discarded next tick. With a full bag this repeats every tick for the whole collect window (60/200 ticks), spending up to 2048 of the 4096 snapshot cells per tick; with room, each picked drop still cancels and restarts the route (10-20 route requests per felled tree).

**Предложенное исправление:** skip items that cannot fit before `insert`; walk only to drops that fit; start a new pickupWalk only when the target changes or the previous failed; cap failures per window; larger pickup radius with LoS.

### M71. Лечение на отдыхе отменяет дорогу домой или патруль; три лечения проваливают `HomeJob` и считаются застреванием

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Worker.java:274` · bug · navigation · **[verified]** (`bot().inventory().extract(slot,1)` during Home/Patrol jobs; Walk counts CANCELLED as a failed attempt)

`extract` → `stopAll` cancels the running navigation; a wounded resident walking home at dusk loses one attempt per heal (every 400 ticks); after the third the walk FAILS → "не нашёл дорогу домой (cancelled)" → `cooldown("home", 6000)` (no home that night) and stuck count. Wounded guards lose patrol targets the same way.

**Предложенное исправление:** heal only when `job==null` or the route is not RUNNING/COMPUTING, or take food from the backing inventory without `stopAll`; don't charge an attempt for a route cancelled by someone else.

### M72. Задачи опрашиваются через `completion().isDone()`, каждый опрос аллоцирует и удерживает копию future до завершения

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Job.java:105` (+ Job 126,141,151,165,173,238,242; Worker 106,325,356; Village 372,425; BreathingSafety 25,35; MineJob 27,65,87) · perf · navigation

Every library task returns `completion.copy()`; on an unfinished future `copy()` pushes a UniRelay that stays until completion. A route in COMPUTING for 2000 ticks keeps thousands of future pairs per worker, all firing synchronously on completion. `fighting()` is polled per worker per lurker.

**Предложенное исправление:** `Job.done(status)` helpers polling `task.status()` (volatile) for navigation/mining/excavation/combat; `join()` once after terminal.

### M73. Время ожидания планировщика маршрутов засчитывается в лимит работы и в детекцию «застрял»

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Worker.java:136` · robustness · navigation

Timeout = `now()-job.started > timeLimit()` while Progress allows 2000 ticks of COMPUTING; under saturation (~50% of daytime samples COMPUTING) jobs time out "не уложился во время" → counted as stuck → EscapeJob with the DIG policy (never-reusable snapshot, radius 32) → more congestion (positive feedback). The library's `InfrastructureWaitAccounting`/`infrastructureWaitTicks()` exist for this and are unused.

**Предложенное исправление:** sample the route's wait in Approach/Walk; `now()-started-waitedTicks() > timeLimit()` with a hard 2× cap; leave infrastructure timeouts out of `stuckCount`.

### M74. Занятая стоячая ячейка → маршрут падает `unreachable` сразу, и это питает голоса о недосягаемости сундуков

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Job.java:122` (+ `Village.java:530` `stand`) · bug · navigation · **[unverified]** as to library internals (NavigatorImpl goal-set behaviour with tolerance .35 vs ≥.75 neighbour clamp); `stand()` ignoring entities and other workers' stands is **[verified]**

If the goal cell holds a living entity the neighbour radius is 1 but clamped neighbours are ≥.75 away → empty goal set → `unreachable` without searching. Residents from the same side pick the same closest stand; at the store chest one resident standing there makes the next one's route fail → `unreachable(chest)` → two voters reject the chest for `2400<<strikes` with strikes that never decay, hiding its contents.

**Предложенное исправление:** `stand()` skips cells whose foot/head AABB holds another LivingEntity; per-village claimed-stand set; vote only when the stand was free, or tolerance ≥0.8; decay strikes daily.

### M75. `Fetch`/`Deposit` застрявшего работника перебирают все сундуки по очереди

**Статус: частично** (E1) — Житель сразу пропускает сундуки, у которых сам недавно провалился, так что следующая работа перебор не повторяет; внутри одной работы перебор всех сундуков остался.

`src/main/java/dev/autonomousvillages/Job.java:196` (+ 218) · perf · navigation · **[verified]** (FAILED → `tried.add`, `chest=null`, return MOVING)

Up to 16 chests × 2 failed routes each → ~32 failing route requests (each possibly growing to radius 32) within one 2400-tick job; the two-voter rule protects the store but nothing stops the worker's own loop.

**Предложенное исправление:** count consecutive chest approaches failing unreachable/no_stand from within ~3 blocks of one spot; after 2, FAIL with a stuck reason so escape logic handles the worker.

### M76. `PatrolJob` никогда не фейлится при недосягаемых точках и запрашивает маршруты всю ночь

**Статус: исправлено** (G3) — Точка снимается после двух провалов маршрута, со всеми снятыми точками страж прекращает патруль на ночь.

`src/main/java/dev/autonomousvillages/PatrolJob.java:43` · robustness · navigation

On Walk FAILED the job moves to the next stop; a guard who cannot reach any stop spends 3 attempts per stop cycling for up to 13000 ticks; no failure reaches `endJob`, so stuck detection never triggers; repeated per guard.

**Предложенное исправление:** count consecutive failed stops (reset on ARRIVED); at `min(3, stops.size())` → `fail("не дойти до поста")` with `cooldown("patrol", 2400)`.

### M77. Ходьба к движущимся целям сбрасывает бюджет повторов при каждом перенацеливании

**Статус: частично** (G4) — `HerdJob` и `ShearJob` ограничивают перенацеливания за работу; `GiftJob` — нет; `EntityFollowGoal` не используется.

`src/main/java/dev/autonomousvillages/HerdJob.java:39` (+ `ShearJob.java:40`, `GiftJob.java:43`) · perf · navigation

A new Walk (new goTo, fresh snapshot, `attempts=0`) whenever the sheep/player moved 2-3 blocks; an unreachable moving target never reaches FAILED; requests continue until `timeLimit`. The library's `EntityFollowGoal/FollowGoal` re-plan by distance while keeping the current route.

**Предложенное исправление:** `goTo(EntityFollowGoal(sheep, 1.8, 3.0), TRAVEL)` via a `Job.Follow` helper with the Progress watchdog; at minimum carry `attempts` over.

### M78. Бегство перепланируется каждый тик, пока угроза в поле зрения

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Worker.java:338` · perf · navigation · **[verified]** (`danger()` every tick while fleeing; new `HomeJob(this).new Walk(point,1.5)` each time the flight is not MOVING)

At the refuge with a zombie still in view (through a window) each tick calls `stopAll`, builds a throwaway HomeJob to own a Walk, picks a point and sends `goTo` with a full fresh capture; a new Walk starts with `attempts=0`, so a failing flight never stops retrying.

**Предложенное исправление:** keep flight state across re-targets (failure count); hold position after the refuge or 2 failures; new flight route at most every 40 ticks; move Walk into a non-inner helper.

### M79. `Worker.endJob` отклоняет цель лишь на 600 тиков, перезаписывает более длинные сроки и не даёт бэкоффа недосягаемым целям

**Статус: открыто** — `endJob` по-прежнему ставит `now+600` с перезаписью; бэкофф с удвоением есть только для сундуков, верстаков и ячеек стройки (`UnreachableMemory`, E1).

`src/main/java/dev/autonomousvillages/Worker.java:174` · bug/perf · economy, safety, navigation · **[verified]** (`v.rejected.put(job.target().asLong(),now()+600)`)

`GraveJob:38`, `NameplateJob:45`, `HazardJob` and `MineJob:43` put `now+2400`/`1200` and then fail; `endJob` resets to 600 → retried four times as often, each a failed walk. For a tree/crop/hazard/cut no route reaches, the planner picks the same nearest target every 30 s via the TRAVEL growth path (8→16→32, ~143k cells, 32k nodes) — only chests/tables get `unreachableFrom` backoff.

**Предложенное исправление:** `v.rejected.merge(key, now+600, Math::max)`; for unreachable/no_stand/stuck reasons reject through a generalised strike backoff (`2400<<strikes`, capped, decaying).

---

## Низкая серьёзность

### L1. `safeFeet`/`feetPosition` читают состояния блоков до проверки загрузки чанка

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Village.java:479` · perf/robustness · lifecycle, navigation · **[verified]** (three `getBlockState` + collision shapes before `!level.hasChunkAt(p)`; `feetPosition` has no check)

On ServerLevel a read in a non-FULL chunk (outskirts beyond the radius-2 ticket ring) forces a synchronous load/generation; `stand()` calls both for 200 cells per call.

**Предложенное исправление:** return false at the top when `!hasChunkAt(p)||!hasChunkAt(p.below())`; guard `feetPosition` or call it only after `safeFeet`.

### L2. `mayBreak`, вызываемый библиотекой на каждую ячейку снимка, аллоцирует стримы и читает BlockState каждый раз

**Статус: открыто**

`src/main/java/dev/autonomousvillages/VillageRuntime.java:58` · perf · lifecycle, navigation · **[verified]**

Streams over villages to find the owner, streams policy zones, `getBlockState` in `isPrivate` and again at 67, checks `town.protects` for every village; EscapeJob's DIG capture covers up to ~143k cells.

**Предложенное исправление:** `Map<UUID,Village>` owner index rebuilt on residents changes; plain loops; read the block state once; cheap checks first.

### L3. Диапазон зависимости допускает несовместимые сборки navigation-ai; `LinkageError` минует catch тика

**Статус: открыто**

`src/main/resources/fabric.mod.json:12` · robustness · lifecycle · **[unverified]** (map itself unsure whether the APIs predate 0.2.2)

`"navigation_ai": ">=0.2.2"` has no upper bound while the library still reports 0.2.2 and the mod uses APIs it seems to have gained since (`TickWorkBudget.residentDecisionNanos`, `BlockBreakPermissions`, `navigationAiSavedState`). `NoClassDefFoundError`/`NoSuchMethodError` are Errors, not caught by `VillageRuntime.tick`.

**Предложенное исправление:** bump the library version on API change and declare `>=0.2.3 <0.3.0`; optionally catch `LinkageError` in tick and disable villages with a clear message.

### L4. Ошибки сохранения никогда не показываются операторам; периодический успех не сбрасывает флаг

**Статус: открыто**

`src/main/java/dev/autonomousvillages/VillageRuntime.java:29` · ux · lifecycle (docs) · **[verified]** (`persistenceWriteFailed()` has no callers; cleared only in the immediate branch)

`status`/`plan`/boss bar never mention failing saves; an owner can play for hours with nothing persisted.

**Предложенное исправление:** "⚠ сохранение не удаётся — см. лог" in `status()`; notify online ops once per streak; clear via a writer success callback; expose in the soak timeline.

### L5. Валидация claim и обработка частичного провала

**Статус: открыто**

`src/main/java/dev/autonomousvillages/VillageRuntime.java:80` · code-quality · lifecycle · **[verified]** (`radius` parameter ignored, constructor hard-codes 48; `villages.put`+`ticket` before `capture`/`save`)

An invalid name is caught only by `validate()` with the misleading "Повреждённые данные деревни"; if `capture` throws the command reports failure while the village stays claimed in memory and is written by the next periodic save.

**Предложенное исправление:** check the name regex in `claim()`; remove the unused parameter; build and capture before `put`/`ticket`, or roll back in a catch.

### L6. Нет тестов для персистентности рантайма, claim, аренд чанков, расширения и путей ошибок load/writeSave

**Статус: открыто**

`src/test/java/dev/autonomousvillages/VillageRulesTest.java:159` · test-gap · lifecycle, tooling

Untested: `load()` version gates (v1-3 retire naming, future-version refusal, version-less JSON parsing as v4), `writeSave` with one failing village, `VillageRuntime.lease` ref counting, `Village.ticket` wanted set, `expand()` rules, `VillageDiscovery.apply/horizontalDistance`, command validation — all embedded in classes needing a MinecraftServer.

**Предложенное исправление:** extract `SaveCodec.parse(String)`, `ChunkLeases` with a ticket callback, static `VillageState.mergeBounds`, `VillageControls` validators; unit-test each.

### L7. Планировщик предлагает крафты при включённом gamerule `doLimitedCrafting`

**Статус: открыто**

`src/main/java/dev/autonomousvillages/VillageCrafting.java:98` · bug · economy

`craft()` returns false under LIMITED_CRAFTING; `CraftJob.affordable` and every planner branch ignore it → each CraftJob fetches, walks and fails "не вышло" every 200 ticks.

**Предложенное исправление:** check the rule in `CraftJob.affordable` (or once per snapshot) and report in `idleReason`; decide whether villagers bypass the recipe book.

### L8. Крафты досок без гейтов «уже крафтится» и доступности

**Статус: открыто**

`src/main/java/dev/autonomousvillages/VillagePlanner.java:353` (+ 370) · bug · economy

`craftForProject`/`workshop` return `CraftJob(PLANKS,n)` from a 40-tick-old logs count without `!crafting(w,PLANKS)` or `affordable`; latecomers fail "нет на складе: доски" and get a 200-tick craft cooldown blocking door/bed crafts.

**Предложенное исправление:** add both guards to both branches.

### L9. Fetch семян и таблички игнорируют кулдаун «fetch» и зацикливаются на недосягаемом сундуке

**Статус: открыто**

`src/main/java/dev/autonomousvillages/VillagePlanner.java:563` (+ 157) · bug · economy · **[verified]** (seed FetchJob without `w.ready("fetch")`)

`endJob` sets a 200-tick "fetch" cooldown that nothing reads here; a farmer whose seeds sit in an unreachable chest retries every 20 ticks until two workers vote.

**Предложенное исправление:** require `w.ready("fetch")` as the furnace/tool/pick fetches do.

### L10. `Supplies.replace` без отката: может дублировать или уничтожить предметы

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Supplies.java:45` · robustness · economy

Ignores the stack returned by `extract()` (EMPTY when the library's `stopAll` claim fails → `next` inserted anyway → duplication); an insert remainder throws `IllegalStateException` after all 36 slots were extracted → destruction; `Inventory.add` can stack into offhand slot 40. Unlikely with the current library, but every craft/fetch/deposit/consume goes through it.

**Предложенное исправление:** check each extract against `before`; drop remainders as ItemEntity instead of throwing; fake-inventory unit test.

### L11. Snapshot ищет block entity печей без проверки загрузки чанка

**Статус: открыто**

`src/main/java/dev/autonomousvillages/VillagePlanner.java:61` · robustness · economy · **[verified]**

`v.level.getBlockEntity(BlockPos.of(packed))` for every `state.furnaces` entry; `Level.getBlockEntity` loads the chunk synchronously. Village.tick's own pruning guards with `hasChunkAt`. Furnaces are normally inside the ticketed footprint, so rare.

**Предложенное исправление:** skip entries whose chunk is not loaded.

### L12. `CompostJob` переиспользует один `Approach` для повторных походов — счётчик попыток исчерпывается ложным провалом

**Статус: исправлено** (G4) — Каждый поход к компостеру начинается с нового подхода.

`src/main/java/dev/autonomousvillages/CompostJob.java:69` · bug · gathering (see M12)

`toComposter` is created once; `attempts` only grows (each new stand counts, failures count twice) → after a few composter-crop round trips "не подойти к компостеру" though nothing is wrong.

**Предложенное исправление:** `toComposter=null` when leaving for crops (as `toCrop` already is).

### L13. `ChopJob` перепроверяет и пересортировывает весь список брёвен на каждом тике подхода

**Статус: исправлено** (G4) — Дерево обследуется один раз за работу, повторно проверяется только нижнее бревно.

`src/main/java/dev/autonomousvillages/ChopJob.java:38` (+ `ForestWork.java:23`) · perf · gathering

`ForestWork.next()` every APPROACH tick runs `remaining()`: removeIf over ≤256 entries (getBlockState, protects, policy stream each) then a sort whose comparator allocates two BlockPos per comparison; thousands of allocations per tick for jungle/dark oak with several loggers.

**Предложенное исправление:** pick the next log once per Approach and re-check only that block; recompute on Approach end or every 20 ticks; compare with `BlockPos.getY(long)`.

### L14. `BreathingSafety` сканирует 8125 ячеек с рейкастами каждый тик, пока не находит воздух / после каждого провала маршрута

**Статус: открыто**

`src/main/java/dev/autonomousvillages/BreathingSafety.java:37` · perf · gathering, navigation

When `findAir()` returns null the code only jumps and calls it again next tick (25×13×25 box, 3 reads + 2 `hasChunkAt` per cell, `noBlockCollision` + raycast per candidate); after a failed surfacing route the target is rejected and the scan plus a new `goTo` repeat every few ticks; deep water (>12 above head) never yields a target.

**Предложенное исправление:** retry every 10-20 ticks; scan the column straight up first and extend the range when empty; raycast only the best ~16 candidates; reject a 3×3 around a failed target.

### L15. Когерентные рецепты считаются доступными при смешанных досках без брёвен — крафт проваливается снова и снова

**Статус: исправлено** (G4) — Однородные рецепты доступны только при досках одного дерева или брёвнах одного дерева (`Wood.covered`).

`src/main/java/dev/autonomousvillages/CraftJob.java:60` · bug · gathering

For door/fence/slab/stairs/sign/trapdoor/gate: when no single plank type covers the need and there are no logs, `plank` falls back to any PLANK so `affordable()` is true; `VillageCrafting.craft` requires one type → "не вышло" → re-issued after the 200-tick cooldown; the house waits on a door forever.

**Предложенное исправление:** when `recipe.coherent()` and both `mostCommon(PLANK, planks)` and `mostCommon(LOG, 1)` are null, return an unmeetable Need / `affordable()` false.

### L16. Остановка работы шахты выбрасывает уже вырытые сегменты

**Статус: исправлено** (G1) — Пройденные ступени сохраняются по ходу и при `stop()`.

`src/main/java/dev/autonomousvillages/MineJob.java:87` · code-quality · gathering · **[verified]** (`stop()` cancels without copying `completedSegments()` or running `uncoverOres()`)

On danger/flee/breathing/timeout the next job re-walks the cleared steps and ore found along them is never recorded.

**Предложенное исправление:** before cancelling, `mineLength=max(mineLength, min(64, completedSegments()))` and `uncoverOres()`.

### L17. `HerdJob` выбирает вторую овцу по расстоянию до работника — пара может никогда не размножиться

**Статус: исправлено** (G4) — Первая овца — с парой в 8 блоках, вторая — ближайшая к первой.

`src/main/java/dev/autonomousvillages/HerdJob.java:33` · bug · gathering

Vanilla BreedGoal finds a partner within 8 blocks and love lasts 600 ticks; a far second sheep (anywhere within 48) wastes the wheat. Wool for beds is the growth bottleneck.

**Предложенное исправление:** remember the first sheep and choose the second among adults within 6 blocks of it; otherwise keep the wheat and end.

### L18. Нет тестов для добывающей логики; алгоритмы нельзя тестировать, так как они берут живую `Village`

**Статус: исправлено** (G1, G4) — `GatheringTest` и `MineJobTest` проверяют вынесенные чистые правила (топливо, окно сбора, овцы, дерево, геометрия и вердикты шахты).

`src/main/java/dev/autonomousvillages/ForestWork.java:9` · test-gap · gathering

ForestWork BFS, shaft length/termination, fuel amounts, `CraftJob.needs/affordable`, collect timing all read Village/ServerLevel; the pickup-delay regression and the cooldown-key mismatch would each have been caught by a small test.

**Предложенное исправление:** extract `ForestWork.connectedLogs(isLog, allowed, base)`, `MineJob.clampLength(inBounds, request)`, `SmeltJob.fuelFor(amount, coal)`, `CraftJob.needs` over a `ToIntFunction<Predicate>`; test in `GatheringTest`.

### L19. Лимит времени `PatrolJob` короче ночного окна — каждый страж «проваливается» каждое утро

**Статус: исправлено** (G3) — Лимит патруля — до утра, дозор завершается на рассвете без провала.

`src/main/java/dev/autonomousvillages/PatrolJob.java:34` · bug · safety

`timeLimit` 13000 vs night 11500→1000 (13500); a guard starting at dusk times out at clock 500 → "не уложился во время" → stuckCount, `v.failures`, frown, JOB_FAILED, and a new PatrolJob for 500 ticks.

**Предложенное исправление:** 14000 like ShelterJob, or return false without failure near the window end.

### L20. `ShelterJob.find` — неограниченный скан ячеек вне бюджета решений

**Статус: исправлено** (G3) — Поиск по ячейкам пола ближайших домов, затем вокруг кроватей, с бюджетом 640 ячеек.

`src/main/java/dev/autonomousvillages/ShelterJob.java:21` · perf · safety · **[verified]** (9×3×9 per bed within 64 blocks; called from the night branch without `mayPlan`)

Each candidate runs protects, hasChunkAt, canSeeSky×2, safeFeet, ready; with 50+ beds 12k+ cells per call, by every bedless resident at dusk and every 600 ticks when nothing is found, several in one tick.

**Предложенное исправление:** village computes a deduped shelter-cell set every ~200 ticks (or the interior flood fill from M29) and `find()` picks from it; or charge the work to the decision budget.

### L21. `EscapeJob` копает до самого колокола, в том числе ночью

**Статус: исправлено** (G3) — Днём — к двум ближайшим открытым клеткам поверхности, затем к дому; ночью — к домам; колокол последним.

`src/main/java/dev/autonomousvillages/EscapeJob.java:18` · bug · safety

Started from the night branch after three failed routes from one spot (e.g. HomeJob at a doorstep), it sends the resident outdoors to the meeting point with breaking and placing allowed for the whole route; dug drops are left on the ground.

**Предложенное исправление:** at night target the nearest shelter cell/home, otherwise a safe cell ~8 blocks toward the hub; finish once >4 blocks from `stuckAt` or a plain TRAVEL route succeeds.

### L22. Проверка «табличка не изменилась» никогда не срабатывает: каждая табличка переписывается и пересылается каждые 30 с

**Статус: исправлено** (G3)

`src/main/java/dev/autonomousvillages/NameplateJob.java:90` · perf · safety

`SignText` in 26.2 declares no equals/hashCode and `setMessage` returns a new object, so `text.equals(sign.getFrontText())` is identity and always false; `refresh()` calls `setText` on up to 256 plates plus an extra `setChanged`/`sendBlockUpdated` (double packets, chunks marked dirty).

**Предложенное исправление:** compare per-line strings; drop the extra `setChanged`/`sendBlockUpdated` after `setText`.

### L23. Строки табличек шире 90-px знака: счётчик «и ещё N» и длинные имена обрезаются

**Статус: исправлено** (G3)

`src/main/java/dev/autonomousvillages/NameplateJob.java:82` (+ `GraveJob.java:47`) · ux · safety

~15 Cyrillic chars fit; "Ярослава и ещё 2" is 16; name-tag names up to 50 chars are cut on nameplates and graves. `NameplateTest` currently asserts an overflowing line.

**Предложенное исправление:** names in rows 1-2 and "и ещё N" alone in row 3; `fit(String)` helper with an approximate glyph-width table and "…"; unit test.

### L24. `PathJob` никогда не помечает недосягаемые ячейки тропинки — они предлагаются вечно

**Статус: исправлено** (G2) — Недосягаемая ячейка отвергается на игровой день, планировщик не предлагает отвергнутые.

`src/main/java/dev/autonomousvillages/PathJob.java:37` · bug · safety

A failed cell is dropped only from this job; `Projects.unpaved()` does not consult `v.rejected`, so every new PathJob gets the same cells at up to 6 route attempts each.

**Предложенное исправление:** `v.rejected.put(cell, now+2400)` on failure; filter with `Lighting.unreachable` in `unpaved/pave`.

### L25. `HazardJob` (и `FlowerJob` по таймауту) теряют счётчики летописи

**Статус: частично** (G3) — `HazardJob` записывает каждую клетку сразу; `FlowerJob` — по-прежнему только в `finish()`.

`src/main/java/dev/autonomousvillages/HazardJob.java:32` · code-quality · safety

`chronicle.add("hazards")` only at line 24 when cells run out at the start of a tick; packing or failing the last cell returns false without recording; FlowerJob records only in `finish()`.

**Предложенное исправление:** record each cell as it is done (as PathJob does) or in `stop()`.

### L26. Кладбище не перевыбирается, когда заполнено/заблокировано; неудачный поиск повторяется

**Статус: исправлено** (G3) — Заполненное или застроенное кладбище заменяется новым участком; неудачный поиск ждёт 6000 тиков.

`src/main/java/dev/autonomousvillages/GraveJob.java:60` · bug · safety

4×4 grid holds 16 signs then `spot()` returns null forever while graves keep coming; an unreachable or built-over plot is kept; a null result is not cached so each worker reruns `chooseCemetery` (~150 corners × 16 columns × beds stream) every 2400 ticks.

**Предложенное исправление:** extend rows or pick an adjacent plot when full; re-choose after N failures; remember "no plot" for a day.

### L27. Фонарный столб остаётся голым забором, если факел не удалось поставить после забора

**Статус: исправлено** (G2)

`src/main/java/dev/autonomousvillages/LightJob.java:45` · robustness · safety

No rollback: if the torch/lantern placement fails (entity at the torch cell) the fence stays; the heightmap top is now a non-sturdy fence so `needsLight` never picks the column again — permanent dark spot plus stray post.

**Предложенное исправление:** check the torch cell before placing the fence, or break the fence back into the bag on failure.

### L28. Фонарные столбы могут перекрыть единственный проход к двери или в переулок

**Статус: исправлено** (G2)

`src/main/java/dev/autonomousvillages/Lighting.java:76` · ux · safety

`byDoor` checks only within 1 block, so a 1.5-high solid post can go 2 blocks in front of a door or in a 1-wide gap between houses (rc3 worklog: residents stuck at a door facing a post).

**Предложенное исправление:** for lamp posts require ≥3 walkable horizontal neighbours, no door within 2 along its facing, no DIRT_PATH alongside; otherwise ground torch.

### L29. Фильтр мастерских выбрасывает дома бронника, лучника, каменщика и пастуха

**Статус: исправлено** (G5)

`src/main/java/dev/autonomousvillages/Blueprints.java:53` · bug · construction · **[verified]** (`!n.contains("_house_")`)

Removes `plains_armorer_house_1`, `fletcher_house`, `masons_house`, `shepherds_house`; `houses()` needs beds>0 so they are never built; `Projects.trade` branches for fletcher/mason/shepherd are dead; `trade("…_armorer_house_1")` → "armorer_house" falls to the default label. A shepherd's house would help the wool bottleneck.

**Предложенное исправление:** choose workshops by `beds()==0` plus existing excludes; strip a trailing `_house` in `trade()`; `CivicTest` cases.

### L30. Каждая установка блока форсит полную перестройку индекса защиты

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Construction.java:122` · perf · construction · **[verified]** (`v.town.invalidate()` after every `setBlock`)

The next `protects()` rebuilds the boxed `HashSet<Long>` of every plan cell of every building (decoding states, allocating BlockPos for doors/beds); protection depends only on plans, never on world state.

**Предложенное исправление:** remove `invalidate()` from `place()`, keep it on lay/capture; `LongOpenHashSet`.

### L31. Сопоставление плана аллоцирует в горячих циклах и обходит кэш подсчёта склада

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Construction.java:19` (+ `TownBuilding.java:34`, `Projects.java:582`) · perf · construction

`matches()` creates `Set.of("facing",…)` per property per state per call; `Plan.step()` allocates a Step each call; these run for every plan cell in remaining/built/missing/nextSteps every 100 ticks and per worker decision; `nextSteps` passes a new lambda per optional plan to `VillageStock.count` → identity cache always misses.

**Предложенное исправление:** static property-name set; cache Step/item in Plan (transient); one predicate per item.

### L32. Исключение в хуке завершения навсегда пропускает поздние хуки здания и сохранение

**Статус: исправлено** (C1) — Каждый хук в своём try/catch, ошибка логируется один раз, сохранение выполняется.

`src/main/java/dev/autonomousvillages/Projects.java:117` · robustness · construction · **[verified]** (`b.completed=true` before hooks; no try/catch)

If `Festivities.housewarming` throws, the pen never gets gate and sheep, the well is never filled, `save()` is skipped; the exception reaches the unguarded `town.tick()` in Village.tick so breeding and all workers miss that tick (see M3).

**Предложенное исправление:** wrap each hook in its own try/catch with rate-limited log; guard `town.tick()`.

### L33. `Construction.place` игнорирует результат `setBlock` после списания предмета и подписывает каждый знак «СКЛАД»

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Construction.java:121` · code-quality · construction · **[verified]**

Items are consumed before `level.setBlock`, whose boolean is ignored (item lost on a refused write); every sign placed gets the store text; graves and nameplates overwrite it, landmark signs show it until `signboard` runs.

**Предложенное исправление:** check `setBlock`'s return and refund; move the store-sign text to the notice-board caller.

### L34. Нет тестов автомата проектов, `siteAt`, `palisade()`, подмен и рукописных построек

**Статус: частично** (C1) — `ProjectsTest`: очередь, предикаты площадки, частокол, усыновление; подмены `Construction.available`, `sameMaterial` и `TownBuilding.valid` без тестов.

`src/main/java/dev/autonomousvillages/Projects.java:108` · test-gap · construction

`Projects.tick` ordering/gates only exercised by soak (which never reached the tower); storehouse/tower/well/pen/cottage lack the reach/support/duplicate checks Landmarks get; `Construction.available` substitution, `sameMaterial`, `TownBuilding.valid`, Plan JSON round trip untested.

**Предложенное исправление:** pure next-project decision over a facts record, table-driven; move hand-written structures into Design data; tests for substitution/matching/valid.

### L35. Номер дня различается между летописью и утренними новостями

**Статус: частично** (E6) — `Chronicle.day(clock)` — один номер дня для летописи и утренних новостей; могилы (`AutonomousVillagesMod`) всё ещё по `getGameTime()`.

`src/main/java/dev/autonomousvillages/Chronicle.java:19` · bug · life-pres

`Chronicle.day()` uses `getGameTime()/24000+1` (no jump on sleep); MorningNews (49) and the fair use the overworld clock day; graves use gameTime too → "Утро, день 10" vs "день 6".

**Предложенное исправление:** one helper `floorDiv(getOverworldClockTime(), 24000)+1` for chronicle, graves, certificates.

### L36. Пир может праздновать захваченный родной дом или не то здание

**Статус: исправлено** (E7)

`src/main/java/dev/autonomousvillages/LifeEvents.java:82` · bug · life-pres

`watchCompletions` compares the count of completed buildings and names the last in list order; `capture/captureHouse` append `native_house` with `completed=true` on expansion → "Пир в таверне в честь: дом деревни!" for something nobody built; `completed`/`feastFor` transient so the result depends on tick order after restart.

**Предложенное исправление:** `Projects.tick` calls `v.lifeEvents.completed(b)` at line 117 (skip native_house/tavern); or count only non-native completions.

### L37. Рождение звонит в колокол трижды; колокола ярмарки и рождения звучат там, где колокола нет

**Статус: исправлено** (E7)

`src/main/java/dev/autonomousvillages/LifeEvents.java:103` (+ 56) · ux · life-pres

`Chronicle.born` → `announce` → `herald` (one stroke), then `watchBirths` adds two more plus the lullaby bypassing the 10 s limit; `watchBirths`/`openFair` play BELL_BLOCK at `meetingPoint()` even when it is only the fallback point.

**Предложенное исправление:** one bell helper checking `Blocks.BELL` with a shared rate limit; skip herald for births.

### L38. Еда съедается до того, как ребёнок существует; неудачный спаун всё равно записывает рождение

**Статус: исправлено** (E6) — Сначала спаун ребёнка, потом еда; текст причины исправлен.

`src/main/java/dev/autonomousvillages/Breeding.java:54` · bug · life-pres, docs · **[verified]** (consume at 54-56, `getBreedOffspring` may return null at 57; `addFreshEntityWithPassengers` result ignored)

The blocker text still says "нужно 3 хлеба или яблока" although MEAL includes cooked fish/meat.

**Предложенное исправление:** create and add the baby first (check the boolean), then consume; fix the message.

### L39. Текст доски обрезан, кэш не привязан к позиции знака, английские id материалов

**Статус: частично** (E6) — Строки обрезаются до 15 символов и сравниваются с табличкой на её позиции; ключи без перевода (`NEEDS.getOrDefault(key,key)`) остались.

`src/main/java/dev/autonomousvillages/VillagePresentation.java:74` · ux · life-pres

`boardText` compared without position (a replaced board stays blank until the list changes); "нужно на стройку:" ~100 px vs 90 px; `NEEDS.getOrDefault(key,key)` keeps the `minecraft:` prefix and lacks trapdoor, stone_stairs, stone_slab, stone_wall, glass_pane, ladder, fence_gate.

**Предложенное исправление:** key the cache by `board.asLong()+text`; header "нужно:"; one label function covering every `materialKey` with `Component.translatable` fallback.

### L40. Рутинные события вытесняют важную историю из 60-записной летописи

**Статус: частично** (G4) — `SmeltJob` пишет в летопись только загрузку печи; приоритета событий нет.

`src/main/java/dev/autonomousvillages/Chronicle.java:21` (+ `SmeltJob.java:39`, `PlaceJob.java:38`, `syncRoles`) · code-quality · life-pres

No notion of importance; SmeltJob logs every furnace visit incl. output pickups as "Загрузили печь: забирает готовое" (wrong); births, deaths and completions scroll out of the book within a day or two.

**Предложенное исправление:** event priority or keep routine events out of the list (count in totals); SmeltJob logs only loads.

### L41. Неверный грамматический род и дубли имён

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Chronicle.java:45` (+ `PlayerGoods.java:138`, `VillagePresentation.java:43`) · ux · life-pres

"Родился "+name / "Погиб "+who always masculine while NAMES is ~half female ("Родился Мария"); names are UUID-hash picks from 40 entries (a village of 10 has ~70% chance of a duplicate).

**Предложенное исправление:** gender flag per name (two arrays); first unused name within the village, persisted in `residentNames`.

### L42. Хук клонирует navigation-ai HEAD и «последнюю» JDK без пина и проверки

**Статус: открыто** — Клон по-прежнему без пина ревизии (E3 добавил только повторы).

`.claude/hooks/session-start.sh:34` (+ 16) · robustness · tooling (docs: CONTRIBUTING pins an older rev — L54)

`git clone --depth 1` takes the default branch; two sessions on the same mod commit can build against different libraries; soak comparisons stop being reproducible; JDK `latest/25/ga` without checksum.

**Предложенное исправление:** `navigation_ai_ref` in `gradle.properties`; `git fetch --depth 1 origin $REF && checkout FETCH_HEAD`; warn on a divergent sibling; pin and verify the JDK.

### L43. Задачи релиза жёстко ссылаются на файлы эпохи 0.2.2 и молча пропускают отсутствующие

**Статус: частично** (E5) — Отсутствующий файл валит сборку («Distribution files missing»); документы эпохи 0.2.2 (`RELEASE_0.2.2.md`, `STATIC_AUDIT`, старый `VALIDATION.md`) в списке остались.

`build.gradle:57` (+ 41) · bug · tooling

`assembleDistribution` copies `RELEASE_0.2.2.md`, `STATIC_AUDIT_2026-10-17.md`, `docs/release/**`; shipped `VALIDATION.md` tells users to run `tools/validation/run.py` which no longer exists; Gradle Copy ignores missing `from` files.

**Предложенное исправление:** derive paths from `project.version`; `doFirst` failing on missing files; drop stale references.

### L44. `Task.project` на этапе выполнения (несовместимо с Gradle 10, уже репортится)

**Статус: исправлено** (E5)

`build.gradle:29` (+ 60-75) · code-quality · tooling

The expand closure reads `project.version` lazily; the `assembleDistribution` doLast uses `project.layout`, `fileTree`, `project(':navigationAi')`; this is why configuration-cache is off.

**Предложенное исправление:** capture values at configuration time (`def v=project.version.toString(); inputs.property…`); typed task with `@InputFiles/@OutputFile`.

### L45. Периодическая перепись вычисляется и выбрасывается, съедая время тика внутри измеряемого прогона

**Статус: открыто**

`tools/soak/java/dev/autonomousvillages/SoakProbe.java:165` (+ 101) · perf · tooling

Every 6000 ticks `lastCensus=census()` (~1.3M `getBlockState` + tag checks + every chest) is never read; `finish()` runs a fresh census; the spike inflates mspt and lowers avgTps for every build; Properties strings are re-parsed five times per tick.

**Предложенное исправление:** delete it or write its delta to the timeline (built-blocks curve); read config once into static fields.

### L46. `writeMap` читает heightmap возможно незагруженных чанков, форсируя синхронную загрузку/генерацию

**Статус: открыто**

`tools/soak/java/dev/autonomousvillages/SoakProbe.java:343` · robustness · tooling

`census()` guards with `hasChunkAt` (369), `writeMap()` does not; the area grows to bounds+16 while the ticket radius is 9 chunks (~144 blocks) → a radius ~135 village plus margin exceeds the ticket → chunk loads on the server thread every 12000 ticks, changing the world under test.

**Предложенное исправление:** skip unloaded columns (−1/"unloaded", grey in map.py), or size the ticket from the final area.

### L47. Загрузки шаблона не атомарны: усечённый jar навсегда проходит проверку готовности

**Статус: частично** (E4) — Загрузки через `*.part` с проверкой zip; общий порт 25599 и отсутствие блокировки при параллельной подготовке остались.

`tools/soak/run.py:44` (+ 58) · robustness · tooling

`urlretrieve` writes straight to the final names; an interrupted download leaves `exists()==True`; the prepare boot always uses port 25599 so concurrent `--prepare-only` calls collide.

**Предложенное исправление:** download to `*.part`, verify with `zipfile.is_zipfile`, `os.replace`; file lock around `ensure_template`; random boot port.

### L48. Крайние случаи CLI роняют инструменты пост-обработки

**Статус: частично** (E4) — `digest`/`compare`/`map` дают понятные ошибки, `--every` ≥ 1, метки разбираются через `rpartition`; повторные сиды в `run.py` не схлопываются.

`tools/soak/digest.py:36` (+ `compare.py:113`, `map.py:76,11`, `run.py:190`) · robustness · tooling

`--every 0` → ZeroDivisionError; `l.split('-')[1]` IndexError for labels without '-' and mis-sorts negative seeds; `load(frames[0])` IndexError with no maps; PIL import without hint; `run.py` does not dedupe repeated seeds (two workers share a lab name and one `rmtree`s the other's live lab).

**Предложенное исправление:** argparse type checks, `rsplit('-',1)`, explicit errors, `dict.fromkeys` dedupe.

### L49. `events.log` не содержит собственных строк пробы; `logErrors` насыщается на 200

**Статус: исправлено** (E4)

`tools/soak/run.py:143` (+ 145-148) · bug · tooling

Markers miss `SOAK ` lines (claimed, run started, births, finish reason); problems are sliced to [:200] before `len()`.

**Предложенное исправление:** add `b'SOAK '`; count all problem lines first, write only the first 200.

### L50. `make_timelapse.py` работает только на одной Windows-машине

**Статус: открыто**

`tools/showcase/make_timelapse.py:27` (+ 28) · robustness · tooling

ffmpeg hard-coded to `~/AppData/Local/universal-modder/ffmpeg/bin/ffmpeg.exe`, font to `C:/Windows/Fonts/arial.ttf`; fails on the Linux stand; shipped via `sourceTree`.

**Предложенное исправление:** `shutil.which('ffmpeg')`/`--ffmpeg`; system font via fc-match/`--font`; clear error when missing.

### L51. Некоторые тесты ничего не проверяют о моде

**Статус: открыто**

`src/test/java/dev/autonomousvillages/MaterialsTest.java:36` (+ AmbienceTest:49, PlayerGoodsTest:43, CivicTest:20, LifeEventsTest:30, LandmarkGoodsTest:40) · test-gap · tooling

`assertTrue(0L-Long.MIN_VALUE<0)` tests Java arithmetic; several `newSwitchesAreOnByDefault` tests re-read field initialisers.

**Предложенное исправление:** one `ModConfigTest` case asserting every boolean switch defaults to true via reflection; delete the arithmetic assert.

### L52. Поведение големов описано противоречиво в комментариях и документах

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Village.java:459` (+ `ModConfig.java:17`, `CHANGELOG.md:24`) · docs · docs

Code: golems at dusk and on alarm, up to `min(3, 1+residents/10)`, replacement after 6000 ticks; the stale javadoc, ModConfig comment ("at most once a day") and CHANGELOG ("раз в сутки") describe the old behaviour; README:63-65 is correct.

**Предложенное исправление:** delete the stale javadoc; fix the comment and CHANGELOG line.

### L53. Устаревшие документы 0.2 дают неверные инструкции и попадают в дистрибутив

**Статус: открыто**

`VALIDATION.md:71` (+ `MODDING_PLAN.md:3,14`, `docs/ROADMAP.md:18,42-50`, `build.gradle:37,49-50`, `.gitignore`) · docs · docs

References to `tools/validation/run.py`, `validationJar`, `clientValidationJar`, `/avillage orders`, deleted classes (DeliveryRuns, Resident, PersonalKits, Orders); "No further tests are authorized" contradicts AGENTS.md; bundled while PLAN-v0.3, WORKLOG, CONTRIBUTING are not.

**Предложенное исправление:** move to `docs/history/` with a banner; write a short 0.3 VALIDATION.md and ROADMAP; fix include lists and `.gitignore`.

### L54. CONTRIBUTING противоречит реальной настройке (ревизия библиотеки, имена каталогов, упаковка)

**Статус: открыто**

`CONTRIBUTING.md:23` (+ 12-17, 45-46) · docs · docs

Pins navigation-ai 45156ef7 (pre-PR #1, ~15× slower pathfinding) while master is tested against f97d4d8 and the hook clones unpinned main; requires directory name "AutonomousVillages" though only `../MinecraftmodNavigationAi` matters; says packaging is in README (it is not).

**Предложенное исправление:** pin f97d4d8 (and the same in the hook); state only the library directory matters; describe `bundleDistribution` or drop the sentence.

### L55. PLAN-v0.3 частично устарел и без статуса

**Статус: открыто**

`docs/PLAN-v0.3.md:58` (+ 32, 61, 72, 84) · docs · docs

"400 TPS" vs default 100; Phase 1.7 save goals not met; no phase marked done/open; acceptance criteria (normal 5d ≤1 death, 10-day timelapse) never measured (the 8-day showcase stopped at 0.6 days); delivered items not ticked, absent ones (town hall, zoning, raids, caravans) unnoted.

**Предложенное исправление:** per-phase status with WORKLOG evidence links; fix the TPS figure; mark 1.7 open or link the fix.

### L56. Раздел CHANGELOG 0.3 устарел и неполон

**Статус: открыто**

`CHANGELOG.md:22` (+ 13, 19-20, 34, 89, 107) · docs · docs

"готовится" sections were merged in #5/#6/#9; "3 хлеба"; rc3 numbers superseded; `/avillage orders` mentioned; missing: ledger, donations/honour, requests, fair/feast, landmark goods, ambience, lanterns, plaza, doorway push-out (#10), false-alarm fixes (#8), early pen (#12), boss bar; 0.2 entries not marked as replaced code.

**Предложенное исправление:** one 0.3.0 list by area with gate numbers; "История 0.2 (код заменён ядром 0.3)".

### L57. Отчёт внешнего ревью не говорит, какие рекомендации ещё применимы к 0.3

**Статус: открыто**

`docs/reviews/2026-10-19-external-review.md:194` · docs · docs

All 10 fixes target deleted 0.2 files; of the open items #1 (sync save) and #2 (all-or-nothing) still apply, #4 (farmSites grows) applies, #3 no longer applies, #5 resolved, #6 (logs in .gitignore) applies — a reader cannot tell.

**Предложенное исправление:** a "Статус в 0.3" table (applies / fixed / n/a with file:line).

### L58. Нет тестов, охраняющих документированные правила и синхронизацию документов с кодом

**Статус: открыто**

`src/test/java/dev/autonomousvillages/ModConfigTest.java:10` · test-gap · docs

Nothing checks README config list vs ModConfig, help text vs registered commands, README numeric promises (Breeding food/cap, golemsWanted, Professions.product, storage/private limits), malformed config.

**Предложенное исправление:** `DocsConsistencyTest` (reflection over ModConfig vs README; command literals vs help/README); `BreedingRulesTest`, `ProfessionsTest`, `golemsWanted` in `VillageRulesTest`.

### L59. Маршруты подбора переживают `Harvest` и саму работу

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Job.java:173` · bug · navigation · **[verified]** (`Harvest.cancel()` and the DONE path never call `stopPickup()`; BuildJob/FarmJob/MineJob `stop()` don't either)

After the job ends the body keeps walking to an old drop until the next job's `goTo` or an idle `stopAll`.

**Предложенное исправление:** `stopPickup()` in `Harvest.cancel()` and on DONE/FAILED; or a base-class final wrapper that Worker calls instead of `job.stop()`.

### L60. Классификация застревания — сопоставление русских UI-строк

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Worker.java:178` · code-quality · navigation · **[verified]**

Whether a failure counts as stuck (→ EscapeJob → teleport) depends on `startsWith`/`contains` against player-facing messages; rewording changes behaviour silently; the watchdog's `cancelled` is not counted while planner timeouts are.

**Предложенное исправление:** `Job.FailureKind` enum (ROUTE_UNREACHABLE, ROUTE_STALLED, ROUTE_STUCK, PLANNING_TIMEOUT, NO_MATERIAL, OTHER) set by Approach/Walk/`fail()`; base `stuckCount` on it.

### L61. Бой сбрасывается через 600 тиков без отмены

**Статус: открыто**

`src/main/java/dev/autonomousvillages/Worker.java:106` · robustness · navigation · **[verified]** (`combat=null` without `cancel()`)

The library CombatTask keeps running and owns the body (navigator tick skipped) until a later claim cancels it; meanwhile the worker plans and starts a new job as if free.

**Предложенное исправление:** `combat.cancel()` before `combat=null` when not finished.

### L62. Нет юнит-тестов логики маршрутов

**Статус: частично** (E1) — `UnreachableMemoryTest` покрывает память недосягаемых, кворум, выбор сундука и отсрочку ячеек; `Approach/Walk/Progress`, `collect`, классификация в `endJob` и бегство — без тестов.

`src/test/java/dev/autonomousvillages/VillageRulesTest.java:84` · test-gap · navigation

Only `Worker.nightRestWindow` is covered; Approach/Walk/Progress, Fetch/Deposit, collect, endJob classification, danger/flight, `unreachableFrom` votes/backoff and `Village.stand` have none — every navigation defect is reachable only through soak.

**Предложенное исправление:** extract route decisions into small classes behind task/stand/clock interfaces; `RouteLogicTest` with fake tasks.

---

## Сводка

Статусы исправления на 20 октября 2026 — в разделе «Статусы» в начале документа; ниже — исходная сводка синтеза.

| Серьёзность | Записей | Из них [verified] |
|---|---|---|
| Высокая | 10 | 10 |
| Средняя | 79 | ~40 |
| Низкая | 62 | ~15 |
| **Итого** | **151** (из 179 исходных) | |

Помечены **[doubtful]**: детальная формулировка docs-product по лимитам сундуков/печей (M11 — лимиты
применяются при установке, а не игнорируются). **[unverified]**: M46 (ванильные шаблоны), M74 (поведение
библиотеки при занятой целевой ячейке), L3 (совместимость версий navigation-ai).
Возможностей (opportunities) во всех картах: 102 (lifecycle 11, economy 10, gathering 12, safety 11,
construction 12, life-pres 11, tooling 11, docs 12, navigation 12); они не дедуплицированы здесь, но
основные пересечения: единая память недосягаемых целей (economy/gathering/construction/navigation),
отложенное сохранение (lifecycle/gathering/life-pres/docs), фейки для тестов инвентаря
(economy/tooling), тик-профилировщик и soak-метрики (lifecycle/gathering/safety/construction/
navigation/tooling), один предикат еды (economy/life-pres).

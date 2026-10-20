# Архив документов 0.1–0.2.2

Документы эпохи 0.1–0.2.2, которые больше не описывают текущий мод (0.3.0-dev). Они хранятся как
история: содержание не обновляется, в начале каждого — баннер об архиве, относительные ссылки внутри
переписаны под новые пути. В комплект релиза (`./gradlew assembleDistribution`) и архив исходников
(`./gradlew sourceTree`) они не входят; список документов релиза — `releaseDocs` в
[build.gradle](../../build.gradle).

| Файл | Прежний путь | Что это |
|---|---|---|
| [VALIDATION.md](VALIDATION.md) | `VALIDATION.md` | Статус проверок кандидатов 0.2.1, 0.2.2 и roadmap 2 (17 октября 2026); команды `tools/validation` давно удалены |
| [MODDING_PLAN.md](MODDING_PLAN.md) | `MODDING_PLAN.md` | Исходный план реализации (англ.): этапы 0.1 и поселение 0.2 |
| [MODLOG.md](MODLOG.md) | `MODLOG.md` | Журнал разработки 14–17 октября 2026; продолжение — [WORKLOG](../WORKLOG.md) |
| [ROADMAP-0.2.md](ROADMAP-0.2.md) | `docs/ROADMAP.md` | Roadmap 0.2 (пункты 1–10, 17 октября 2026); ссылки на удалённые классы оставлены как есть |
| [STATIC_AUDIT_2026-10-17.md](STATIC_AUDIT_2026-10-17.md) | `docs/STATIC_AUDIT_2026-10-17.md` | Статический аудит автономности: F1–F6, O1–O6 |

## Свидетельства 0.2.2

Остаются на своих местах — на них ссылаются архивные документы и CHANGELOG:

- [docs/release/](../release/0.2.2.md) — комплект кандидата 0.2.2: описание и SHA-256 JAR
  ([0.2.2.md](../release/0.2.2.md)), манифест сборки, итоги серверных и клиентских проверок,
  [REVIEW-FIXES.md](../release/REVIEW-FIXES.md);
- [docs/validation/](../validation/) — отчёты проверок 14–17 октября 2026, по каталогу на прогон;
- [docs/reviews/2026-10-19-external-review.md](../reviews/2026-10-19-external-review.md) — внешнее ревью
  перед 0.3.0-dev.

## Актуальные документы

[README](../../README.md), [CHANGELOG](../../CHANGELOG.md), [PLAN-v0.3](../PLAN-v0.3.md),
[ARCHITECTURE](../ARCHITECTURE.md), [WORKLOG](../WORKLOG.md),
[реестр статического обзора](../reviews/2026-10-20-static-review.md), [soak](../../tools/soak/README.md).

**Известная неточность.** Раздел 0.2.2 в [CHANGELOG.md](../../CHANGELOG.md) («Подробности: …») ещё
ссылается на прежние пути `docs/STATIC_AUDIT_2026-10-17.md` и `docs/ROADMAP.md`; ссылки исправит
обновление документации 0.3.0-dev. Новые пути — в таблице выше.

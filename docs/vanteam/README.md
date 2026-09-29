# VANTEAM BSL Language Server

Форк [1c-syntax/bsl-language-server](https://github.com/1c-syntax/bsl-language-server) для статической
проверки кода 1С:Предприятие 8 в проектах VANTEAM. Цель — собственные диагностики и изменения
производительности, которых нет в upstream, при полной совместимости с исходным форматом отчётов
и конфигурацией `.bsl-language-server.json`.

| | |
|---|---|
| Базовая версия | [v1.0.7](https://github.com/1c-syntax/bsl-language-server/releases/tag/v1.0.7) (`master`, коммит `f377f95ae`) |
| Рабочая ветка | `vanteam-bsl-1.1` |
| Лицензия кода | LGPL-3.0-or-later, как у upstream ([COPYING.md](../../COPYING.md)) |
| Лицензия текстов в `docs/vanteam` | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ru) |
| Статус | изменения в ветке и замерены 29.09.2026, выпусков пока нет |
| Страница проекта | [ivanbokhan84.github.io/bsl-language-server](https://ivanbokhan84.github.io/bsl-language-server/) — графики замеров |

## Зачем форк

BSL Language Server 1.0.7 при каждом запуске заново разбирает синтакс-помощник установленной платформы 1С.
На проверке одного модуля это 83% процессорного времени, а общая стоимость запуска по сравнению с 0.29.0
выросла в 2,6–3,5 раза по CPU и в 1,9–2,4 раза по времени. Штатного кэша между запусками нет, настройкой
его не включить. Подробный разбор с замерами — в статье
[«Быстрее и честнее: ускорение проверки BSL-кода»](bsl-check-optimization.md).

Форк нужен, чтобы:

- сохранять разобранный контекст платформы между запусками (постоянный кэш с проверкой версии платформы,
  JAR и Java);
- в режиме `--analyze` с полным контекстом каталога считать диагностики только для целевых файлов;
- добавлять диагностики под стандарты разработки VANTEAM, которые не подходят для upstream.

Каждое изменение проверяется полной сверкой объектов диагностик с upstream на одном и том же коде:
ускорение не должно менять находки.

## Изменения относительно upstream

| Изменение | Статус |
|---|---|
| Описание форка и статья о стоимости проверки (`docs/vanteam`) | в ветке |
| Защита от повторного разбора синтакс-помощника после `OutOfMemoryError`: без неё при нехватке памяти разбор повторяется для каждого документа и бесконечно занимает CPU | в ветке |
| Отключение отправки ошибок в Sentry проекта upstream в сборках форка | в ветке |
| Постоянный дисковый кэш разобранного синтакс-помощника (`app.platform-context.cache.enabled`, `app.platform-context.cache.path`; ключ — файлы справки, версии движка, bsl-context, Kryo и Java) | в ветке |
| `analyze --target <файл>`: полный контекст каталога, диагностики и метрики только по целевым файлам | в ветке |

Реестр изменений ведётся в этом файле. Каждое изменение — отдельный коммит поверх тега upstream,
поэтому при обновлении базовой версии коммиты переносятся по одному.

## Результаты замеров (29.09.2026)

Форк с тёплым кэшем, AppCDS распакованного JAR и только русской справкой против штатного 1.0.7 на тех же флагах JVM
(`-XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=4`). 7 сценариев, прогрев и 5 парных раундов, новая JVM на запуск:

- CPU ×0,26–0,47, время ×0,30–0,47, пик памяти ×0,53–0,70;
- по времени быстрее 0.29.0 во всех 7 сценариях, по CPU не дороже 0.29.0 в 6 из 7 (форма +6%);
- находки форка с тёплым и холодным кэшем и с `--target` совпадают со штатным 1.0.7 на всех сценариях
  и на двух реальных конфигурациях: 8 051 и 71 460 диагностик, сверка полных JSON-объектов;
- пакетный запуск: постоянная часть у форка около 19 с CPU против 62 с у штатного, каждый следующий модуль — 0,6–0,7 с;
- инвалидация кэша: 8 из 8 шагов по ожиданию (смена платформы, даты файла справки, порча файла кэша).

Таблицы и графики — на [странице проекта](https://ivanbokhan84.github.io/bsl-language-server/).

## Сборка

Требуется JDK 21. Gradle подтягивается через wrapper.

```bash
git clone -b vanteam-bsl-1.1 https://github.com/ivanbokhan84/bsl-language-server.git
cd bsl-language-server
./gradlew bootJar          # Windows: gradlew.bat bootJar
```

Исполняемый JAR — `build/libs/bsl-language-server-<версия>-exec.jar`. Полная сборка с тестами — `./gradlew build`.
Тесты запускаются параллельными JVM по 3 ГБ кучи: на машине с небольшим объёмом памяти
число форков ограничивается свойством `-PmaxParallelForks=2`.

## Использование

Командная строка и формат отчётов совпадают с upstream:

```bash
java -jar bsl-language-server-<версия>-exec.jar --analyze --srcDir <каталог> --reporter json --silent
```

Для коротких CLI-запусков рекомендуем флаги JVM `-XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=4`:
на 1.0.7 они снижают CPU на 56–59% при тех же диагностиках (см. статью, раздел 8).
Документация по настройкам и диагностикам — на [сайте upstream](https://1c-syntax.github.io/bsl-language-server).

## Обновление базовой версии

```bash
git remote add upstream https://github.com/1c-syntax/bsl-language-server.git
git fetch upstream --tags
git rebase --onto vX.Y.Z v1.0.7 vanteam-bsl-1.1
```

После переноса — полная сборка с тестами и сверка диагностик на эталонном корпусе.

## Благодарности

Проект целиком основан на работе команды [1c-syntax](https://github.com/1c-syntax) и участников BSL Language Server.
Изменения общего назначения мы стараемся предлагать в upstream.

---

## English summary

A fork of [BSL Language Server](https://github.com/1c-syntax/bsl-language-server) (LGPL-3.0-or-later) based on
release v1.0.7. Goal: performance and custom diagnostics for 1C:Enterprise projects while keeping the
report format and configuration fully compatible. The main target is a persistent cache of the parsed
1C platform help: in 1.0.7 it is re-parsed on every CLI run and takes ~83% of CPU for a single-module check.
Texts in `docs/vanteam` are licensed under CC BY 4.0.

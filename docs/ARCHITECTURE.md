# Архитектура Lingua Player

Простой MVVM без лишней инфраструктуры: Compose-экраны → ViewModel → репозитории → Room.
Зависимости создаются вручную в `AppContainer` (один экземпляр на приложение).

## Пакеты

```
io.github.linguaplayer
├── LinguaApp.kt, AppContainer.kt   — точка входа и «ручной DI»
├── MainActivity.kt                 — единственная Activity (Compose)
├── util/TextKeys.kt                — нормализация текста (NFC, регистр, пробелы)
├── importer/                       — CSV/XLSX: чтение, сопоставление столбцов, план импорта
├── player/                         — плеер: настройки, очередь, план речи, движок, TTS
├── data/
│   ├── db/                         — Room: сущности, DAO, построитель запросов, БД
│   └── repo/                       — репозитории: записи, теги, плейлисты, языки, настройки
└── ui/
    ├── AppNavigation.kt            — навигация и нижняя панель
    ├── theme/                      — светлая и тёмная тема
    ├── components/                 — общие элементы (строка записи, диалоги)
    ├── home/ library/ playlists/ tags/ settings/ — экраны и их ViewModel
```

Планируемые пакеты следующих этапов:

| Пакет | Этап | Назначение |
|---|---|---|
| `player/service/` | 4 | `MediaSessionService` + собственный `SimpleBasePlayer`, audio focus, Bluetooth |
| `backup/` | 5 | Экспорт CSV/XLSX, резервная копия JSON (версионированный формат) |

## База данных (Room, версия схемы 2)

```
language_pairs (id, source_lang, target_lang, created_at)     UNIQUE(source_lang, target_lang)
entries        (id, pair_id → language_pairs RESTRICT, source, translation, type,
                source_key, translation_key, created_at, updated_at)
tags           (id, name, name_key UNIQUE, system_key UNIQUE NULL, created_at)
entry_tags     (entry_id → entries CASCADE, tag_id → tags CASCADE)          PK(entry_id, tag_id)
playlists      (id, name, kind normal|smart, pair_id NULL → SET NULL,
                smart_type all|word|phrase, smart_tag_mode any|all,
                created_at, updated_at, last_opened_at, cover_path)   ← cover_path: v2
playlist_entries (playlist_id → CASCADE, entry_id → CASCADE, position)      PK(playlist_id, entry_id)
smart_playlist_tags (playlist_id → CASCADE, tag_id → CASCADE)
```

Ключевые решения:

- **Языки — коды BCP 47** (`ko-KR`, `ru-RU`), приводятся к канонической форме через
  `Locale.forLanguageTag`. Пары не зашиты в код: при первом запуске создаётся только `ko-KR → ru-RU`.
- **`source_key` / `translation_key`** — нормализованный текст (Unicode NFC + нижний регистр +
  схлопнутые пробелы). Нужен для поиска без учёта регистра (SQLite `LIKE` не умеет
  регистронезависимо работать с кириллицей) и для поиска дубликатов при импорте. NFC важен для
  корейского: один и тот же слог может прийти как готовый символ или как набор чамо.
- **Системный тег «Не помню»** имеет `system_key = "dont_remember"`. Приложение ищет его
  только по этому ключу, поэтому тег можно переименовать. Удалить его нельзя (проверка и в
  репозитории, и в самом SQL-запросе). Тег создаётся `INSERT OR IGNORE` при каждом открытии БД.
- **Удаление плейлиста** удаляет только строки `playlist_entries` (CASCADE) — записи остаются.
  Удаление записи убирает её из всех плейлистов и снимает теги.
- **Умный плейлист** не хранит записи — содержимое вычисляется тем же `EntryFilter`, что и
  фильтры библиотеки (пара + тип + теги «любой»/«все»).
- **Перечисления** хранятся строковыми кодами (`word`, `phrase`, `smart`…), а не порядковыми
  номерами — безопасно при изменении enum.
- **Миграции**: `MIGRATION_1_2` добавляет `playlists.cover_path`. `MigrationTest` создаёт базу
  v1 строго по `app/schemas/…/1.json`, наполняет данными и открывает текущим Room — Room сверяет
  итоговую схему с ожидаемой. Схемы экспортируются в `app/schemas/`. При изменении сущностей версия БД
  увеличивается и добавляется миграция; `fallbackToDestructiveMigration` не используется.
- **Лимит параметров SQLite** (999 на старых Android) — массовые операции разбиты на порции.

## Импорт

```
Uri (SAF) ──FileLoader──▶ LoadedFile ──(лист, разделитель)──▶ SourceTable (строки ячеек)
                                                              │
       ImportOptions (пара, столбцы, тип, политика дубликатов) ▼
existingKeys(pair) ─────────────────────────────▶ ImportPlanner.plan()  (чистая функция)
                                                              │ ImportPlan: New / Update(id) / Skip / Error
                                              предпросмотр ◀──┤
                                                              ▼
                                       ImportRepository.import(): порции по 500 строк,
                                       каждая — отдельная транзакция Room
```

- `CsvParser` — свой парсер RFC 4180 (кавычки, `""`, переносы строк внутри значений, CRLF,
  BOM, автоопределение разделителя), декодирование UTF-8/UTF-16, запасной вариант Windows-1251.
- `XlsxReader` — XLSX без внешних библиотек: `ZipInputStream` + SAX-парсер, встроенный в Android.
  Поддержаны общие строки, rich text (фонетика `rPh` пропускается), inline-строки, результаты
  формул, разреженные ячейки (`r="C5"`), пропущенные строки. Защита: внешние XML-сущности
  отключены, размер распакованной части ограничен.
- `ImportPlanner` — без Android и БД, поэтому полностью покрыт JVM-тестами. Один и тот же
  план используется для предпросмотра и (пересчитанный заново) для записи.
- Сбой транзакции одной порции помечает её строки как ошибки, остальные порции сохраняются.
- Импорт запускается в `AppContainer.appScope`, а не во `viewModelScope`: закрытие экрана
  не обрывает запись на середине.

## Плеер

```
PlaybackSource (записи + языковые пары) ─┐
PlaybackSettings (DataStore) ────────────┼─▶ QueueBuilder ─▶ List<QueueItem>
                                          │    (тип → порядок → направление)
                                          ▼
                  PlayerEngine (одна корутина на главном потоке)
                  для каждой записи: SpeechPlanner.steps() = [Speak | Silence]…
                                          │ Speak
                                          ▼
                  SpeechEngine ── AndroidTtsEngine (TextToSpeech)
                                └─ FakeSpeech / HoldingSpeech в тестах
```

- **`SpeechPlanner`** — чистая функция «запись + настройки → шаги». Точная последовательность
  задокументирована в коде и README и проверена тестами.
- **`PlayerEngine`** — не знает об Android. Любая команда сначала синхронно отменяет корутину
  воспроизведения и останавливает TTS, ждёт её завершения (`join`) и только потом запускает
  новую — поэтому две фразы не звучат одновременно. Паузы — `delay`, отменяются мгновенно.
  Тесты идут в виртуальном времени: проверяются точные моменты начала фраз и отсутствие наложений.
- **`AndroidTtsEngine`** — `TextToSpeech` не является медиаплеером, поэтому обёрнут в
  suspend-функцию `speak()`: она выставляет язык и скорость (только если изменились),
  вызывает `speak(QUEUE_FLUSH)` и ждёт `onDone/onError` от `UtteranceProgressListener`;
  отмена корутины вызывает `stop()`. Таймаут — на случай, если движок не пришлёт `onDone`.
  Если для языка выбран сетевой голос, а установлен офлайн-голос того же языка — берётся офлайн.
  Аудио — `USAGE_MEDIA / CONTENT_TYPE_SPEECH`.
- Ошибки: движок не запустился → `EngineUnavailable`; язык не поддерживается / данные не
  скачаны → `LanguageUnavailable(lang)` с кнопкой «Настройки голоса»; 3 сбоя подряд → `RepeatedFailures`.
- Плеер живёт в `AppContainer` (один на приложение), поэтому играет при переходах между
  экранами. На этапе 4 он будет обёрнут в `MediaSessionService` + `SimpleBasePlayer`.
- Манифест содержит `<queries>` для `TTS_SERVICE` — без этого на Android 11+ нельзя получить
  список движков.

## Оформление

- `SeedScheme` строит `ColorScheme` (светлую и тёмную) из одного цвета пользователя:
  `primary` сдвигается к чёрному/белому до контраста ≥ 4,5:1 с фоном, контейнеры — смесь с
  фоном. Сам цвет «как есть» (`LocalBrandColor`) — фон обложек плейлистов. `ThemeTest`
  проверяет контраст на 200+ случайных цветах.
- Обложки: `CoverStore` копирует выбранную картинку в `files/covers/`, уменьшая до 1024 px;
  при замене/удалении обложки или плейлиста файл удаляется. (Бэкап этапа 5 будет включать обложки.)

## Поток данных

Экраны подписываются на `Flow` из репозиториев (`collectAsStateWithLifecycle`). Room сам
уведомляет об изменениях таблиц, поэтому список библиотеки, счётчики и плейлисты обновляются
автоматически после любого редактирования. Все операции записи — `suspend`, выполняются вне
главного потока; составные изменения — в транзакциях (`withTransaction`).

## Почему именно такие технические решения

- **AGP 9 со встроенным Kotlin** — отдельный плагин `kotlin-android` больше не нужен.
- **compileSdk 37 / targetSdk 36** — compileSdk 37 требуют свежие библиотеки AndroidX;
  targetSdk оставлен 36, пока поведение фонового воспроизведения не проверено на Android 17
  (будет пересмотрено на этапе 4).
- **Без Hilt/Koin** — для приложения такого размера ручной контейнер проще и понятнее.
- **XLSX без Apache POI** (этап 2) — POI тяжёлый (~10 МБ) и плохо совместим с Android,
  а fastexcel зависит от StAX, которого нет в Android. Формат XLSX — это ZIP с XML, его надёжно
  читает встроенный в Android SAX-парсер, без внешних зависимостей.

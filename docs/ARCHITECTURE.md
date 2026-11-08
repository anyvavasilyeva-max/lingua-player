# Архитектура Lingua Player

Простой MVVM без лишней инфраструктуры: Compose-экраны → ViewModel → репозитории → Room.
Зависимости создаются вручную в `AppContainer` (один экземпляр на приложение).

## Пакеты

```
io.github.linguaplayer
├── LinguaApp.kt, AppContainer.kt   — точка входа и «ручной DI»
├── MainActivity.kt                 — единственная Activity (Compose)
├── util/TextKeys.kt                — нормализация текста (NFC, регистр, пробелы)
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
| `importer/` | 2 | Чтение CSV (свой парсер RFC 4180) и XLSX (ZIP + SAX), сопоставление столбцов, дубликаты |
| `player/queue/` | 3 | Формирование очереди: фильтры, порядок, направление, повторы |
| `player/tts/` | 3 | Обёртка над `TextToSpeech`: инициализация, голоса, `UtteranceProgressListener` |
| `player/engine/` | 3 | Конечный автомат воспроизведения: речь → пауза → речь |
| `player/service/` | 4 | `MediaSessionService` + собственный `SimpleBasePlayer`, audio focus, Bluetooth |
| `backup/` | 5 | Экспорт CSV/XLSX, резервная копия JSON (версионированный формат) |

## База данных (Room, версия схемы 1)

```
language_pairs (id, source_lang, target_lang, created_at)     UNIQUE(source_lang, target_lang)
entries        (id, pair_id → language_pairs RESTRICT, source, translation, type,
                source_key, translation_key, created_at, updated_at)
tags           (id, name, name_key UNIQUE, system_key UNIQUE NULL, created_at)
entry_tags     (entry_id → entries CASCADE, tag_id → tags CASCADE)          PK(entry_id, tag_id)
playlists      (id, name, kind normal|smart, pair_id NULL → SET NULL,
                smart_type all|word|phrase, smart_tag_mode any|all,
                created_at, updated_at, last_opened_at)
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
- **Миграции**: схемы экспортируются в `app/schemas/`. При изменении сущностей версия БД
  увеличивается и добавляется миграция; `fallbackToDestructiveMigration` не используется.
- **Лимит параметров SQLite** (999 на старых Android) — массовые операции разбиты на порции.

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

# Формат резервной копии Lingua Player (версия 1)

Файл `*.lpbackup` — обычный ZIP-архив:

```
backup.json          — все данные (UTF-8, JSON)
covers/<имя>.jpg     — обложки плейлистов (если есть)
```

Создание: **Настройки → Данные → Создать резервную копию**.
Восстановление: **Настройки → Данные → Восстановить из резервной копии** — заменяет *все*
текущие данные (показывается подтверждение с количеством записей в копии).

## backup.json

```json
{
  "format": "lingua-player-backup",
  "version": 1,
  "createdAt": 1762780000000,
  "appVersion": "1.0.0",
  "languagePairs": [ { "id": 1, "sourceLang": "ko-KR", "targetLang": "ru-RU", "createdAt": 0 } ],
  "tags": [
    { "id": 1, "name": "Не помню", "systemKey": "dont_remember", "createdAt": 0 },
    { "id": 2, "name": "кухня", "systemKey": null, "createdAt": 0 }
  ],
  "entries": [
    { "id": 10, "pairId": 1, "source": "포크", "translation": "Вилка", "type": "word",
      "createdAt": 0, "updatedAt": 0, "tagIds": [1, 2] }
  ],
  "playlists": [
    { "id": 5, "name": "Кухня", "kind": "normal", "pairId": 1,
      "smartType": "all", "smartTagMode": "any",
      "createdAt": 0, "updatedAt": 0, "lastOpenedAt": null,
      "cover": "playlist_5.jpg",
      "entryIds": [12, 10, 11],
      "smartTagIds": [] }
  ],
  "settings": {
    "activePairId": 1,
    "seedColor": "#FF00B2D1",
    "playlistsGrid": true,
    "playback": {
      "direction": "source_first", "typeFilter": "all", "order": "sequential",
      "pauseSeconds": 2, "repeats": 1, "sourceRate": 1.0, "targetRate": 1.0,
      "translationEnabled": true, "repeatMode": "off", "slowRepeat": false, "hideAnswer": false
    }
  }
}
```

| Поле | Значения |
|---|---|
| `entries[].type` | `word`, `phrase` |
| `playlists[].kind` | `normal` (записи в `entryIds` в порядке плейлиста), `smart` (содержимое по `smartType` + `smartTagIds` + `smartTagMode`) |
| `smartType` | `all`, `word`, `phrase` |
| `smartTagMode` | `any` (любой из тегов), `all` (все теги) |
| `tags[].systemKey` | `dont_remember` — системный тег «Не помню» (имя может быть любым), иначе `null` |
| `playback.direction` | `source_first`, `target_first`, `random` |
| `playback.order` | `sequential`, `shuffle` |
| `playback.repeatMode` | `off`, `all` (зациклить список), `one` (зациклить запись) |
| `playback.pauseSeconds` | 0, 2, 5, 10 |
| `playback.repeats` | 1, 2, 3 |
| `sourceRate`, `targetRate` | 0.5, 0.75, 1.0, 1.25, 1.5, 2.0 |

Не сохраняются (зависят от устройства): выбранный движок синтеза речи, текущая очередь плеера.
Служебные поля поиска (`source_key`, `name_key`…) не хранятся — пересчитываются при восстановлении.

## Правила восстановления

1. Файл целиком читается и проверяется **до** изменения данных: `format` должен быть
   `lingua-player-backup`; `version` больше поддерживаемой → «копия создана более новой версией».
2. Обложки распаковываются в новые файлы.
3. Замена данных — одна транзакция: при ошибке всё откатывается, текущие данные не меняются.
4. Ссылки на несуществующие объекты отбрасываются (например, тег записи, которого нет в
   `tags`); записи плейлиста другой языковой пары не добавляются.
5. Если в копии нет системного тега «Не помню», он создаётся.

## Версионирование

- Новые **необязательные** поля можно добавлять без смены версии — старые версии приложения
  их игнорируют.
- Несовместимые изменения увеличивают `version`; приложение читает все версии ≤ своей.
- Схема базы данных (Room, `app/schemas/`) от формата копии не зависит.

## Автоматическая копия

Тот же формат `.lpbackup`. Настройки → Данные → «Автоматическая резервная копия»:
- пользователь один раз выбирает файл (системный диалог; подходит Google Диск, «Файлы»,
  Dropbox и т. п.), приложение получает постоянный доступ к нему;
- копия обновляется через 2 минуты после последнего изменения данных и раз в сутки
  (WorkManager, работает и при закрытом приложении);
- файл формируется целиком в памяти и записывается одной операцией; дополнительно 3 последние
  копии хранятся в `files/autobackup/` внутри приложения;
- пустая библиотека (нет ни записей, ни плейлистов) не записывается — защита от затирания
  хорошей копии на новом телефоне.

Системная копия Android (Google) включает базу целиком (вместе с журналом `-wal`), обложки и
настройки (`backup_rules.xml`, `data_extraction_rules.xml`); работает только между Android.

## CSV/XLSX ≠ резервная копия

Экспорт в CSV/XLSX (`source, translation, type, tags`) переносит только слова одной пары —
без плейлистов, их порядка, обложек, умных плейлистов, системных идентификаторов и настроек.
Для переноса на другой телефон используйте `.lpbackup`.

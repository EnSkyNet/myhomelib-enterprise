# Резервно копие и пренасяне на потребителски данни

MyHomeLib Enterprise създава съгласуван SQLite snapshot чрез `VACUUM INTO`; активна WAL база не се копира като обикновен файл. Нормалният backup може да съдържа базата, search index, covers и `user-data.json`.

`user-data.json` е преносим versioned manifest с ratings, progress/reviews, bookmarks, reading history/statistics, groups/favorites, saved searches, unified filters, глобални Reader settings и per-book Reader overrides. Данните за книга се свързват първо по стабилен `LibID`; вътрешният book ID е само fallback за същия каталог.

Restore има два режима:

- **Пълно възстановяване** — staged замяна на SQLite, повторно отваряне на колекцията и последователни Flyway migrations; съвместимо със стари database-only backup-и.
- **Само потребителски данни** — текущата каталогова база и metadata не се заменят; `user-data.json` се прилага по `LibID`. Това е препоръчителният режим след refresh/re-import на INPX.

Stage 22 използва schema version 2 и последователно мигрира предишния v1 manifest. Destructive collection maintenance създава отделен safety backup. Не заменяйте ръчно активната база по време на работа.

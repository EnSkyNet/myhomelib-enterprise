# Резервно копие и пренасяне на потребителски данни

MyHomeLib създава съгласуван SQLite snapshot чрез SQLite Online Backup API, така че активна WAL база не се копира като обикновен файл и не е нужно пълно пренареждане с `VACUUM INTO`. Нормалният backup съдържа snapshot на базата и, когато е включен export на потребителски данни, преносимия файл `user-data.json`. Производните search indexes и cache се преизграждат отделно при нужда.

`user-data.json` е преносим versioned manifest с ratings, progress и reviews, bookmarks, highlights/notes с текстови anchors, reading history/statistics, groups/favorites, saved searches, unified filters, глобални Reader settings и per-book Reader overrides. Записите за книги се свързват първо по стабилен `LibID`; вътрешният book ID е само fallback в рамките на същата колекция.

Restore има два режима:

- **Пълно възстановяване** — staged замяна на SQLite, повторно отваряне на колекцията и последователни Flyway migrations; съвместимо със стари database-only backup-и.
- **Само потребителски данни** — запазва текущата каталогова база и metadata на книгите и прилага `user-data.json` по `LibID`. Това е препоръчителният режим след update или re-import на INPX.

Преносимият формат има собствена schema version и последователна migration поддръжка за по-стари поддържани manifest-и. Destructive collection maintenance създава отделен safety backup. Не заменяйте ръчно файловете на активната база по време на работа.

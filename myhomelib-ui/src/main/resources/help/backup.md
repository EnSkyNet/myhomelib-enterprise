# Резервне копіювання та перенесення даних користувача

MyHomeLib Enterprise створює узгоджений snapshot SQLite через `VACUUM INTO`, тому активну WAL-базу не копіюють як звичайний файл. Звичайний backup може містити базу, пошуковий індекс, обкладинки та `user-data.json`.

`user-data.json` — переносимий versioned manifest. Він містить рейтинги, прогрес і відгуки, bookmarks, reading history/statistics, groups/favorites, saved searches, unified filters, глобальні Reader settings та per-book Reader overrides. Книжкові записи зіставляються насамперед за стабільним `LibID`; внутрішній book ID використовується лише як fallback для тієї самої колекції.

Під час відновлення доступні два режими:

- **Повне відновлення** — staged replacement SQLite з повторним відкриттям колекції та послідовними Flyway migrations; сумісне зі старими backup, де є лише `.db`.
- **Лише дані користувача** — каталогова база та metadata книг не замінюються; `user-data.json` накладається на поточний каталог за `LibID`. Це рекомендований режим після оновлення або повторного імпорту INPX.

Stage 22 використовує schema version 2 і має послідовну міграцію попереднього v1 manifest. Перед destructive collection maintenance створюється окрема safety backup. Не замінюйте файли активної БД вручну під час роботи програми.

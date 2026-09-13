# Continuation after Iteration 31

Iteration 31 реалізує source-level MHL-113 Folder Watcher, але не переводить його в DONE без integration/runtime acceptance.

## Спочатку для закриття MHL-113

1. Надати Maven cache / network для `spring-boot-dependencies:3.5.0` та JavaFX `21.0.2`.
2. Запустити `./mvnw clean verify`, окремо нові `IncomingFolderWatchAdapterTest` і `IncomingFolderImportCoordinatorTest`.
3. Windows smoke: копіювання великого FB2/EPUB у incoming folder, modify burst, rename/move-in, restart під час PROCESSING, duplicate content, collection switch.
4. Перевірити JavaFX settings panel і помилки/повторний scan.
5. Лише після цих gate — розглянути MHL-113 DONE.

## Наступний backlog

**MHL-114 Smart Collections v1**: розвинути наявний SavedSearch у типізований AND/OR rule builder для metadata/progress/rating/year/language/format, save/pin/sort/limit і задокументувати performance target для 500k бібліотеки.

Не починати MHL-114 через дублювання наявного SavedSearch: спочатку інвентаризувати current search/filter ports, query plan і UI navigation contract.

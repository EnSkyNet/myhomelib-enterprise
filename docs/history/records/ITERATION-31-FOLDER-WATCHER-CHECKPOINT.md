# Iteration 31 — MHL-113 Folder Watcher checkpoint

**Дата:** 08.09.2026  
**Статус backlog:** MHL-113 — **В роботі** до повного Maven/JUnit integration та Windows filesystem acceptance.

## Реалізований scope

- persisted incoming-folder configuration per collection;
- `WatchService` для create/modify/delete/overflow;
- debounce та stability gate: size + mtime мають залишатися незмінними протягом налаштованого вікна;
- SHA-256 рахується лише після stability gate;
- durable queue `WAITING / READY / PROCESSING / IMPORTED / DUPLICATE_CONTENT / FAILED / MISSING`;
- atomic `READY -> PROCESSING` claim;
- restart recovery `PROCESSING -> READY`;
- SHA-256 content dedup для вже імпортованого вмісту;
- background import через наявний `ImportFileUseCase`, а не окремий importer;
- import виконується лише для активної collection DB; READY іншої колекції чекає `CollectionOpenedEvent`;
- UI settings у Collection workspace: folder, enable, save, scan now, counters/status;
- delete collection зупиняє source-monitor і incoming-folder watcher.

## Свідомі межі

- scan/watch зараз **non-recursive**;
- формати family `CATALOG` (зокрема INPX) навмисно не auto-import як incoming book, щоб не змішувати інкрементальний ingest із full-snapshot/catalog semantics;
- Windows WatchService/JavaFX runtime acceptance не виконувалося;
- додані JUnit-тести не запускалися, бо Maven model resolution блокується відсутніми offline BOM Spring Boot 3.5.0 та JavaFX 21.0.2.

## Перевірки

- canonical baseline static suite: 76/76 PASS після фінальної правки;
- Java 21 parser: усі source units parse PASS;
- MHL-113 SQLite/filesystem contract smoke: signature change, SHA-256 dedup, restart requeue, FK cascade PASS;
- Maven offline test-compile: BLOCKED до source compilation через dependency model cache.

Докази: `verification/iteration31/`.

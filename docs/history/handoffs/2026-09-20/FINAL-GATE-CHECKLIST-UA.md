# Фінальний gate — запускати тільки після завершення розробки

## Перед запуском

- [ ] Фінальний статичний compile-risk audit завершений.
- [ ] Перевірені imports/constructors/records/Spring wiring/module dependencies.
- [ ] Перевірена нумерація DB migrations та localization keys.
- [ ] Немає відомих незакритих v1 P0/P1 coding tasks, які планується реалізувати до тестів.
- [ ] Tests/fixtures додані або актуалізовані.
- [ ] Offline Maven/JavaFX dependencies доступні.

## Build/test gate

- [ ] clean compile/package всього reactor;
- [ ] unit tests;
- [ ] architecture tests;
- [ ] integration tests;
- [ ] JavaFX/UI gate;
- [ ] tool discovery/version/no-link tests;
- [ ] process timeout/cancel/process-tree/output-limit tests;
- [ ] corrupted input / ZIP bomb / traversal / symlink/junction tests;
- [ ] Unicode Windows paths;
- [ ] low disk / cross-volume filesystem scenarios;
- [ ] Organizer Analyze/Plan/Apply/Undo/recovery після durable journal states;
- [ ] parallel staging fail-fast/cancellation;
- [ ] conversion matrix + fallback policy;
- [ ] Pandoc -> FB2 metadata preservation;
- [ ] EPUB structural + semantic + reader reopen;
- [ ] EPUBCheck STANDARD/STRICT acceptance;
- [ ] lossless PNG optimization equivalence;
- [ ] two supplied FB2 ZIP fixtures;
- [ ] libmobi sample MOBI/AZW fixtures;
- [ ] Pandoc EPUB/DOCX/RTF/TXT fixtures;
- [ ] full MyHomeLib regression.

## Після gate

Створити `FINAL-VERIFICATION-YYYY-MM-DD.md` і зафіксувати фактичні команди, exit codes, test counts, failed/fixed tests, real-fixture results і SHA-256 фінальних release/handoff артефактів.

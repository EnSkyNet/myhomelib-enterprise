# MyHomeLib — Iteration 44 — Reader transition/runtime efficiency

**Дата:** 11.09.2026  
**База:** Iteration 43 runtime-log reliability  
**Правило:** усі source/docs/test зміни завершуються до першого test/compile запуску; після freeze дозволені лише виправлення конкретного gate-дефекту з повтором affected gate.

## 1. Same-book Book -> Reader transition

Runtime log після Iteration 43 показав успішне відкриття великого FB2, а одразу після цього — другий `Fb2StreamingParser` на `ui-bg-*`. Типовий шлях переходу вже мав ту саму книгу в shared `BookDetailsViewModel`, але Reader повторно встановлював інший DTO з тим самим book id. Property listener сприймав це як новий вибір і запускав `BookDetailsAnalysisService`, який повторно парсив файл.

### Зміна
- `BookDetailsViewModel.setCurrentBookIfDifferentId(...)` — окремий API саме для workspace transition.
- Якщо logical book id не змінився, property не перевидається і rich-details analysis не стартує вдруге.
- Звичайний `setCurrentBook(...)` **залишається без dedup**, тому metadata/download/edit refresh того самого id і надалі оновлює details panel.
- `NewReaderWorkspaceController` використовує transition-safe API після успішного open.

## 2. Standard Reader format registry

`ReaderView` і `BookInspectionService` кожен вручну реєстрували FB2/EPUB/TXT/ZIP, через що кожне створення Reader генерувало повторний INFO-блок `Зареєстровано формат...`.

### Зміна
- `DefaultBookFormatRegistry.standard()` повертає незалежний mutable registry, уже preload-нутий стандартними форматами без replay INFO-registration noise.
- Явний `register(...)` для plugin/custom format зберігає попередню семантику й INFO-log.
- `ReaderView` і `BookInspectionService` використовують один canonical standard factory.
- Кожен registry залишається незалежно розширюваним; custom registration в одному екземплярі не просочується в інший.

## 3. Не змінюється

- Archive-entry materialization lifecycle не переписується: Iteration 43 compatibility fallback уже працює, а temp-file ownership потрібен окремий дизайн перед можливим zero-copy reader source.
- Reader/application/infrastructure boundaries не змінюються.
- External Windows/GitHub backlog status не змінюється.
- Source release, як і раніше, не містить Maven/wrapper/dependency JAR.

## Acceptance після freeze

1. `BookDetailsViewModelIdentityUpdateTest`: same id no-op, different id update, normal setter same-id refresh.
2. `ReaderWorkspaceDetailsReuseContractTest`: Reader використовує transition-safe details update.
3. `StandardBookFormatRegistryTest`: 4 built-in formats + independent extensibility.
4. Existing registry locale regression.
5. Reader + UI affected regressions у headless mode.
6. Static/architecture/localization/completeness gates.
7. 13-module offline `test-compile`.
8. Clean Maven-free source staging + ZIP manifest/SHA-256.

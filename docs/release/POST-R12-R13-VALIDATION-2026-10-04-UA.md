# MyHomeLib — V34 External Test R13 validation

**Дата:** 2026-10-04
**База:** V34 External Test R12
**Мета:** закрити підтверджений Windows regression у multi-artifact conversion, відновити вже пошкоджені primary projections, зробити EPUB Book Details fault-tolerant і не дозволяти portable FB2→EPUB мовчки падати у повільний Calibre через відсутній спеціалізований converter.

## 1. Підтверджений дефект: secondary conversion міг стати primary після SQLite round-trip

R2/R3 гарантували, що conversion додає secondary artifact і не промотує його без explicit user action. `ConvertBookUseCase` цю частину виконував правильно: `upsertArtifact(..., false)`.

Незакритим залишився інший шлях. Для post-V50 книг без рядка `book_artifact_preferences` domain hydration обирав implicit preferred artifact із відсортованого списку artifacts. Якщо generated conversion сортувався раніше original, converted EPUB міг стати `Book.file` у пам'яті. Подальша операція могла закріпити цю проєкцію в `books.*`.

Попередній regression test перевіряв лише mutation call з `makePreferred=false`, а не повний SQLite write → read → `BookArtifactHelper` hydration. V63 також ремонтував explicit preference на conversion, але не стан **«preference row відсутній, а books.* уже дрейфував до conversion»**.

### R13 fix

- `SqliteBookCommandRepository.upsertArtifact(..., false)` до вставлення secondary artifact створює durable preference на поточний original/canonical artifact.
- `Book.withArtifacts(...)` без explicit persisted preference більше не виводить generated conversion як primary, якщо існує source/imported artifact.
- Додано real SQLite round-trip regression, де conversion artifact навмисно сортується раніше source.
- Додано V68 `repair_missing_artifact_preferences`: для вже існуючих баз відновлюється original projection і explicit preference; за наявності `conversion.sourceArtifactId` використовується саме записане джерело, інакше — deterministic non-conversion fallback.
- Explicit user preference лишається authoritative: R13 не забороняє свідомо зробити інший artifact preferred.

## 2. EPUB Book Details

EPUB parser існував, але `BookInspectionService` трактував failure повного spine/XHTML parse як failure всього Book Details. Це було надто жорстко для EPUB, у якого валідний OPF metadata може співіснувати з проблемним XHTML/spine.

R13:

- читає `dc:description` у annotation;
- якщо full EPUB document parse падає, повторно читає OPF metadata через `readMetadata(...)`;
- Book Details у такому випадку показує доступні title/authors/language/publisher/year/isbn/annotation та явне warning, а не «не вдалося проаналізувати документ» без корисних даних;
- word/character statistics у metadata-only fallback не вигадуються і лишаються 0.

Додано regression з валідним OPF і навмисно malformed XHTML.

## 3. Чому FB2→EPUB у R12 Portable могло тривати ~16 хвилин

R12 Core portable був спеціально зменшеним пакетом і не містив FBC/fb2cng. Toolchain підтримує direct FBC route `fb2 → epub` і має вищий selection priority, тоді як Calibre є fallback. Без bundled FBC AUTO міг знайти system Calibre і піти повільним external route.

R13 Windows portable contract:

- portable packaging встановлює `MHL_REQUIRE_FBC=1`;
- build fail-closed, якщо bundled `fbc.exe` відсутній;
- static release gate перевіряє цей контракт;
- R13 Core portable handoff включає наданий Windows x64 `tools/converters/fbc-windows-amd64/fbc.exe`.

Це прибирає саме packaging-причину Calibre fallback. Фактичний wall-clock FBC на Windows має бути виміряний на цільовій машині; Linux не використовується як заміна Windows conversion acceptance.

## 4. Targeted regression evidence

На фінальному коді до release freeze пройшли:

- `BookMultiArtifactTest`: **6/6 PASS**;
- `SqlitePreferredArtifactSelectionTest` + `ConversionProjectionRepairV68Test`: **6/6 PASS**;
- `ConvertBookUseCaseTest`: **19/19 PASS**;
- `ConverterSelectionPolicyTest`: **3/3 PASS**;
- `ConverterToolLocatorTest`: **7/7 PASS**;
- `ConversionRoutePlannerTest`: **2/2 PASS**;
- `EpubParserTest`: **8/8 PASS**;
- `BookInspectionServiceTest`: **3/3 PASS**;
- affected reactor: **9/9 SUCCESS**, `BUILD SUCCESS`;
- `tools/static_release_check.py`: **PASS**, schema/migrations через V68.

Windows fat JAR окремо зібрано з `javafx.platform=win`: build **15/15 SUCCESS**. Усередині присутні Windows JavaFX 21.0.12 native JAR-и та V68 migration.

## 5. Root regression

Exact-current R13 root `clean verify` запускався у single-thread і `-T 4` режимах під тим самим JDK 21 / Maven 3.9.16 / supplied offline repo / `C.UTF-8` / Xvfb. Обидва runs були примусово завершені зовнішнім execution limit цього середовища до reactor summary; до моменту завершення інструментом Maven failures/test errors не було.

Тому R13 **не оголошує** новий root `BUILD SUCCESS`. Останній повністю завершений root baseline лишається exact R12: **2088 tests / 0 failures / 0 errors / 23 skipped; 21/21 reactor projects SUCCESS; BUILD SUCCESS**. Змінені R13 модулі покриті targeted RED→GREEN regression evidence з розділу 4. Windows fat-JAR build окремо завершився `BUILD SUCCESS`.

## 6. Acceptance, який все ще потрібен на Windows

На копії реальної R12 `data/`:

1. зробити backup `data/`;
2. запустити R13 і дати Flyway V68 завершити migration;
3. відкрити книгу, яку R12 уже переписав на EPUB, і підтвердити, що primary знову original FB2/FB2-in-ZIP;
4. виконати нову FB2→EPUB conversion і підтвердити: original primary не змінюється, EPUB з'являється secondary artifact;
5. зафіксувати selected converter/provider і elapsed time — для normal R13 Portable очікується bundled FBC, а не Calibre fallback;
6. перевірити Book Details original FB2 та converted EPUB;
7. якщо конкретний EPUB все ще не читається, зберегти саме цей EPUB як external defect corpus.

## 7. Scope discipline

R13 не відкриває V35 scope. Memory tuning R5/R7 не змінено. R12 lazy FB2 image loading збережено. Зміни обмежені regression fix у multi-artifact preference/hydration, V68 repair migration, EPUB metadata fallback та Windows portable FBC packaging contract.

# MyHomeLib — V34 External Test R7 validation

**Дата:** 2026-10-04  
**База:** V34 External Test R6  
**Мета:** перевірити, чи локальна оптимізація використання пам’яті доведена до межі наявного evidence, закрити прогалину в Windows memory/soak evidence contract і не змінювати production memory defaults без representative A/B.

## 1. Висновок memory-аудиту

Нової production memory-зміни в R7 **немає**. Поточні defaults мають виміряне обґрунтування або bounded lifecycle:

- interactive Hikari max pool: **2** — R5 real 707,154-book same-DB A/B дав приблизно **−17.31%** warm-probe RSS проти pool=4 без material import regression;
- SQLite cache: **16 MiB/physical connection** та mmap **64 MiB** — нижчий профіль 8/32 MiB відхилений через material author-query regression;
- `BookCache`: **64 MiB**, weight-bounded;
- `CoverCache`: **64 MiB**, byte-weight-bounded; single cover >16 MiB не утримується в cache;
- Search cache bounded сумарною кількістю cached result IDs; collection switch очищує application caches;
- desktop heap: **`-Xmx1024m`**, G1 default. Історичний production INPX A/B показав, що 512/768 MiB зменшували peak RSS лише приблизно на **5.94% / 4.88%**, але погіршували import приблизно на **6.66% / 6.00%** і summed GC pause time приблизно на **39.05% / 32.79%**; тому менший heap не прийнятий;
- Reader/details lifecycle закриває document/resources, page/image caches та temp resources; large embedded resources у `HybridResourceRepository` переходять у temp storage замість необмеженого RAM retention.

Отже, додаткове зменшення pool/cache/mmap/Xmx без нового target-machine A/B було б speculative tuning, а не evidence-first optimization.

## 2. Локальні memory/resource-release regressions

На exact-current R7 tooling candidate виконано focused suite:

- `DataSourceConfigLeakDetectionTest`: **5/5 PASS**;
- `CollectionScopedCachesTest`: **2/2 PASS**;
- `Fb2StreamingParserTest`: **5/5 PASS**;
- `HybridResourceRepositoryTransparencyTest`: **3/3 PASS**;
- `BookInspectionServiceTest`: **1/1 PASS**.

Разом: **16/16 PASS**, `BUILD SUCCESS`.

Окремий opt-in `PerformanceBaselineTest` на 100,000-book catalogue + 8 MiB Reader fixture. Raw final evidence збережено в `docs/release/PERFORMANCE-R7-100K-MEMORY-2026-10-04.json`:

- FB2 peak heap delta: **32,881,536 B**;
- EPUB peak heap delta: **31,897,024 B**;
- Reader overall peak heap delta: **32,881,536 B**;
- після **20 Reader open/close** retained delta: **224 B** у цьому controlled JVM probe;
- Reader GC collections delta: **59**;
- Lucene 100k peak heap delta: **26,939,632 B**.

Це локальне regression evidence проти очевидного retention leak. Воно **не** є заявою про Windows process Working Set/RSS або production 1–2 h soak.

## 3. Знайдена прогалина R6 memory acceptance

R6 `windows-target-memory-soak.ps1` збирав Working Set, heap, thread/handle count та raw NMT, але не збирав cumulative GC count/time. Final checker також не вимагав GC evidence і не вимагав MEM-10 peak/drift summary.

Це суперечило handoff contract, де target-machine evidence має містити GC behavior, і дозволяло формально отримати memory PASS з неповним набором даних.

## 4. R7 fix — тільки acceptance tooling

Production Java не змінювався. Змінено лише evidence tooling/docs:

- `windows-target-memory-soak.ps1` тепер вимагає `jcmd` + `jstat`;
- кожний MEM checkpoint записує `gcCollectionCount` і `gcTotalTimeMs` разом із Working Set/heap/threads/handles;
- зберігаються raw `jstat -gcutil` та raw `jcmd` outputs;
- MEM-10 додатково містить peak Working Set/heap і first→last delta для Working Set, heap, threads, handles, GC collections та GC time;
- після soak оператор явно ставить `PASS`/`FAIL` і додає decision note; `FAIL` робить memory report FAIL;
- `post-r5-live-acceptance-check.py` fail-closed відхиляє memory evidence без handle/GC metrics або без MEM-10 peak/drift/GC-delta summary;
- regression tests містять negative cases для missing GC evidence та missing MEM-10 peak/drift evidence;
- static release gate контролює нові markers.

NMT лишається best-effort: якщо exact packaged JVM запущена без Native Memory Tracking, raw відповідь фіксується, але основними обов’язковими measurements лишаються Working Set, heap, handles/threads та GC.

## 5. Tooling regressions

Після змін:

- post-R5 live acceptance checker regressions — **PASS**;
- Windows acceptance harness binding regressions — **PASS**;
- static release check — **PASS**.

PowerShell runtime acceptance самого `.ps1` у цьому Linux-середовищі не оголошується виконаним; реальний runner має бути запущений на bound Windows host з JDK 21 tools (`jcmd`, `jstat`).

## 6. Exact-current full regression

Фінальний root offline run під `C.UTF-8` + Xvfb, JDK 21, Maven 3.9.16 та supplied offline repository:

- **2087 tests**;
- **0 failures**;
- **0 errors**;
- **23 skipped**;
- Surefire test/product modules: **20/20**;
- reactor projects: **21/21 SUCCESS**;
- result: **BUILD SUCCESS**;
- total Maven time: **02:52 min**.

Окремий запуск без UTF-8 locale не зараховувався як product regression: Unicode path tests закономірно отримали `InvalidPath ... unmappable characters`. Accepted run повторено в задокументованому baseline environment `C.UTF-8`.

## 7. Чи «все зроблено» з оптимізацією пам’яті

**Локальна evidence-first оптимізація — так, у межах доступного середовища.** Немає підтвердженої локальної причини додатково зменшувати Hikari/SQLite/cache/Xmx або переписувати Reader storage.

**Повний memory acceptance — ще ні**, бо його остання частина принципово platform-specific:

1. target Windows cold startup → Dashboard;
2. 60 s stabilized idle;
3. author 500+;
4. large FB2 Details;
5. Search;
6. Reader;
7. large/full INPX import;
8. post-import idle;
9. Lucene reopen/rebuild;
10. ≥1 h normal production soak (рекомендовано 1–2 h).

На цьому run треба оцінити не лише абсолютні піки, а й first→last drift: Working Set, heap, threads, handles, GC collections/time. Якщо Windows evidence суперечить R5 Linux A/B — пріоритет має target-machine evidence, але будь-яка зміна defaults має проходити same-machine A/B + regression.

## 8. R7 release decision

R7 приймається як **memory-evidence hardening candidate** без product behavior change. Наступний крок — freeze exact Git candidate SHA і виконувати live gates на цьому SHA; не відкривати новий memory tuning без measured Windows evidence.

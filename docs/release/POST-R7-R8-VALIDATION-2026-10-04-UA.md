# MyHomeLib — V34 External Test R8 validation

**Дата:** 2026-10-04  
**База:** V34 External Test R7, frozen base SHA `9d7f1c8c288fe0dc111486677eba2011fb904fc6`  
**Мета:** закрити локальну неузгодженість release evidence, зробити readiness check справді current-candidate-aware і не змінювати production behavior без live defect evidence.

## 1. Знайдений release-evidence defect

R7 final memory JSON (`myhomelib-r7-memory-baseline-100k.json`) і `POST-R6-R7-VALIDATION` містять **224 B** retained heap delta після 20 Reader open/close cycles. `CURRENT-VALIDATION.md` випадково залишив попереднє observational значення **744 bytes**.

Крім того, `external-acceptance-readiness.py` перевіряв `CURRENT-VALIDATION.md` простим пошуком старих токенів `2026-10-02`, `2076 tests`, `22 skipped`. Оскільки CURRENT документ навмисно містить історичні checkpoints, ці токени могли бути знайдені в старому розділі навіть при stale current header. Це був false-green risk у release readiness, а не product runtime defect.

## 2. R8 fix

Production Java, memory defaults і runtime resources **не змінювалися**.

- final R7 memory JSON promoted у source release evidence: `docs/release/PERFORMANCE-R7-100K-MEMORY-2026-10-04.json`;
- `CURRENT-VALIDATION.md`: **744 → 224 bytes**, синхронізовано з final JSON;
- `POST-R6-R7-VALIDATION` тепер прямо посилається на raw JSON;
- `external-acceptance-readiness.py` перевіряє саме current header, поточний `V34 External Test R<n>` revision, `BUILD SUCCESS`/external-gate status, current Maven baseline aggregate і binding до `POST-R<n-1>-R<n>-VALIDATION-*`;
- current baseline aggregate звіряється з detailed evidence document; історичні токени нижче в CURRENT більше не можуть самі задовольнити gate;
- regression test містить negative cases для stale current baseline aggregate та mismatched current evidence revision.

## 3. Exact R7 memory evidence, збережене в source

`docs/release/PERFORMANCE-R7-100K-MEMORY-2026-10-04.json`:

- 100,000-book catalogue;
- FB2 peak heap delta: **32,881,536 B**;
- EPUB peak heap delta: **31,897,024 B**;
- Reader peak heap delta: **32,881,536 B**;
- Reader 20× open/close retained delta: **224 B**;
- Reader GC collections delta: **59**;
- Lucene 100k peak heap delta: **26,939,632 B**.

Це JVM/local regression evidence; воно не замінює Windows Working Set/handles/GC soak.

## 4. Targeted release/readiness regressions

На R8 exact-current tree мають бути green:

- `external-acceptance-readiness-test.py`;
- `external-acceptance-readiness.py --run-regressions`;
- GitHub connected acceptance regression;
- GitHub artifact ingest regression;
- release candidate integrity regression;
- post-R5 live acceptance checker regression;
- Windows harness binding/evidence validator regressions;
- supply-chain policy;
- static release.

Readiness outcome залишається **READY_FOR_LIVE_EVIDENCE**, а MHL-010/011/012/017/018/019 — **OPEN_EXTERNAL**.

## 5. Maven baseline і R8 verification limitation

R8 не змінює Java source, `pom.xml` або runtime resources. Authoritative one-shot Maven baseline тому лишається exact frozen R7 run під `C.UTF-8` + Xvfb, JDK 21, Maven 3.9.16 та supplied offline repository:

- **2087 tests**;
- **0 failures**;
- **0 errors**;
- **23 skipped**;
- Surefire test/product modules: **20/20**;
- reactor projects: **21/21 SUCCESS**;
- result: **BUILD SUCCESS**.

На R8 exact-current tree root `clean verify` запускали повторно (single-thread, `-T 2`, `-T 4`), але локальний execution tool примусово завершує команди після приблизно 5 хвилин; жоден R8 one-shot не дійшов до reactor summary. До termination у логах немає Maven `BUILD FAILURE` або test failure/error. Тому R8 **не** оголошує новий one-shot `BUILD SUCCESS`.

Зміни R8 лежать лише в release docs/evidence та двох Python readiness files; їхні власні regressions, static-release, hygiene, build-check і supply-chain gates виконані окремо й PASS. Це не підміняє майбутній exact-candidate CI run.

## 6. R8 release decision

R8 є **release-evidence consistency candidate** без production behavior change. Memory tuning із R5/R7 не переглядається. Наступний крок — freeze exact R8 Git SHA та виконувати Windows/Kobo/Readwise/PWA/real-comic/target-memory і connected CI/security gates тільки на цьому exact SHA.

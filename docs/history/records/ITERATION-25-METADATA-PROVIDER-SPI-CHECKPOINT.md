# MyHomeLib — Iteration 25 MetadataProvider SPI checkpoint

Дата checkpoint: 07.09.2026

## Scope

Локально завершено MHL-107 — `MetadataProvider SPI`.

MHL-101…MHL-106 залишаються завершеними з попередніх 7.2 checkpoint.

External 7.1 Final items MHL-010/MHL-011/MHL-012/MHL-017/MHL-018/MHL-019 не змінювалися: без фактичного GitHub/Windows consolidated PASS вони залишаються відкритими.

## MHL-107 — vendor-neutral MetadataProvider SPI

Application-level extension point реалізовано в `com.myhomelibcorp.application.metadata` без залежності від конкретного online vendor.

Основні контракти:

- `MetadataProvider` — взаємозамінний provider SPI;
- `MetadataQuery` — пошук за ISBN / title / author з bounded result limit;
- `MetadataCandidate` — нормалізована non-destructive metadata proposal;
- `MetadataSource` — provider/source attribution;
- `MetadataRequestContext` — єдиний cooperative cancel + monotonic deadline contract;
- `MetadataProviderException` + `MetadataProviderErrorKind` — єдиний vendor-neutral error vocabulary;
- `MetadataProviderIssue` — UI-safe failure model без raw vendor quota/header/error payload;
- `MetadataLookupService` — coordinator, ranking, timeout containment і partial-success aggregation.

SPI навмисно не розміщено в `application.port.out`: реальний production adapter з'явиться у MHL-108. Це не створює dead outbound port і не послаблює `implementation-completeness-check`.

## Query/result behavior

Реалізовано:

- ISBN validation/normalization через domain `Isbn`;
- пошук за ISBN, title або author;
- глобальний limit 1…100, default 20;
- confidence у діапазоні 0.0…1.0;
- source attribution: provider id/name + remote record id/url;
- optional title/authors/ISBN/year/publisher/language/annotation/cover URL;
- immutable/deduplicated author list;
- metadata candidates не застосовуються до локальної книги автоматично.

## Timeout / cancel / error / rate-limit policy

Єдиний application contract:

- cooperative cancellation через shared `AtomicBoolean`;
- deadline базується на monotonic `System.nanoTime()`;
- default per-provider timeout — 8 seconds;
- `MetadataLookupService` додає hard `CompletableFuture.orTimeout(...)` fallback навіть для provider, який ігнорує cooperative deadline;
- provider failure не валить aggregate future і не блокує локальні library operations;
- supported failure kinds: CANCELLED, TIMEOUT, RATE_LIMITED, UNAVAILABLE, AUTHENTICATION, INVALID_RESPONSE, FAILED;
- generic `retryAfter` може використовувати coordinator/adapter, але не входить у UI issue model;
- raw provider messages та vendor-specific rate-limit details не віддаються в UI; coordinator формує generic user-safe message.

## Coordinator/ranking behavior

`MetadataLookupService`:

- запускає provider calls через керований `ExecutorPort`, не через ad-hoc executor;
- пропускає disabled providers;
- ізолює timeout/error кожного provider;
- перевіряє, що `candidate.source.providerId` відповідає фактичному provider;
- допускає partial success;
- при user cancellation відкидає partial candidates;
- сортує candidates за confidence descending, далі детерміновано за providerId/recordId;
- застосовує global query limit після aggregation.

## Contract tests

Один parameterized contract suite проганяється для двох mock provider implementations (`mock-a`, `mock-b`) і перевіряє взаємозамінність для ISBN/title/author та спільний cancellation contract.

Окремі coordinator tests перевіряють:

- partial success + RATE_LIMITED failure isolation;
- hard timeout containment;
- cooperative cancellation;
- disabled provider;
- invalid source attribution;
- sanitization provider error message.

Нові MHL-107 tests: **11/11 PASS**.

## Local regression evidence

Фінальний рекомендований reactor-run після MHL-107:

```bash
./mvnw -o \
  -Dmaven.repo.local=/mnt/data/maven-offline-repo/maven-offline-repo \
  -pl myhomelib-application,myhomelib-infrastructure,myhomelib-ui -am test
```

Результат: **BUILD SUCCESS**.

- myhomelib-shared: 12 tests, 0 failures, 0 errors, 0 skipped;
- myhomelib-domain: 12 tests, 0 failures, 0 errors, 0 skipped;
- myhomelib-application: 150 tests, 0 failures, 0 errors, 1 skipped;
- myhomelib-infrastructure: 282 tests, 0 failures, 0 errors, 6 skipped;
- myhomelib-reader: 40 tests, 0 failures, 0 errors, 0 skipped;
- myhomelib-ui: 48 tests, 0 failures, 0 errors, 0 skipped.

Разом у цьому reactor-run: **544 tests, 0 failures, 0 errors, 7 skipped**.

Завершено: `2026-09-07T18:49:45Z`; Maven total time: 56.341 s.

## Final local static gates

Після фінального MHL-107 коду виконано:

- `tools/architecture-check.py` — PASS;
- `tools/managed-executor-check.py` — PASS;
- `tools/static_release_check.py` — PASS;
- `tools/supply-chain-policy-check.py` — PASS;
- `tools/implementation-completeness-check.py` — PASS;
- `tools/ui-function-reachability-check.py` — PASS;
- `tools/user-data-consistency-check.py` — PASS;
- `tools/user-state-search-index-check.py` — PASS.

Static release check: 52 SQLite migrations, 991 Java source files, 213 test source files; XML/FXML/migration integrity issues = 0.

## External 7.1 gate — без змін

Не переводити у `Виконано` без реального evidence:

- MHL-010;
- MHL-011;
- MHL-012;
- MHL-017;
- MHL-018;
- MHL-019.

Для live acceptance джерелом істини лишається Iteration 22 external runbook.

## Наступний 7.2 крок

Наступна залежність backlog — MHL-108 `Open Library provider`, після неї MHL-109 `Google Books provider`.

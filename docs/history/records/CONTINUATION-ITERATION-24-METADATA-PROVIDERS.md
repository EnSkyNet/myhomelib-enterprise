# MyHomeLib — continuation after Iteration 24

Дата handoff: 07.09.2026

## Базовий checkpoint

`ITERATION-24-DUPLICATE-REVIEW-MERGE-CHECKPOINT.md`

## Не змінювати 7.1 external status

MHL-010/MHL-011/MHL-012/MHL-017/MHL-018/MHL-019 залишаються відкритими до фактичного consolidated GitHub + Windows PASS. External flow вести тільки за Iteration 22 live-acceptance runbook.

## Наступна задача — MHL-107 MetadataProvider SPI

Acceptance backlog:

1. application-level provider SPI без залежності від конкретного online vendor;
2. search by ISBN / title / author;
3. provider result має confidence та source attribution;
4. єдиний timeout/cancel/error contract;
5. provider-specific rate-limit metadata не протікає в UI як vendor-specific implementation detail;
6. два mock providers проходять один contract-test suite взаємозамінно;
7. жоден provider failure не блокує локальні library operations.

Рекомендований порядок:

1. value objects / DTO для metadata query + candidate;
2. `MetadataProvider` port у application;
3. coordinator/ranking policy;
4. provider contract tests із двома mock implementations;
5. лише після SPI — MHL-108 Open Library adapter;
6. потім MHL-109 Google Books adapter.

## Regression gates

Після змін мінімально прогнати:

```bash
./mvnw -o -Dmaven.repo.local=/mnt/data/maven-offline-repo \
  -pl myhomelib-application,myhomelib-infrastructure,myhomelib-ui -am test
```

і локальні static gates з Iteration 24.

Не називати GitHub/Windows 7.1 external acceptance PASS без реального live evidence.

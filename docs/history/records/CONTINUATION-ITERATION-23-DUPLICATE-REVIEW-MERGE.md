# MyHomeLib — continuation after Iteration 23

Дата handoff: 07.09.2026

## Базовий checkpoint

`ITERATION-23-MULTI-ARTIFACT-DUPLICATE-FOUNDATION-WIP.md`

## Не змінювати 7.1 external status

MHL-010/MHL-011/MHL-012/MHL-017/MHL-018/MHL-019 не переводити у `Виконано` без фактичного consolidated external PASS. Для цього потоку джерелом істини лишається Iteration 22 live-acceptance runbook.

## Наступна задача — завершити MHL-105 через user-visible duplicate review

Потрібно:

1. Додати bounded candidate retrieval/use case для fuzzy suggestions, без O(N^2) scan на великій library.
2. Duplicate review має показувати для кожної пропозиції:
   - обидві книги;
   - score;
   - explainable reasons;
   - artifacts/formats/state;
   - явну відсутність auto-merge.
3. Додати UI regression/contract test, який доводить, що score/reasons реально присутні у user-facing view.
4. Після цього MHL-105 можна перевести у `Виконано`.

## MHL-106 — Merge Books UI

Acceptance:

- side-by-side metadata/artifacts;
- користувач явно вибирає survivor/значення;
- user data не губляться;
- physical files не видаляються без окремої explicit опції;
- merge operation undoable;
- E2E/integration test покриває merge -> undo -> original state.

Рекомендована реалізація:

- application merge plan + port;
- один SQLite transaction для logical merge;
- durable merge journal/snapshot для undo;
- artifacts move/consolidation без filesystem delete за замовчуванням;
- union book groups/authors/genres/bookmarks; deterministic reading-progress/history policy;
- після DB commit — search-index refresh окремим контрольованим кроком.

## Обов'язкові regression gates

Після змін:

```bash
./mvnw -o -Dmaven.repo.local=/mnt/data/maven-offline-repo \
  -pl myhomelib-infrastructure,myhomelib-ui -am test
```

Окремо прогнати relevant static/release policy checks. Не називати external 7.1 gates PASS без реального GitHub/Windows evidence.

# MyHomeLib — continuation after Iteration 25

Дата handoff: 07.09.2026

## Базовий checkpoint

`ITERATION-25-METADATA-PROVIDER-SPI-CHECKPOINT.md`

## Не змінювати 7.1 external status

MHL-010/MHL-011/MHL-012/MHL-017/MHL-018/MHL-019 залишаються відкритими до фактичного consolidated GitHub + Windows PASS. External flow вести тільки за Iteration 22 live-acceptance runbook.

## Наступна задача — MHL-108 Open Library provider

MHL-107 вже створив application contract:

- `MetadataProvider`;
- `MetadataQuery`;
- `MetadataCandidate` + `MetadataSource`;
- `MetadataRequestContext`;
- `MetadataProviderException` / `MetadataProviderErrorKind`;
- `MetadataLookupService`.

MHL-108 має реалізувати production infrastructure adapter для Open Library без зміни vendor-neutral application API.

## Acceptance MHL-108

1. production `OpenLibraryMetadataProvider` реалізує `MetadataProvider`;
2. підтримує lookup by ISBN / title / author;
3. мапить Open Library response у `MetadataCandidate` з коректним source attribution;
4. повертає title/authors/year/ISBN/publisher/language/annotation/cover URL лише коли поля реально доступні;
5. confidence визначається локально та детерміновано, без видавання приблизного score за факт;
6. HTTP timeout/cancellation узгоджені з `MetadataRequestContext`;
7. HTTP/JSON/rate-limit failures мапляться у vendor-neutral `MetadataProviderErrorKind`;
8. raw Open Library quota/header/error payload не протікає у UI;
9. provider failure не блокує локальний каталог і не руйнує partial success інших providers;
10. adapter має contract/integration tests з локальним mock HTTP server — без залежності test suite від live Internet;
11. static gates та рекомендований application/infrastructure/UI reactor залишаються PASS.

## Реалізаційний порядок

1. перевірити наявний HTTP/JSON infrastructure stack і не додавати нову dependency без потреби;
2. додати Open Library configuration/enable flag;
3. реалізувати endpoint/query mapping для ISBN/title/author;
4. реалізувати response DTO/parser + normalized candidate mapping;
5. додати timeout/cancel/error/rate-limit mapping;
6. додати deterministic confidence policy;
7. додати local HTTP contract tests;
8. перевірити Spring wiring: provider автоматично входить у `List<MetadataProvider>`;
9. прогнати regression + static gates;
10. лише після MHL-108 переходити до MHL-109 Google Books provider.

## Regression gates

```bash
./mvnw -o \
  -Dmaven.repo.local=/mnt/data/maven-offline-repo/maven-offline-repo \
  -pl myhomelib-application,myhomelib-infrastructure,myhomelib-ui -am test
```

Також повторити static gates з Iteration 25.

Не називати GitHub/Windows 7.1 external acceptance PASS без реального live evidence.

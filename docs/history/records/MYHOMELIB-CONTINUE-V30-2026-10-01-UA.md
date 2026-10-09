# MyHomeLib — продовження з V30

**Дата handoff:** 2026-10-01  
**База:** `myhomelib-enterprise-v29-final-checkpoint-2026-10-01.zip`  
**Статус бази:** V29 локально завершений; full offline Linux/Xvfb `clean verify` green. Real-browser V29 та Windows/live/connected acceptance залишаються зовнішніми gates.

## Головна задача наступного checkpoint

Реалізувати **тільки V30: Kobo native sync**. Не починати V31 auto-update в тому самому checkpoint.

## Незмінні правила

1. Спочатку protocol research/probe та characterization чинних device/progress/export boundaries.
2. Kobo-specific logic не вбудовувати в `ExportToDeviceUseCase`.
3. Використовувати наявні Application/Infrastructure/UI boundaries; не створювати generic integration platform.
4. Book identity: stable MyHomeLib book ID + artifact/content fingerprint; ніякого title-based matching.
5. Device identity: opaque generated id; secrets/credentials тільки через `SecretStore`.
6. Reuse чинного artifact selection і converter toolchain; не створювати окремий converter stack.
7. Kobo ↔ MyHomeLib progress mapping — окремий pure translator з deterministic tests.
8. Старіший progress не може перезаписувати новіший; raw timestamps самі по собі не є достатньою conflict authority без cursor/version logic.
9. Network/protocol adapter має fake/local deterministic fixtures; live acceptance на реальному Kobo — окремий gate.
10. Нові user actions повинні потрапити в UI action inventory, behavioral registry, user-functions docs і UK/EN/BG help/localization.

## V30 Application contracts

Орієнтовно:
- `KoboSyncService`;
- `KoboSyncPort`;
- DTO/contracts для:
  - device registration;
  - library projection;
  - metadata/content revision;
  - reading progress;
  - sync cursor/checkpoint.

## Infrastructure

- Kobo protocol adapter лише після protocol probe.
- Authentication/device credential handling через `SecretStore`.
- Incremental sync cursor/checkpoint.
- Book add/update/remove projection.
- Unchanged artifact не retransmit-ити.
- Conversion EPUB/KEPUB — тільки якщо protocol/client compatibility реально цього потребує; reuse existing converter toolchain.
- Retry тільки для safe/idempotent operations.

## UI

Integration Center → Kobo Sync:
- enable/disable;
- device status;
- regenerate/remove credentials;
- last sync/error;
- optional `Sync now`.

## Обов'язкові tests після production implementation

- protocol fixtures;
- authentication;
- incremental cursor;
- book add/update/remove;
- unchanged book no retransmit;
- conversion failure;
- partial sync;
- retry idempotency;
- progress both directions;
- stale progress rejection;
- secret redaction;
- device revoke;
- action inventory + action-specific behavioral coverage;
- UK/EN/BG localization/help;
- architecture/static/supply-chain gates;
- full Maven `clean verify`.

## Live acceptance — обов'язково окремо на реальному Kobo

- first sync;
- library appearance;
- open/read;
- progress → MyHomeLib;
- MyHomeLib progress → Kobo;
- cover/metadata;
- reconnect;
- device revoke.

## Definition of Done V30

- Kobo boundary вузький і не ламає generic device export.
- Stable identity/fingerprint mapping без title guessing.
- Incremental cursor deterministic/idempotent.
- Progress mapping tested обома напрямками; stale update не перемагає новіший canonical state.
- Secrets ніколи не потрапляють у SQLite/plain JSON/logs.
- 100% нових user actions мають behavioral evidence.
- Немає нових architecture violations.
- Full local clean verify green.
- Clean source ZIP + docs/history/next-tasks + SHA-256.
- Реальний Kobo acceptance задокументований окремо; local fake tests не видаються за live acceptance.

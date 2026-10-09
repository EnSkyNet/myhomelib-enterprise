# MyHomeLib — next tasks після V29

**Дата:** 2026-10-01  
**Статус V29:** локально завершений; full reactor green.  
**Наступний product checkpoint:** **V30 — Kobo native sync**.

## Перед стартом V30

1. Не змінювати V29 PWA/offline contract у рамках Kobo без окремого regression reason.
2. Провести protocol research/probe Kobo перед реалізацією transport adapter.
3. Не вбудовувати Kobo-specific logic у `ExportToDeviceUseCase`.
4. Не вигадувати title-based mapping: book identity = stable MyHomeLib book ID + artifact/content fingerprint.
5. Device credentials/secrets — тільки `SecretStore`.
6. Progress mapping — окремий pure translator з deterministic conflict/version policy.
7. Live real-Kobo acceptance є окремим gate і не може бути замінений fake/local tests.

## Зовнішні gates, що залишаються окремими

- V29 real-browser install/offline/reconnect acceptance.
- V26 Windows x64 Calibre/batch/process teardown/file-lock acceptance.
- installer/portable/upgrade/DPI/DPAPI.
- candidate CI/SBOM/SCA/CodeQL/signing.
- live integrations/platform devices.

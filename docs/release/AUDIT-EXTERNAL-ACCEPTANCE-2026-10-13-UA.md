# Зовнішні перевірки, стан 2026-10-13

Усі наведені пункти — **OPEN_EXTERNAL** до отримання доказів на відповідній системі. Конфігурація, сценарій або локальний fixture не означають проходження live acceptance.

| Перевірка | Необхідні докази |
|---|---|
| Windows/macOS root build | Точний кандидат, JDK 21, `-Pproduction clean verify`, повні Surefire/Failsafe звіти без виключення несправних тестів |
| Бібліотека користувача | Повтор першої проблемної книги на копії; operation ID, етап і виняток; результат помилки в UI; збережені оригінали; огляд recovery перед повторним запуском |
| Calibre cancellation | Реальний Windows Calibre; скасування; немає дочірніх процесів; узгоджений recovery journal |
| Installer/portable/DPI | Install/upgrade/uninstall; не-ASCII шляхи; DPI 100/150/200%; existing Windows acceptance harness для точного кандидата |
| DPAPI | Реальний user/session; integration tests, restart/read і migration; без секретів у журналі |
| Kobo/WebDAV/Readwise | Фізичний пристрій або відповідні акаунти; двобічний прогрес, reconnect/revoke; реальні відповіді сервісів |
| RAM/UI soak | 1–2 години на цільовому ПК: heap/RSS, великі архіви, повторні операції, чутливість UI. Warm Linux probe не замінює soak |
| SCA/CodeQL | Живі бази, точний SHA, обидва SBOM, scan із 40 JAR-хешами та image-хешами; окремий native review |
| Ліцензія/підписи | Рішення власника щодо ліцензії; provenance native tool; справжні підписи інсталяторів і оновлень |
| GitHub required checks | Власник вмикає source-integrity, тести та security jobs у захисті гілки. Зміна YAML не змінює адміністративних правил |

Використовуйте [загальний runbook](EXTERNAL-ACCEPTANCE-RUNBOOK.md) і [Windows/live runbook](POST-R5-LIVE-ACCEPTANCE-RUNBOOK-UA.md). Записуйте UTC-час, середовище, SHA кандидата, команди, результати та пропуски. Приватні назви книг, шляхи й секрети не включайте у відкритий проєкт.

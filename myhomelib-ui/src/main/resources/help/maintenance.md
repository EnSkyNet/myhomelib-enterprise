# Обслуговування колекції

AutoUpdater стежить за конкретним source INPX/ZIP, використовує debounce і fingerprint, тому масові зміни книжкових файлів не створюють event storm.

Cleaner/Repair завжди працює як **Analyze → Preview/Dry run → Apply → Report**. Перед database repair створюється backup. Orphan files не видаляються автоматично.

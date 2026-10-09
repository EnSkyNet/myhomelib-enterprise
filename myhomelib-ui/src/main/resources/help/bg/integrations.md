# Интеграции и Kobo Sync

Integration Center обединява външните интеграции на MyHomeLib. Разделът **Kobo Sync** предоставя нативна синхронизация със съвместим Kobo през OPDS sidecar и е отделен от USB Export / Send to Device.

## Kobo Sync

1. Активирайте Kobo Sync. MyHomeLib създава непрозрачен device ID и показва credential само веднъж. Secret се съхранява единствено чрез системния SecretStore и не се записва в базата или plain конфигурация.
2. Насочете Kobo към endpoint-а на вашия MyHomeLib server. Съществуващите TLS/OPDS правила за мрежов достъп остават в сила.
3. **Подготви синхронизацията** обновява локалната projection/cursor. Устройството изтегля incremental промените по време на собствената си синхронизация.
4. EPUB/KEPUB се подават директно. При нужда от конвертиране се използва съществуващият converter toolchain и conversion се стартира само при реално изтегляне от Kobo.
5. Прогресът се синхронизира в двете посоки. По-стар update не може да замени по-нова canonical version.
6. **Отмени устройството** прекратява достъпа и премахва credential от SecretStore.

Live interoperability, first sync, отваряне/четене, cover/metadata, reconnect и revoke трябва да се проверят отделно на реално Kobo.

## Readwise
Integration Center → Readwise съхранява токена чрез защитеното хранилище за тайни. Достъпни са проверка на връзката, експорт на всички анотации и изчистване на токена. За текущ selection/filter използвайте Annotation Manager.


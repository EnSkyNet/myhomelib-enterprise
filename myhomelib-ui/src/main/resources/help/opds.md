# OPDS

OPDS працює окремим модулем і за замовчуванням слухає тільки `127.0.0.1`. Доступні authors/series/genres/search/book/download та health endpoint.

Локальний loopback-режим може працювати через HTTP. Будь-який bind поза loopback дозволено запускати тільки через TLS/HTTPS; незахищений LAN-режим блокується сервером.

У вікні **OPDS сервер** можна ввімкнути HTTPS, створити керований self-signed сертифікат або імпортувати X.509 certificate PEM разом з unencrypted PKCS#8 private key PEM. Для сертифіката показуються subject, строк дії та SHA-256 fingerprint. Після перегенерації fingerprint змінюється, тому довіру на клієнтських пристроях потрібно налаштувати повторно.

Self-signed сертифікат не є автоматично довіреним. Перед додаванням довіри на телефоні/ридері звірте SHA-256 fingerprint з тим, що показує MyHomeLib. Керований PKCS12 зберігається в каталозі конфігурації; його пароль записується в application settings лише у зашифрованому `mhlenc:v1` envelope. Системна властивість `myhomelib.opds.tls.keyStorePassword` та `MYHOMELIB_OPDS_TLS_KEYSTORE_PASSWORD` лишаються fallback для вручну налаштованих keystore.

Basic Auth підтримує throttling невдалих спроб. Сервер також обмежує backlog та кількість одночасно оброблюваних запитів. За замовчуванням `/health` публічний на loopback, а при LAN exposure потребує Basic Auth; якщо Basic Auth вимкнено, LAN health endpoint повертає 403.

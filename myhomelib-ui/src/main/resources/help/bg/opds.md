# OPDS

OPDS работи като отделен модул и по подразбиране слуша само `127.0.0.1`. Достъпни са страницирани канали за автори, серии, жанрове, търсене и книги, локално изтегляне, OPDS 2.0 изгледи за колекции/групи/любими/продължаване на четенето и `/health`, като съвместимостта с OPDS 1.x е запазена.

Loopback може да използва HTTP. Bind извън loopback се стартира само с TLS/HTTPS; незащитен LAN режим се блокира. В прозореца **OPDS сървър** може да се създаде управляван self-signed сертификат или да се импортира X.509 PEM сертификат заедно с некриптиран PKCS#8 PEM private key. Показват се subject, срокът на валидност и SHA-256 fingerprint. При регенериране fingerprint се променя и доверието на клиентските устройства трябва да се настрои отново.

Self-signed сертификатът не е автоматично доверен: сравнете SHA-256 fingerprint с показания в MyHomeLib, преди да го добавите като доверен на телефон или четец. Управляваният PKCS12 се пази в конфигурационната директория, а паролата му — само в криптирания `mhlenc:v1` envelope. `myhomelib.opds.tls.keyStorePassword` и `MYHOMELIB_OPDS_TLS_KEYSTORE_PASSWORD` остават fallback за ръчно управлявани keystore.

Може да се включи Basic Auth или да се създават/отменят scoped Bearer tokens с отделни права за четене на каталога и изтегляне. Basic Auth има throttling на неуспешни опити; сървърът ограничава backlog и едновременно обработваните заявки. `/health` е публичен на loopback по подразбиране, но при LAN достъп изисква Basic Auth или връща HTTP 403, ако Basic Auth е изключен.

## Web Library PWA и офлайн четене

Библиотеката `/web/` има инсталируем PWA shell. Loopback HTTP може да използва browser secure-context изключението; LAN достъпът не отслабва съществуващата политика и продължава да изисква HTTPS/TLS.

Статичният app shell, manifest, иконите и offline fallback не съдържат библиотечни данни или credentials. HTML/API отговорите с потребителски данни не се кешират автоматично. Книга влиза в browser Cache Storage само след изричното действие **Save for offline reading**; **Remove offline copy** изтрива кешираното копие, а **Clear all offline copies** премахва всички изрично запазени книги, без да изтрива app shell. Запазват се най-много 20 completed offline-book caches; над лимита се премахва най-старият. Сървърът създава sanitized versioned Web Reader manifest, без да се добавя EPUB/FB2 parser в браузъра.

Офлайн прогресът се пази в IndexedDB queue с монотонна последователност и timestamp и се изпраща след reconnect. Сървърът не заменя по-нов canonical progress с по-стар queued update; точно повторение на същата позиция е idempotent.

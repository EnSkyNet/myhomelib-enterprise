# OPDS

OPDS е отделен модул и по подразбиране слуша само `127.0.0.1`. Loopback режимът може да използва HTTP, но bind извън loopback се стартира само с TLS/HTTPS; незащитен LAN режим се блокира.

В прозореца **OPDS сървър** може да се включи HTTPS, да се създаде управляван self-signed сертификат или да се импортира X.509 certificate PEM заедно с некриптиран PKCS#8 private-key PEM. Показват се subject, срокът на валидност и SHA-256 fingerprint. При регенериране fingerprint се променя и доверието на клиентските устройства трябва да се настрои отново.

Self-signed сертификатът не е автоматично доверен. Преди да го добавите като доверен на телефон или четец, сравнете SHA-256 fingerprint с показания в MyHomeLib. Управляваният PKCS12 се пази в конфигурационната директория, а паролата му се записва в application settings само в криптирания `mhlenc:v1` envelope. `myhomelib.opds.tls.keyStorePassword` и `MYHOMELIB_OPDS_TLS_KEYSTORE_PASSWORD` остават fallback за ръчно управлявани keystore.

Basic Auth има throttling на неуспешни опити, а сървърът ограничава backlog и едновременно обработваните заявки. `/health` е публичен на loopback; при LAN exposure изисква Basic Auth или връща 403, ако Basic Auth е изключен.

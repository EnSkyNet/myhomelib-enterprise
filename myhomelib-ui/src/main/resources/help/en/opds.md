# OPDS

OPDS runs as a separate module and binds to `127.0.0.1` by default. It exposes paged author, series, genre, search and book feeds, local book downloads, OPDS 2.0 views for collections/groups/favorites/continue-reading and a `/health` probe while retaining OPDS 1.x compatibility.

Loopback may use HTTP. Any bind beyond loopback starts only with TLS/HTTPS; plaintext LAN exposure is rejected. The **OPDS server** dialog can create a managed self-signed certificate or import an X.509 PEM certificate together with an unencrypted PKCS#8 PEM private key. The UI shows subject, validity and SHA-256 fingerprint. Regenerating the certificate changes that fingerprint, so client trust must be configured again.

A self-signed certificate is not trusted automatically: compare its SHA-256 fingerprint with MyHomeLib before trusting it on a phone or reader. The managed PKCS12 is stored in the application configuration directory and its password is persisted only inside the encrypted `mhlenc:v1` envelope. `myhomelib.opds.tls.keyStorePassword` and `MYHOMELIB_OPDS_TLS_KEYSTORE_PASSWORD` remain fallback options for manually managed keystores.

You can enable Basic Auth or create/revoke scoped Bearer tokens with separate catalog-read and download permissions. Basic Auth has failed-attempt throttling; the server also bounds backlog and concurrent request handling. `/health` is public on loopback by default, but on LAN exposure it requires Basic Auth or returns HTTP 403 when Basic Auth is disabled.

## Web Library PWA and offline reading

The `/web/` library has an installable PWA shell. Loopback HTTP can use the browser secure-context exception; LAN access does not weaken the existing policy and still requires HTTPS/TLS.

The static app shell, manifest, icons and offline fallback contain no library data or credentials. User-data HTML/API responses are not cached automatically. A book enters browser Cache Storage only after the explicit **Save for offline reading** action; **Remove offline copy** clears that cached book, while **Clear all offline copies** removes every explicitly saved book without deleting the app shell. At most 20 completed offline-book caches are retained; the oldest is evicted beyond that bound. The server creates a sanitized versioned Web Reader manifest rather than adding an EPUB/FB2 parser to the browser.

Offline progress is held in a browser IndexedDB queue with a monotonic sequence and timestamp, then replayed after reconnect. The server does not overwrite newer canonical progress with an older queued update; an exact replay of the same position is idempotent.

# OPDS

OPDS is a separate module, bound to `127.0.0.1` by default, with paged authors/series/genres/search feeds, book entries, downloads and health.

Loopback may use HTTP. Any bind beyond loopback is allowed to start only with TLS/HTTPS; plaintext LAN exposure is rejected by the server.

The **OPDS server** dialog can enable HTTPS, create a managed self-signed certificate, or import an X.509 certificate PEM together with an unencrypted PKCS#8 private-key PEM. The UI displays the subject, validity interval and SHA-256 fingerprint. Regenerating the certificate changes the fingerprint, so client trust must be configured again.

A self-signed certificate is not trusted automatically. Before trusting it on a phone or reader, compare its SHA-256 fingerprint with the value shown by MyHomeLib. The managed PKCS12 lives in the application configuration directory; its password is persisted only inside the encrypted `mhlenc:v1` envelope. `myhomelib.opds.tls.keyStorePassword` and `MYHOMELIB_OPDS_TLS_KEYSTORE_PASSWORD` remain fallback options for manually managed keystores.

Basic Auth includes per-client failure throttling. The server also bounds listen backlog and concurrently executing requests. `/health` is public on loopback by default; when exposed to LAN it requires Basic Auth, or returns 403 if Basic Auth is disabled.

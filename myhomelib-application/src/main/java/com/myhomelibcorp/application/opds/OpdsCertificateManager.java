package com.myhomelibcorp.application.opds;

import java.nio.file.Path;
import java.util.Optional;

/** Application-facing certificate lifecycle for the embedded OPDS HTTPS endpoint. */
public interface OpdsCertificateManager {
    ManagedCertificate generateSelfSigned(String requestedHost);
    ManagedCertificate importPem(Path certificatePem, Path privateKeyPem);
    Optional<OpdsCertificateInfo> inspect(OpdsTlsSettings tls);

    record ManagedCertificate(OpdsTlsSettings tls, OpdsCertificateInfo certificate) {
        public ManagedCertificate {
            if (tls == null) throw new IllegalArgumentException("tls must not be null");
            if (certificate == null) throw new IllegalArgumentException("certificate must not be null");
        }
    }
}

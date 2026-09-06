package com.myhomelibcorp.application.opds;

import java.time.Instant;

/** Non-secret certificate metadata safe to display in the OPDS settings UI. */
public record OpdsCertificateInfo(
        String fingerprintSha256,
        String subject,
        Instant notBefore,
        Instant notAfter,
        boolean selfSigned) {

    public OpdsCertificateInfo {
        fingerprintSha256 = fingerprintSha256 == null ? "" : fingerprintSha256;
        subject = subject == null ? "" : subject;
    }
}

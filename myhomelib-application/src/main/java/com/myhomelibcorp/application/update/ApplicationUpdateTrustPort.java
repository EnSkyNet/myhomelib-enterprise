package com.myhomelibcorp.application.update;
import java.security.PublicKey;
import java.util.Optional;
public interface ApplicationUpdateTrustPort { Optional<PublicKey> trustedKey(String keyId); }

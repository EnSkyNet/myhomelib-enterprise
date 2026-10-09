package com.myhomelibcorp.infrastructure.update;

import com.myhomelibcorp.application.update.ApplicationUpdateTrustPort;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/** Built-in public verification keys. Private signing material is never part of the application. */
@Component
public final class BundledApplicationUpdateTrustAdapter implements ApplicationUpdateTrustPort {
    private final Map<String, PublicKey> keys;
    public BundledApplicationUpdateTrustAdapter() { this.keys=load(); }
    @Override public Optional<PublicKey> trustedKey(String keyId) { return Optional.ofNullable(keys.get(keyId)); }
    private static Map<String, PublicKey> load() {
        try (InputStream in=BundledApplicationUpdateTrustAdapter.class.getResourceAsStream("/update/trusted-keys.properties")) {
            if(in==null) throw new IllegalStateException("Bundled update trust store is missing");
            Properties p=new Properties(); p.load(in); Map<String,PublicKey> out=new LinkedHashMap<>();
            KeyFactory factory=KeyFactory.getInstance("Ed25519");
            for(String name:p.stringPropertyNames()) {
                String v=p.getProperty(name," ").trim(); if(v.isBlank()) continue;
                out.put(name, factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(v))));
            }
            if(out.isEmpty()) throw new IllegalStateException("Bundled update trust store contains no keys");
            return Map.copyOf(out);
        } catch (Exception e) { throw new IllegalStateException("Cannot load bundled application-update trust store",e); }
    }
}

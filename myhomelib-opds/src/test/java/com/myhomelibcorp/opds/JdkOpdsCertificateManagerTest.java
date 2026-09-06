package com.myhomelibcorp.opds;

import com.myhomelibcorp.application.opds.OpdsCertificateManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdkOpdsCertificateManagerTest {
    @TempDir Path tempDir;

    @AfterEach
    void cleanupProperties() {
        System.clearProperty("myhomelib.dataDir");
    }

    @Test
    void generatesManagedSelfSignedCertificateWithInspectableFingerprint() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.toString());
        JdkOpdsCertificateManager manager = new JdkOpdsCertificateManager();

        OpdsCertificateManager.ManagedCertificate generated = manager.generateSelfSigned("127.0.0.1");

        assertThat(generated.tls().enabled()).isTrue();
        assertThat(generated.tls().keyStoreType()).isEqualTo("PKCS12");
        assertThat(generated.tls().keyStorePassword()).isNotBlank();
        assertThat(Files.isRegularFile(Path.of(generated.tls().keyStorePath()))).isTrue();
        assertThat(generated.certificate().fingerprintSha256()).matches("(?:[0-9A-F]{2}:){31}[0-9A-F]{2}");
        assertThat(generated.certificate().selfSigned()).isTrue();
        assertThat(generated.certificate().notAfter()).isAfter(generated.certificate().notBefore());
        assertThat(manager.inspect(generated.tls())).contains(generated.certificate());

        char[] password = generated.tls().keyStorePassword().toCharArray();
        KeyStore store = KeyStore.getInstance(generated.tls().keyStoreType());
        try (InputStream in = Files.newInputStream(Path.of(generated.tls().keyStorePath()))) {
            store.load(in, password);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
        X509Certificate certificate = (X509Certificate) store.getCertificate(store.aliases().nextElement());
        assertThat(certificate.getSubjectAlternativeNames().toString()).contains("127.0.0.1");
    }

    @Test
    void importsMatchingPemCertificateAndPkcs8PrivateKeyIntoManagedStore() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.toString());
        JdkOpdsCertificateManager manager = new JdkOpdsCertificateManager();
        OpdsCertificateManager.ManagedCertificate generated = manager.generateSelfSigned("localhost");
        Material material = exportPem(generated, tempDir.resolve("leaf.crt"), tempDir.resolve("leaf.key"));

        OpdsCertificateManager.ManagedCertificate imported = manager.importPem(material.cert(), material.key());

        assertThat(imported.certificate().fingerprintSha256()).isEqualTo(generated.certificate().fingerprintSha256());
        assertThat(imported.tls().keyStorePassword()).isNotBlank().isNotEqualTo(generated.tls().keyStorePassword());
        assertThat(manager.inspect(imported.tls())).contains(imported.certificate());
    }

    @Test
    void rejectsInvalidPrivateKeyPemWithClearMessage() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.toString());
        JdkOpdsCertificateManager manager = new JdkOpdsCertificateManager();
        OpdsCertificateManager.ManagedCertificate generated = manager.generateSelfSigned("localhost");
        Material material = exportPem(generated, tempDir.resolve("leaf.crt"), tempDir.resolve("leaf.key"));
        Path badKey = tempDir.resolve("bad.key");
        Files.writeString(badKey, "-----BEGIN PRIVATE KEY-----\nnot-base64\n-----END PRIVATE KEY-----\n");

        assertThatThrownBy(() -> manager.importPem(material.cert(), badKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid Base64");
    }

    @Test
    void rejectsInvalidCertificatePemWithClearMessage() throws Exception {
        System.setProperty("myhomelib.dataDir", tempDir.toString());
        JdkOpdsCertificateManager manager = new JdkOpdsCertificateManager();
        OpdsCertificateManager.ManagedCertificate generated = manager.generateSelfSigned("localhost");
        Material material = exportPem(generated, tempDir.resolve("leaf.crt"), tempDir.resolve("leaf.key"));
        Path badCert = tempDir.resolve("bad.crt");
        Files.writeString(badCert, "-----BEGIN CERTIFICATE-----\nnot-base64\n-----END CERTIFICATE-----\n");

        assertThatThrownBy(() -> manager.importPem(badCert, material.key()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot import OPDS TLS certificate/key");
    }

    private static Material exportPem(OpdsCertificateManager.ManagedCertificate generated, Path certPath, Path keyPath) throws Exception {
        char[] password = generated.tls().keyStorePassword().toCharArray();
        KeyStore store = KeyStore.getInstance(generated.tls().keyStoreType());
        try (InputStream in = Files.newInputStream(Path.of(generated.tls().keyStorePath()))) {
            store.load(in, password);
        }
        String alias = store.aliases().nextElement();
        X509Certificate certificate = (X509Certificate) store.getCertificate(alias);
        PrivateKey privateKey = (PrivateKey) store.getKey(alias, password);
        java.util.Arrays.fill(password, '\0');

        Files.writeString(certPath, pem("CERTIFICATE", certificate.getEncoded()), StandardCharsets.US_ASCII);
        Files.writeString(keyPath, pem("PRIVATE KEY", privateKey.getEncoded()), StandardCharsets.US_ASCII);
        return new Material(certPath, keyPath);
    }

    private static String pem(String type, byte[] bytes) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(bytes);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private record Material(Path cert, Path key) { }
}

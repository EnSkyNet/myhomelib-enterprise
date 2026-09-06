package com.myhomelibcorp.opds;

import com.myhomelibcorp.application.opds.OpdsCertificateInfo;
import com.myhomelibcorp.application.opds.OpdsCertificateManager;
import com.myhomelibcorp.application.opds.OpdsTlsSettings;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** JDK-only certificate lifecycle used by the OPDS settings UI. */
@Component
@Slf4j
public class JdkOpdsCertificateManager implements OpdsCertificateManager {
    private static final String STORE_TYPE = "PKCS12";
    private static final String ALIAS = "myhomelib-opds";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public ManagedCertificate generateSelfSigned(String requestedHost) {
        try {
            Path dir = managedDirectory();
            Files.createDirectories(dir);
            Path target = managedKeyStore();
            Path temp = Files.createTempFile(dir, ".opds-tls-", ".p12");
            String password = randomPassword();
            String host = effectiveHost(requestedHost);

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072, RANDOM);
            KeyPair keyPair = generator.generateKeyPair();
            X509Certificate certificate = createSelfSignedCertificate(keyPair, host);

            char[] chars = password.toCharArray();
            try {
                KeyStore store = KeyStore.getInstance(STORE_TYPE);
                store.load(null, chars);
                store.setKeyEntry(ALIAS, keyPair.getPrivate(), chars, new Certificate[]{certificate});
                try (var out = Files.newOutputStream(temp)) {
                    store.store(out, chars);
                }
            } finally {
                java.util.Arrays.fill(chars, '\0');
            }

            restrictPermissions(temp);
            atomicReplace(temp, target);
            restrictPermissions(target);
            OpdsTlsSettings tls = new OpdsTlsSettings(true, target.toString(), STORE_TYPE, password);
            OpdsCertificateInfo info = inspect(tls)
                    .orElseThrow(() -> new IllegalStateException("Generated TLS certificate cannot be inspected"));
            return new ManagedCertificate(tls, info);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot generate OPDS TLS certificate: " + safeMessage(e), e);
        }
    }

    @Override
    public ManagedCertificate importPem(Path certificatePem, Path privateKeyPem) {
        if (certificatePem == null || !Files.isRegularFile(certificatePem)) {
            throw new IllegalArgumentException("Certificate PEM file does not exist");
        }
        if (privateKeyPem == null || !Files.isRegularFile(privateKeyPem)) {
            throw new IllegalArgumentException("Private-key PEM file does not exist");
        }
        try {
            List<X509Certificate> chain = readCertificates(certificatePem);
            if (chain.isEmpty()) throw new IllegalArgumentException("No X.509 certificate found in PEM file");
            try {
                chain.getFirst().checkValidity();
            } catch (java.security.cert.CertificateExpiredException e) {
                throw new IllegalArgumentException("TLS certificate has expired: " + chain.getFirst().getNotAfter(), e);
            } catch (java.security.cert.CertificateNotYetValidException e) {
                throw new IllegalArgumentException("TLS certificate is not valid yet: " + chain.getFirst().getNotBefore(), e);
            }
            PrivateKey privateKey = readPrivateKey(privateKeyPem);
            verifyPrivateKeyMatchesCertificate(privateKey, chain.getFirst());

            Path dir = managedDirectory();
            Files.createDirectories(dir);
            Path target = managedKeyStore();
            Path temp = Files.createTempFile(dir, ".opds-import-", ".p12");
            String password = randomPassword();
            char[] chars = password.toCharArray();
            try {
                KeyStore store = KeyStore.getInstance(STORE_TYPE);
                store.load(null, chars);
                Certificate[] certificates = chain.toArray(Certificate[]::new);
                store.setKeyEntry(ALIAS, privateKey, chars, certificates);
                try (var out = Files.newOutputStream(temp)) {
                    store.store(out, chars);
                }
            } finally {
                java.util.Arrays.fill(chars, '\0');
            }
            restrictPermissions(temp);
            atomicReplace(temp, target);
            restrictPermissions(target);

            OpdsTlsSettings tls = new OpdsTlsSettings(true, target.toString(), STORE_TYPE, password);
            OpdsCertificateInfo info = inspect(tls)
                    .orElseThrow(() -> new IllegalStateException("Imported TLS certificate cannot be inspected"));
            return new ManagedCertificate(tls, info);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot import OPDS TLS certificate/key: " + safeMessage(e), e);
        }
    }

    @Override
    public Optional<OpdsCertificateInfo> inspect(OpdsTlsSettings tls) {
        if (tls == null || !tls.hasKeyStorePath()) return Optional.empty();
        Path path = Path.of(tls.keyStorePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) return Optional.empty();
        char[] password = resolvePassword(tls).toCharArray();
        try {
            KeyStore store = KeyStore.getInstance(tls.keyStoreType());
            try (InputStream in = Files.newInputStream(path)) {
                store.load(in, password);
            }
            X509Certificate certificate = firstKeyCertificate(store);
            if (certificate == null) return Optional.empty();
            return Optional.of(toInfo(certificate));
        } catch (Exception e) {
            throw new IllegalArgumentException("TLS keystore cannot be opened: " + safeMessage(e), e);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static Path managedDirectory() {
        return AppPaths.configDir().resolve("opds");
    }

    private static Path managedKeyStore() {
        return managedDirectory().resolve("opds-tls.p12");
    }

    private static String randomPassword() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String effectiveHost(String requestedHost) {
        String value = requestedHost == null ? "" : requestedHost.trim();
        if (value.isBlank() || "0.0.0.0".equals(value) || "::".equals(value)) {
            try {
                String host = InetAddress.getLocalHost().getHostName();
                if (host != null && !host.isBlank()) return host;
            } catch (Exception ignored) { }
            return "localhost";
        }
        return value;
    }

    private static X509Certificate createSelfSignedCertificate(KeyPair keyPair, String host) throws Exception {
        Instant notBefore = Instant.now().minusSeconds(300);
        Instant notAfter = notBefore.plusSeconds(825L * 24 * 60 * 60);
        byte[] algorithm = Der.sequence(Der.oid("1.2.840.113549.1.1.11"), Der.nullValue());
        byte[] name = Der.name(sanitizeDn(host));
        byte[] serialBytes = new byte[20];
        RANDOM.nextBytes(serialBytes);
        serialBytes[0] &= 0x7f;
        boolean allZero = true;
        for (byte b : serialBytes) if (b != 0) { allZero = false; break; }
        if (allZero) serialBytes[serialBytes.length - 1] = 1;

        List<SanEntry> sans = subjectAlternativeNames(host);
        List<byte[]> generalNames = new ArrayList<>();
        for (SanEntry san : sans) {
            if (san.ip()) {
                generalNames.add(Der.contextPrimitive(7, InetAddress.getByName(san.value()).getAddress()));
            } else {
                generalNames.add(Der.contextPrimitive(2, san.value().getBytes(StandardCharsets.US_ASCII)));
            }
        }
        byte[] sanExtension = Der.sequence(
                Der.oid("2.5.29.17"),
                Der.octetString(Der.sequence(generalNames.toArray(byte[][]::new))));
        byte[] extensions = Der.contextExplicit(3, Der.sequence(sanExtension));

        byte[] tbs = Der.sequence(
                Der.contextExplicit(0, Der.integer(BigInteger.valueOf(2))),
                Der.integer(new BigInteger(1, serialBytes)),
                algorithm,
                name,
                Der.sequence(Der.time(notBefore), Der.time(notAfter)),
                name,
                keyPair.getPublic().getEncoded(),
                extensions);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate(), RANDOM);
        signer.update(tbs);
        byte[] encoded = Der.sequence(tbs, algorithm, Der.bitString(signer.sign()));

        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate;
        try (InputStream in = new ByteArrayInputStream(encoded)) {
            certificate = (X509Certificate) factory.generateCertificate(in);
        }
        certificate.checkValidity();
        certificate.verify(keyPair.getPublic());
        return certificate;
    }

    private static List<SanEntry> subjectAlternativeNames(String host) {
        java.util.LinkedHashMap<String, SanEntry> names = new java.util.LinkedHashMap<>();
        addSan(names, false, "localhost");
        addSan(names, true, "127.0.0.1");
        if (!"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host)) {
            addSan(names, isIpLiteral(host), host);
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    String literal = address.getHostAddress();
                    int zone = literal.indexOf('%');
                    if (zone >= 0) literal = literal.substring(0, zone);
                    addSan(names, true, literal);
                }
            }
        } catch (Exception ignored) { }
        return List.copyOf(names.values());
    }

    private static void addSan(java.util.Map<String, SanEntry> target, boolean ip, String value) {
        if (value == null || value.isBlank()) return;
        String normalized = value.trim();
        target.put((ip ? "ip:" : "dns:") + normalized.toLowerCase(Locale.ROOT), new SanEntry(ip, normalized));
    }

    private static boolean isIpLiteral(String host) {
        if (host == null || host.isBlank()) return false;
        if (host.contains(":")) return true;
        return host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
    }

    private static String sanitizeDn(String value) {
        String cleaned = value == null ? "localhost" : value.replaceAll("[,=+<>#;\\\\]", "_").trim();
        if (cleaned.isBlank()) cleaned = "localhost";
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private record SanEntry(boolean ip, String value) { }

    /** Minimal DER writer for the small X.509 v3 certificate emitted above. */
    private static final class Der {
        private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);
        private static final DateTimeFormatter GENERALIZED_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

        private Der() { }

        static byte[] sequence(byte[]... elements) { return tagged(0x30, concat(elements)); }
        static byte[] set(byte[]... elements) { return tagged(0x31, concat(elements)); }
        static byte[] integer(BigInteger value) { return tagged(0x02, value.toByteArray()); }
        static byte[] nullValue() { return new byte[]{0x05, 0x00}; }
        static byte[] octetString(byte[] value) { return tagged(0x04, value); }

        static byte[] bitString(byte[] value) {
            byte[] body = new byte[value.length + 1];
            System.arraycopy(value, 0, body, 1, value.length);
            return tagged(0x03, body);
        }

        static byte[] contextExplicit(int number, byte[] value) { return tagged(0xA0 + number, value); }
        static byte[] contextPrimitive(int number, byte[] value) { return tagged(0x80 + number, value); }
        static byte[] utf8(String value) { return tagged(0x0C, value.getBytes(StandardCharsets.UTF_8)); }

        static byte[] name(String commonName) {
            byte[] cn = set(sequence(oid("2.5.4.3"), utf8(commonName)));
            byte[] ou = set(sequence(oid("2.5.4.11"), utf8("MyHomeLib OPDS")));
            byte[] org = set(sequence(oid("2.5.4.10"), utf8("MyHomeLib")));
            return sequence(cn, ou, org);
        }

        static byte[] time(Instant value) {
            int year = value.atZone(ZoneOffset.UTC).getYear();
            String encoded = year >= 1950 && year <= 2049 ? UTC_TIME.format(value) : GENERALIZED_TIME.format(value);
            return tagged(year >= 1950 && year <= 2049 ? 0x17 : 0x18, encoded.getBytes(StandardCharsets.US_ASCII));
        }

        static byte[] oid(String dotted) {
            String[] parts = dotted.split("\\.");
            if (parts.length < 2) throw new IllegalArgumentException("Invalid OID: " + dotted);
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            if (first < 0 || first > 2 || second < 0 || (first < 2 && second > 39)) {
                throw new IllegalArgumentException("Invalid OID: " + dotted);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeBase128(out, 40L * first + second);
            for (int i = 2; i < parts.length; i++) writeBase128(out, Long.parseLong(parts[i]));
            return tagged(0x06, out.toByteArray());
        }

        private static void writeBase128(ByteArrayOutputStream out, long value) {
            if (value < 0) throw new IllegalArgumentException("Negative OID component");
            int count = 1;
            long copy = value;
            while ((copy >>= 7) != 0) count++;
            for (int i = count - 1; i >= 0; i--) {
                int b = (int) ((value >> (7 * i)) & 0x7f);
                if (i != 0) b |= 0x80;
                out.write(b);
            }
        }

        private static byte[] tagged(int tag, byte[] body) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 8);
            out.write(tag);
            writeLength(out, body.length);
            out.writeBytes(body);
            return out.toByteArray();
        }

        private static void writeLength(ByteArrayOutputStream out, int length) {
            if (length < 0x80) {
                out.write(length);
                return;
            }
            int bytes = 0;
            int copy = length;
            while (copy != 0) { bytes++; copy >>>= 8; }
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) out.write((length >>> (8 * i)) & 0xff);
        }

        private static byte[] concat(byte[]... arrays) {
            int length = 0;
            for (byte[] array : arrays) length = Math.addExact(length, array.length);
            byte[] result = new byte[length];
            int offset = 0;
            for (byte[] array : arrays) {
                System.arraycopy(array, 0, result, offset, array.length);
                offset += array.length;
            }
            return result;
        }
    }

    private static List<X509Certificate> readCertificates(Path pem) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(pem)) {
            Collection<? extends Certificate> certificates = factory.generateCertificates(in);
            List<X509Certificate> result = new ArrayList<>();
            for (Certificate certificate : certificates) {
                if (certificate instanceof X509Certificate x509) result.add(x509);
            }
            return result;
        }
    }

    private static PrivateKey readPrivateKey(Path pem) throws Exception {
        String text = Files.readString(pem, StandardCharsets.US_ASCII);
        if (text.contains("BEGIN RSA PRIVATE KEY") || text.contains("BEGIN EC PRIVATE KEY")) {
            throw new IllegalArgumentException("Private key must use unencrypted PKCS#8 PEM (BEGIN PRIVATE KEY)");
        }
        String body = text
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        if (body.isBlank()) throw new IllegalArgumentException("PKCS#8 private key was not found in PEM file");
        byte[] encoded;
        try {
            encoded = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Private-key PEM contains invalid Base64", e);
        }
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(encoded);
        for (String algorithm : List.of("RSA", "EC", "DSA", "Ed25519", "Ed448")) {
            try { return KeyFactory.getInstance(algorithm).generatePrivate(spec); }
            catch (Exception ignored) { }
        }
        throw new IllegalArgumentException("Unsupported PKCS#8 private-key algorithm");
    }

    private static void verifyPrivateKeyMatchesCertificate(PrivateKey key, X509Certificate certificate) throws Exception {
        String signatureAlgorithm = switch (key.getAlgorithm().toUpperCase(Locale.ROOT)) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "DSA" -> "SHA256withDSA";
            case "ED25519" -> "Ed25519";
            case "ED448" -> "Ed448";
            default -> throw new IllegalArgumentException("Unsupported private-key algorithm: " + key.getAlgorithm());
        };
        byte[] challenge = new byte[32];
        RANDOM.nextBytes(challenge);
        Signature signer = Signature.getInstance(signatureAlgorithm);
        signer.initSign(key);
        signer.update(challenge);
        byte[] signature = signer.sign();

        Signature verifier = Signature.getInstance(signatureAlgorithm);
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(challenge);
        if (!verifier.verify(signature)) {
            throw new IllegalArgumentException("Private key does not match the certificate public key");
        }
    }

    private static X509Certificate firstKeyCertificate(KeyStore store) throws Exception {
        Enumeration<String> aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (!store.isKeyEntry(alias)) continue;
            Certificate certificate = store.getCertificate(alias);
            if (certificate instanceof X509Certificate x509) return x509;
        }
        return null;
    }

    private static OpdsCertificateInfo toInfo(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder fp = new StringBuilder(digest.length * 3 - 1);
        for (byte b : digest) {
            if (!fp.isEmpty()) fp.append(':');
            fp.append(String.format(Locale.ROOT, "%02X", b & 0xff));
        }
        boolean selfSigned = certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal());
        if (selfSigned) {
            try { certificate.verify(certificate.getPublicKey()); }
            catch (Exception notActuallySelfSigned) { selfSigned = false; }
        }
        return new OpdsCertificateInfo(
                fp.toString(),
                certificate.getSubjectX500Principal().getName(),
                certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant(),
                selfSigned);
    }

    private static String resolvePassword(OpdsTlsSettings tls) {
        if (tls.keyStorePassword() != null && !tls.keyStorePassword().isEmpty()) return tls.keyStorePassword();
        String value = System.getProperty("myhomelib.opds.tls.keyStorePassword", "");
        if (!value.isEmpty()) return value;
        return System.getenv().getOrDefault("MYHOMELIB_OPDS_TLS_KEYSTORE_PASSWORD", "");
    }

    private static void atomicReplace(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }
        } catch (Exception e) {
            log.warn("Cannot restrict OPDS TLS keystore permissions on {}: {}", path, e.getMessage());
        }
    }

    private static String compact(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() > 500 ? text.substring(0, 500) + "…" : text;
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}

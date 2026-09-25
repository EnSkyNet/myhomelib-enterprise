package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.shared.util.EncryptionUtil;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Arrays;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Single network policy for catalog/book HTTP. TLS remains JVM-validated; there is deliberately no trust-all mode. */
public final class OnlineHttpPolicy {
    private static final int MAX_TRUST_STORE_BYTES = 32 * 1024 * 1024;
    private final ApplicationSettingsPort settings;

    public OnlineHttpPolicy(ApplicationSettingsPort settings) { this.settings = settings; }

    public HttpClient create(CookieHandler cookies) {
        int connectSeconds = clamp(settings.getInt("online.connectTimeoutSeconds", 20), 2, 300);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(connectSeconds));
        if (cookies != null) builder.cookieHandler(cookies);
        configureTls(builder);

        String mode = setting("online.proxy.mode", "SYSTEM").trim().toUpperCase(Locale.ROOT);
        switch (mode) {
            case "NONE", "DIRECT" -> { }
            case "HTTP" -> configureHttpProxy(builder);
            case "SYSTEM" -> {
                ProxySelector selector = ProxySelector.getDefault();
                if (selector != null) builder.proxy(selector);
            }
            case "SOCKS" -> throw new IllegalStateException(
                    "SOCKS proxy не підтримується java.net.http.HttpClient у безпечному portable profile; використайте system proxy");
            default -> throw new IllegalArgumentException("Невідомий online.proxy.mode: " + mode);
        }
        return builder.build();
    }

    private void configureTls(HttpClient.Builder builder) {
        String trustStorePath = setting("online.tls.trustStore", "").trim();
        if (trustStorePath.isBlank()) return; // Normal JVM trust validation.

        Path path = Path.of(trustStorePath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)) {
            throw new IllegalStateException("TLS trust store недоступний: " + path);
        }
        String type = setting("online.tls.trustStoreType", inferKeyStoreType(path)).trim();
        if (type.isBlank()) type = KeyStore.getDefaultType();
        String storedPassword = setting("online.tls.trustStorePassword", "");
        char[] password = new char[0];
        try {
            if (storedPassword != null && !storedPassword.isBlank()) {
                password = decryptStoredSecret(storedPassword).toCharArray();
            }
            Path safePath = SafeFilePublication.requireSafeRegularFile(path, "TLS trust store");
            SafeFilePublication.FileSnapshot trustStoreSnapshot = SafeFilePublication.snapshot(
                    safePath, "TLS trust store snapshot");
            if (trustStoreSnapshot.size() > MAX_TRUST_STORE_BYTES) {
                throw new java.io.IOException("TLS trust store exceeds the safety size limit");
            }
            byte[] trustStoreBytes = SafeFilePublication.readOwnedBytes(
                    safePath, trustStoreSnapshot.sha256(), MAX_TRUST_STORE_BYTES, "TLS trust store");
            KeyStore keyStore = KeyStore.getInstance(type);
            try (ByteArrayInputStream input = new ByteArrayInputStream(trustStoreBytes)) {
                keyStore.load(input, password.length == 0 ? null : password);
            } finally {
                Arrays.fill(trustStoreBytes, (byte) 0);
            }
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(keyStore);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, trustManagers.getTrustManagers(), new SecureRandom());
            builder.sslContext(ssl);
        } catch (Exception e) {
            throw new IllegalStateException("Не вдалося завантажити TLS trust store (" + type + ")", e);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String inferKeyStoreType(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".pkcs12")) return "PKCS12";
        if (name.endsWith(".jks")) return "JKS";
        return KeyStore.getDefaultType();
    }

    private void configureHttpProxy(HttpClient.Builder builder) {
        String host = setting("online.proxy.host", "").trim();
        int port = clamp(settings.getInt("online.proxy.port", 8080), 1, 65535);
        if (host.isBlank()) throw new IllegalStateException("HTTP proxy увімкнено, але host не задано");
        builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
        String user = setting("online.proxy.user", "").trim();
        String storedPassword = setting("online.proxy.password", "");
        if (!user.isBlank()) {
            final String password = decryptStoredSecret(storedPassword);
            builder.authenticator(new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY) {
                        return new PasswordAuthentication(user, password.toCharArray());
                    }
                    return null;
                }
            });
        }
    }

    private String decryptStoredSecret(String value) {
        if (value == null || value.isBlank()) return "";
        if (!EncryptionUtil.isEncrypted(value)) {
            throw new IllegalStateException("Proxy password відхилено: plaintext credentials заборонені");
        }
        return EncryptionUtil.decrypt(value);
    }

    public Duration requestTimeout() {
        return Duration.ofSeconds(clamp(settings.getInt("online.readTimeoutSeconds", 120), 5, 7200));
    }

    public String userAgent() {
        String value = setting("online.userAgent", "MyHomeLib Enterprise/8.0.0").trim();
        return value.isBlank() ? "MyHomeLib Enterprise/8.0.0" : value;
    }

    private String setting(String key, String defaultValue) {
        String value = settings.get(key, defaultValue);
        return value == null ? defaultValue : value;
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}

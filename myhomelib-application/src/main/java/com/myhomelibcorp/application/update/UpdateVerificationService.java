package com.myhomelibcorp.application.update;

import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

@Service
public final class UpdateVerificationService {
    private final ApplicationUpdateTrustPort trust;
    public UpdateVerificationService(ApplicationUpdateTrustPort trust) { this.trust = Objects.requireNonNull(trust); }

    public void verifyManifest(ReleaseManifest manifest, String expectedProduct, String currentVersion,
                               String updaterVersion, String platform, String arch, boolean allowDowngrade) {
        Objects.requireNonNull(manifest, "manifest");
        if (!manifest.productId().equals(expectedProduct)) throw new SecurityException("Wrong update product");
        if (!normalize(manifest.platform()).equals(normalize(platform))) throw new SecurityException("Wrong update platform");
        if (!normalize(manifest.arch()).equals(normalize(arch))) throw new SecurityException("Wrong update architecture");
        if (!"https".equalsIgnoreCase(manifest.artifactUri().getScheme())) throw new SecurityException("Update artifact must use HTTPS");
        if (compareVersions(updaterVersion, manifest.minimumUpdaterVersion()) < 0) throw new SecurityException("Updater version is too old");
        int versionOrder = compareVersions(manifest.version(), currentVersion);
        if (versionOrder < 0 && !allowDowngrade) throw new SecurityException("Downgrade is blocked");
        var key = trust.trustedKey(manifest.keyId()).orElseThrow(() -> new SecurityException("Unknown update signing key"));
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(manifest.canonicalSignedPayload());
            if (!verifier.verify(Base64.getDecoder().decode(manifest.signature()))) throw new SecurityException("Invalid update signature");
        } catch (SecurityException e) { throw e; }
        catch (Exception e) { throw new SecurityException("Update signature verification failed", e); }
    }

    public void verifyArtifact(Path artifact, ReleaseManifest manifest) throws Exception {
        if (artifact == null || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(artifact))
            throw new SecurityException("Staged update is not a safe regular file");
        long size = Files.size(artifact);
        if (size != manifest.artifactLength()) throw new SecurityException("Update artifact length mismatch");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(artifact)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            for (int n; (n = in.read(buf)) >= 0;) {
                total += n;
                if (total > manifest.artifactLength()) throw new SecurityException("Update artifact exceeds manifest length");
                if (n > 0) digest.update(buf, 0, n);
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!MessageDigest.isEqual(actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                manifest.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new SecurityException("Update artifact SHA-256 mismatch");
    }

    static int compareVersions(String a, String b) {
        ParsedVersion x = ParsedVersion.parse(a), y = ParsedVersion.parse(b);
        int max = Math.max(x.numbers.length, y.numbers.length);
        for (int i=0;i<max;i++) {
            long xv=i<x.numbers.length?x.numbers[i]:0, yv=i<y.numbers.length?y.numbers[i]:0;
            if (xv != yv) return Long.compare(xv,yv);
        }
        if (x.preRelease.isEmpty() && !y.preRelease.isEmpty()) return 1;
        if (!x.preRelease.isEmpty() && y.preRelease.isEmpty()) return -1;
        return x.preRelease.compareTo(y.preRelease);
    }
    private static String normalize(String v) { return v == null ? "" : v.trim().toLowerCase(Locale.ROOT).replace('_','-'); }
    private record ParsedVersion(long[] numbers, String preRelease) {
        static ParsedVersion parse(String value) {
            String v = value == null ? "0" : value.trim();
            int plus=v.indexOf('+'); if (plus>=0) v=v.substring(0,plus);
            String pre=""; int dash=v.indexOf('-'); if(dash>=0){pre=v.substring(dash+1);v=v.substring(0,dash);}
            String[] p=v.split("\\."); long[] n=new long[p.length];
            for(int i=0;i<p.length;i++){ if(!p[i].matches("\\d+")) throw new IllegalArgumentException("Invalid version: "+value); n[i]=Long.parseLong(p[i]); }
            return new ParsedVersion(n,pre);
        }
    }
}

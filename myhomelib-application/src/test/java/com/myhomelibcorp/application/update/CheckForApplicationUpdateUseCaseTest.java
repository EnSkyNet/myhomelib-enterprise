package com.myhomelibcorp.application.update;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CheckForApplicationUpdateUseCaseTest {
    @Test
    void returnsOnlyVerifiedNewerManifest() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ReleaseManifest newer = signed(pair, "8.1.0");
        ApplicationUpdatePort port = mock(ApplicationUpdatePort.class);
        when(port.fetchManifest(UpdateChannel.STABLE)).thenReturn(Optional.of(newer));
        var useCase = new CheckForApplicationUpdateUseCase(port,
                new UpdateVerificationService(id -> Optional.of(pair.getPublic())));

        var found = useCase.execute(new CheckForApplicationUpdateUseCase.Request(UpdateChannel.STABLE,
                "myhomelib", "8.0.0", "8.0.0", "linux", "x64", false));
        assertThat(found).contains(newer);

        ReleaseManifest same = signed(pair, "8.0.0");
        when(port.fetchManifest(UpdateChannel.STABLE)).thenReturn(Optional.of(same));
        assertThat(useCase.execute(new CheckForApplicationUpdateUseCase.Request(UpdateChannel.STABLE,
                "myhomelib", "8.0.0", "8.0.0", "linux", "x64", false))).isEmpty();
    }

    private static ReleaseManifest signed(KeyPair pair, String version) throws Exception {
        byte[] bytes = "candidate".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        ReleaseManifest unsigned = new ReleaseManifest("myhomelib", version, UpdateChannel.STABLE, "linux", "x64",
                URI.create("https://updates.example.invalid/app.bin"), bytes.length, sha, "AA==", "key", "8.0.0", "notes");
        Signature s = Signature.getInstance("Ed25519"); s.initSign(pair.getPrivate()); s.update(unsigned.canonicalSignedPayload());
        return new ReleaseManifest(unsigned.productId(), unsigned.version(), unsigned.channel(), unsigned.platform(), unsigned.arch(),
                unsigned.artifactUri(), unsigned.artifactLength(), unsigned.sha256(), Base64.getEncoder().encodeToString(s.sign()),
                unsigned.keyId(), unsigned.minimumUpdaterVersion(), unsigned.releaseNotes());
    }
}

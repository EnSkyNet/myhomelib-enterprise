package com.myhomelibcorp.application.update;

import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.Optional;

@Service
public final class CheckForApplicationUpdateUseCase {
    private final ApplicationUpdatePort port;
    private final UpdateVerificationService verification;
    public CheckForApplicationUpdateUseCase(ApplicationUpdatePort port, UpdateVerificationService verification) {
        this.port=Objects.requireNonNull(port); this.verification=Objects.requireNonNull(verification);
    }
    public Optional<ReleaseManifest> execute(Request request) throws Exception {
        Optional<ReleaseManifest> found=port.fetchManifest(request.channel());
        if(found.isEmpty()) return Optional.empty();
        ReleaseManifest manifest=found.get();
        verification.verifyManifest(manifest, request.productId(), request.currentVersion(), request.updaterVersion(),
                request.platform(), request.arch(), request.allowDowngrade());
        if (UpdateVerificationService.compareVersions(manifest.version(), request.currentVersion()) <= 0 && !request.allowDowngrade()) return Optional.empty();
        return Optional.of(manifest);
    }
    public record Request(UpdateChannel channel,String productId,String currentVersion,String updaterVersion,
                          String platform,String arch,boolean allowDowngrade) { public Request { Objects.requireNonNull(channel); } }
}

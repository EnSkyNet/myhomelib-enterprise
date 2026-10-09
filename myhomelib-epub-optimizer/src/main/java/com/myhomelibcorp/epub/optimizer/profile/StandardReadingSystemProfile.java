package com.myhomelibcorp.epub.optimizer.profile;

import java.util.Locale;
import java.util.Set;

public enum StandardReadingSystemProfile implements ReadingSystemProfile {
    GENERIC_EPUB33_COMPAT(EpubTargetStandard.EPUB_3_3, Set.of(ImageCodec.JPEG, ImageCodec.PNG, ImageCodec.GIF, ImageCodec.SVG), false),
    MYHOMELIB_DESKTOP(EpubTargetStandard.EPUB_3_3, Set.of(ImageCodec.JPEG, ImageCodec.PNG, ImageCodec.GIF, ImageCodec.SVG, ImageCodec.WEBP), true),
    MODERN_EPUB33(EpubTargetStandard.EPUB_3_3, Set.of(ImageCodec.JPEG, ImageCodec.PNG, ImageCodec.GIF, ImageCodec.SVG, ImageCodec.WEBP), true),
    KOBO(EpubTargetStandard.EPUB_3_3, Set.of(ImageCodec.JPEG, ImageCodec.PNG, ImageCodec.GIF, ImageCodec.SVG), false),
    POCKETBOOK(EpubTargetStandard.EPUB_3_3, Set.of(ImageCodec.JPEG, ImageCodec.PNG, ImageCodec.GIF, ImageCodec.SVG), false),
    KINDLE_DERIVED(EpubTargetStandard.EPUB_3_3, Set.of(ImageCodec.JPEG, ImageCodec.PNG, ImageCodec.GIF, ImageCodec.SVG), false),
    MODERN_EPUB34_EXPERIMENTAL(EpubTargetStandard.EPUB_3_4,
            Set.of(ImageCodec.JPEG, ImageCodec.PNG, ImageCodec.GIF, ImageCodec.SVG, ImageCodec.WEBP, ImageCodec.AVIF, ImageCodec.JPEG_XL), true);

    private final EpubTargetStandard standard;
    private final Set<ImageCodec> codecs;
    private final boolean woff2;

    StandardReadingSystemProfile(EpubTargetStandard standard, Set<ImageCodec> codecs, boolean woff2) {
        this.standard = standard;
        this.codecs = Set.copyOf(codecs);
        this.woff2 = woff2;
    }

    @Override public String id() { return name().toLowerCase(Locale.ROOT); }
    @Override public EpubTargetStandard targetStandard() { return standard; }
    @Override public Set<ImageCodec> supportedImageCodecs() { return codecs; }
    @Override public boolean supportsWoff2() { return woff2; }
    @Override public int preferredMaxImageWidth() { return 0; }
    @Override public int preferredMaxImageHeight() { return 0; }
    @Override public long preferredMaxResourceBytes() { return 0; }
}

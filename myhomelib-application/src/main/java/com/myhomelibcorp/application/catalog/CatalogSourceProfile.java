package com.myhomelibcorp.application.catalog;

/**
 * Declarative description of one remote catalog family. URLs/file names live here rather than
 * being scattered across use cases and downloader branches.
 */
public record CatalogSourceProfile(
        String sourceType,
        String sourceFormat,
        String baselineUrl,
        String baselineFile,
        String baselineVersionEndpoint,
        String updateUrl,
        String fullUpdateFile,
        String incrementalUpdateFile,
        String fullVersionEndpoint,
        String incrementalVersionEndpoint,
        String authentication,
        String validationStrategy,
        String updateStrategy
) {
    public static final CatalogSourceProfile FLIBUSTA_MHL = new CatalogSourceProfile(
            "flibusta-mhl", "inpx",
            "https://alex80.github.io/mhl/download/inpx/", "flibusta_online_fb2.inpx", "flibusta_online_fb2.info",
            "https://alex80.github.io/mhl/update/", "flibusta_online_fb2.zip", "extra_flibusta_online_fb2.zip",
            "flibusta_online_fb2.info", "extra_flibusta_online_fb2.info",
            "optional-basic", "inpx-zip-with-inp", "mhl-full-plus-extra"
    );
}

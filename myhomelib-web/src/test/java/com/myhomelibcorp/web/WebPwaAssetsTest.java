package com.myhomelibcorp.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WebPwaAssetsTest {
    private final WebPwaAssets assets = new WebPwaAssets();

    @Test void manifestIsInstallableLocalizedAndContainsOnlyPublicShellMetadata() {
        String manifest = assets.manifest("en-US");

        assertThat(manifest)
                .contains("\"name\":", "\"lang\":\"en\"", "\"start_url\":\"/web/\"",
                        "\"scope\":\"/web/\"", "\"display\":\"standalone\"",
                        "\"src\":\"/web/icons/icon-192.png\"", "\"sizes\":\"192x192\"",
                        "\"src\":\"/web/icons/icon-512.png\"", "\"sizes\":\"512x512\"")
                .doesNotContain("token", "Authorization", "credential", "/web/books/");
    }

    @Test void pageHeadUsesVersionedAssetsAndRegistersManifestWithoutInlineBootstrap() {
        assertThat(assets.headTags())
                .contains("rel=\"manifest\" href=\"/web/app.webmanifest\"",
                        "name=\"theme-color\"", WebPwaAssets.CLIENT_JS_PATH,
                        WebPwaAssets.CSS_PATH, "defer")
                .doesNotContain("navigator.serviceWorker.register");
    }

    @Test void clientScriptUsesSecureContextIndexedDbMonotonicQueueAndAllExplicitOfflineActions() {
        String js = assets.clientScript();

        assertThat(js)
                .contains("window.isSecureContext", "localhost", "127.0.0.1", "::1",
                        "indexedDB.open", "clientTimestamp:Date.now()", "clientSequence:seq",
                        "entries.sort((a,b)=>a.clientSequence-b.clientSequence)",
                        "credentials:'same-origin'", "cache:'no-store'", "MessageChannel",
                        "data-pwa-save-book", "SAVE_BOOK",
                        "data-pwa-remove-book", "REMOVE_BOOK",
                        "data-pwa-clear-books", "CLEAR_OFFLINE_BOOKS")
                .doesNotContain("caches.open");
    }

    @Test void serviceWorkerCachesOnlyPublicShellAutomaticallyAndStagesExplicitBooksAtomicallyWithinBound() {
        String sw = assets.serviceWorker();

        assertThat(sw)
                .contains("const V='v29-1'", "SHELL='mhl-shell-'+V", "BOOK_PREFIX='mhl-book-'+V+'-'", "MAX_OFFLINE_BOOKS=20",
                        "SHELL_URLS=['/web/app.webmanifest','/web/offline.html'",
                        "credentials:'omit'", "credentials:'same-origin'", "cache:'no-store'",
                        "h.delete('set-cookie')", "h.delete('authorization')",
                        "SAVE_BOOK", "REMOVE_BOOK", "CLEAR_OFFLINE_BOOKS",
                        "manifest.json", "await caches.delete(name);throw e",
                        "n.startsWith(prefix)&&n!==name", "names.slice(MAX_OFFLINE_BOOKS)",
                        "fetch(req).catch(async()=>await bookCacheMatch(req)||await caches.match(OFFLINE))")
                .doesNotContain("/web/download/");

        String shellDeclaration = sw.substring(sw.indexOf("const SHELL_URLS="), sw.indexOf(";", sw.indexOf("const SHELL_URLS=")));
        assertThat(shellDeclaration).doesNotContain("/web/read/", "/web/books/", "/web/offline/books/");
    }

    @Test void offlineFallbackIsLocalizedAndIconsAreRealPngResources() throws Exception {
        String page = assets.offlinePage("bg");
        byte[] icon192 = assets.icon192();
        byte[] icon512 = assets.icon512();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

        assertThat(page).contains("<html lang=\"bg\"", WebPwaAssets.CLIENT_JS_PATH, WebPwaAssets.CSS_PATH)
                .contains("MyHomeLib");
        assertThat(icon192).hasSizeGreaterThan(8);
        assertThat(icon512).hasSizeGreaterThan(8);
        assertThat(Arrays.copyOf(icon192, 8)).isEqualTo(png);
        assertThat(Arrays.copyOf(icon512, 8)).isEqualTo(png);
        assertThat(new String(icon192, StandardCharsets.ISO_8859_1)).doesNotContain("token");
    }
}

package com.myhomelibcorp.web;

import com.myhomelibcorp.application.webreader.WebReaderChapter;
import com.myhomelibcorp.application.webreader.WebReaderDocument;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Server-rendered basic EPUB/FB2 reader with browser-only presentation preferences. */
public final class WebReaderRenderer {
    private final WebTextCatalog text;

    public WebReaderRenderer() { this(new WebTextCatalog()); }
    public WebReaderRenderer(WebTextCatalog text) { this.text = Objects.requireNonNull(text, "text"); }

    public String render(WebReaderDocument document) { return render(WebTextCatalog.DEFAULT_LANGUAGE, document); }

    public String render(String language, WebReaderDocument document) {
        String lang = text.languageTag(language);
        if (document == null) return error(lang, t(lang, "web.reader.unavailable"));
        if (!document.supported()) return unsupported(lang, document);
        WebReaderChapter chapter = document.currentChapter();
        String id = u(document.bookId());
        StringBuilder toc = new StringBuilder();
        for (WebReaderChapter item : document.chapters()) {
            toc.append("<li><a")
                    .append(item.index() == document.selectedChapter() ? " aria-current=page class=current" : "")
                    .append(" href=\"/web/read/").append(id).append("?chapter=").append(item.index()).append("\">")
                    .append(h(chapterTitle(lang, item))).append("</a></li>");
        }

        StringBuilder article = new StringBuilder();
        for (String paragraph : chapter.text().split("\\n", -1)) {
            if (!paragraph.isBlank()) article.append("<p>").append(h(paragraph)).append("</p>");
        }
        if (article.isEmpty()) article.append("<p class=muted>").append(h(t(lang, "web.reader.chapter_empty"))).append("</p>");

        int previous = Math.max(0, document.selectedChapter() - 1);
        int next = Math.min(document.chapters().size() - 1, document.selectedChapter() + 1);
        String body = "<header><nav><a class=brand href=\"/web/\">MyHomeLib</a>"
                + "<a href=\"/web/books/" + id + "\">" + h(t(lang, "web.reader.about")) + "</a></nav></header>"
                + "<main class=reader-shell>"
                + "<aside><h2>" + h(t(lang, "web.reader.toc")) + "</h2><ol>" + toc + "</ol></aside>"
                + "<section class=reader-panel>"
                + "<div class=toolbar><label>" + h(t(lang, "web.reader.theme")) + " <select id=theme><option value=light>" + h(t(lang, "web.reader.theme.light")) + "</option><option value=sepia>" + h(t(lang, "web.reader.theme.sepia")) + "</option><option value=dark>" + h(t(lang, "web.reader.theme.dark")) + "</option></select></label>"
                + "<label>" + h(t(lang, "web.reader.font")) + " <input id=font type=range min=14 max=28 step=1 value=18></label>"
                + "<span id=progress>" + String.format(java.util.Locale.ROOT, "%.1f%%", document.resumePercent()) + "</span></div>"
                + "<article id=content tabindex=0 data-book=\"" + a(document.bookId()) + "\" data-chapter=\"" + chapter.index()
                + "\" data-start=\"" + chapter.startOffset() + "\" data-end=\"" + chapter.endOffset()
                + "\" data-resume=\"" + document.resumeOffset() + "\" data-resume-chapter=\"" + document.resumeChapter() + "\" data-total=\"" + document.chapters().getLast().endOffset() + "\">"
                + "<h1>" + h(document.title()) + "</h1><h2>" + h(chapterTitle(lang, chapter)) + "</h2>"
                + article + "</article>"
                + "<nav class=chapter-nav>"
                + (document.selectedChapter() > 0 ? "<a href=\"/web/read/" + id + "?chapter=" + previous + "\">" + h(t(lang, "web.reader.previous")) + "</a>" : "<span></span>")
                + (document.selectedChapter() + 1 < document.chapters().size() ? "<a href=\"/web/read/" + id + "?chapter=" + next + "\">" + h(t(lang, "web.reader.next")) + "</a>" : "<span></span>")
                + "</nav></section></main>"
                + script();
        return document(lang, document.title(), body);
    }

    public String notFound() { return notFound(WebTextCatalog.DEFAULT_LANGUAGE); }
    public String notFound(String language) {
        String lang = text.languageTag(language);
        return error(lang, t(lang, "web.book.not_found"));
    }

    private String unsupported(String language, WebReaderDocument document) {
        String id = u(document.bookId());
        String message = document.messageKey().isBlank()
                ? t(language, "web.reader.unsupported_format")
                : t(language, document.messageKey());
        return document(language, document.title(), "<header><nav><a class=brand href=\"/web/\">MyHomeLib</a></nav></header>"
                + "<main><article class=notice><h1>" + h(document.title()) + "</h1><p>" + h(message) + "</p>"
                + "<p><a class=button href=\"/web/download/" + id + "\">" + h(t(language, "web.download.file")) + "</a></p></article></main>");
    }

    private String error(String language, String message) {
        return document(language, t(language, "web.reader.title"), "<main><article class=notice><h1>" + h(t(language, "web.reader.title"))
                + "</h1><p>" + h(message) + "</p><p><a href=\"/web/\">" + h(t(language, "web.library.to")) + "</a></p></article></main>");
    }

    private String chapterTitle(String language, WebReaderChapter chapter) {
        return chapter.title().isBlank() ? t(language, "web.reader.chapter", chapter.index() + 1) : chapter.title();
    }

    private static String script() {
        return "<script>(()=>{const root=document.documentElement,c=document.getElementById('content'),t=document.getElementById('theme'),f=document.getElementById('font'),p=document.getElementById('progress');"
                + "const theme=localStorage.getItem('mhl.web.theme')||'light',font=localStorage.getItem('mhl.web.font')||'18';root.dataset.theme=theme;root.style.setProperty('--reader-font',font+'px');t.value=theme;f.value=font;"
                + "t.onchange=()=>{root.dataset.theme=t.value;localStorage.setItem('mhl.web.theme',t.value)};f.oninput=()=>{root.style.setProperty('--reader-font',f.value+'px');localStorage.setItem('mhl.web.font',f.value)};"
                + "const start=Number(c.dataset.start),end=Number(c.dataset.end),total=Number(c.dataset.total),resume=Number(c.dataset.resume),same=Number(c.dataset.resumeChapter)===Number(c.dataset.chapter);"
                + "if(same&&resume>start&&end>start){requestAnimationFrame(()=>{const ratio=Math.max(0,Math.min(1,(resume-start)/(end-start)));window.scrollTo(0,ratio*Math.max(0,document.documentElement.scrollHeight-innerHeight))})}"
                + "let timer=0;const save=()=>{const max=Math.max(1,document.documentElement.scrollHeight-innerHeight),ratio=Math.max(0,Math.min(1,scrollY/max)),offset=Math.round(start+ratio*Math.max(0,end-start));p.textContent=(100*offset/Math.max(1,total)).toFixed(1)+'%';"
                + "fetch('/web/read/'+encodeURIComponent(c.dataset.book)+'/progress',{method:'POST',credentials:'same-origin',keepalive:true,headers:{'Content-Type':'application/json','X-MyHomeLib-Request':'1'},body:JSON.stringify({chapter:Number(c.dataset.chapter),offset})}).catch(()=>{})};"
                + "addEventListener('scroll',()=>{clearTimeout(timer);timer=setTimeout(save,500)},{passive:true});addEventListener('pagehide',save);})();</script>";
    }

    private String document(String language, String title, String body) {
        return "<!doctype html><html lang=\"" + a(text.languageTag(language)) + "\" data-theme=\"light\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"
                + h(title) + " — MyHomeLib Reader</title><style>"
                + ":root{--reader-font:18px;--bg:#f7f7f8;--panel:#fff;--fg:#171717;--muted:#6b7280;--link:#1d4ed8}:root[data-theme=sepia]{--bg:#efe7d3;--panel:#f8f0dc;--fg:#3c2f22;--muted:#6f5d4b;--link:#7c3f00}:root[data-theme=dark]{--bg:#111827;--panel:#1f2937;--fg:#f3f4f6;--muted:#cbd5e1;--link:#93c5fd}"
                + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px system-ui,sans-serif}header{background:#111827;color:#fff}header nav{max-width:1200px;margin:auto;padding:1rem;display:flex;gap:1rem}.brand{font-weight:800;margin-right:auto}header a{color:#fff}.reader-shell{max-width:1200px;margin:auto;display:grid;grid-template-columns:minmax(210px,280px) 1fr;gap:1rem;padding:1rem}aside,.reader-panel,.notice{background:var(--panel);border:1px solid #9ca3af55;border-radius:.7rem;padding:1rem}aside{position:sticky;top:1rem;align-self:start;max-height:calc(100vh - 2rem);overflow:auto}aside ol{padding-left:1.3rem}.current{font-weight:800}.toolbar{display:flex;gap:1rem;align-items:center;flex-wrap:wrap;border-bottom:1px solid #9ca3af55;padding-bottom:.8rem}.toolbar label{display:flex;gap:.45rem;align-items:center}#progress{margin-left:auto;color:var(--muted)}#content{font:var(--reader-font)/1.72 Georgia,serif;max-width:760px;margin:auto;padding:1rem 0;outline:none}#content p{margin:0 0 1em}.chapter-nav{display:flex;justify-content:space-between;gap:1rem;padding-top:1rem;border-top:1px solid #9ca3af55}a{color:var(--link)}.button{display:inline-block;background:#1d4ed8;color:#fff;padding:.65rem .9rem;border-radius:.45rem;text-decoration:none}.muted{color:var(--muted)}main:not(.reader-shell){max-width:900px;margin:auto;padding:1rem}"
                + "@media(max-width:760px){.reader-shell{grid-template-columns:1fr;padding:.6rem}aside{position:static;max-height:14rem}.reader-panel{padding:.75rem}#content{padding:.5rem 0}.toolbar{gap:.6rem}}"
                + "</style></head><body>" + body + "</body></html>";
    }

    private String t(String language, String key, Object... args) { return text.text(language, key, args); }
    private static String h(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private static String a(String s) { return h(s); }
    private static String u(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20"); }
}

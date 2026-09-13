package com.myhomelibcorp.web;

import com.myhomelibcorp.application.opds.OpdsBookDto;
import com.myhomelibcorp.application.opds.OpdsPage;
import com.myhomelibcorp.application.dto.ContinueReadingItemDto;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Responsive server-rendered Web Library pages. Contains no storage or desktop dependencies. */
public final class WebLibraryRenderer {
    private static final DateTimeFormatter SHELF_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public String continueReading(List<ContinueReadingItemDto> items) {
        StringBuilder body = new StringBuilder(4096).append(nav())
                .append("<main><section class=hero><h1>Продовжити читання</h1></section><section class=grid>");
        if (items == null || items.isEmpty()) {
            body.append("<p class=empty>Немає незавершених книг.</p>");
        } else {
            for (ContinueReadingItemDto item : items) {
                String id = u(item.bookId());
                body.append("<article class=card><h2><a href=\"/web/read/").append(id).append("\">")
                        .append(h(item.title())).append("</a></h2><p class=author>").append(h(item.authors())).append("</p>")
                        .append("<p><strong>").append(String.format(java.util.Locale.ROOT, "%.1f%%", item.percent()))
                        .append("</strong>");
                if (!item.chapterTitle().isBlank()) body.append(" · ").append(h(item.chapterTitle()));
                body.append("</p><progress max=100 value=\"").append(String.format(java.util.Locale.ROOT, "%.2f", item.percent()))
                        .append("\"></progress><p class=muted>Останній пристрій: ").append(h(item.lastDevice()))
                        .append(" · ").append(item.updatedAt() == null ? "—" : h(SHELF_TIME.format(item.updatedAt())))
                        .append("</p><p><a href=\"/web/read/").append(id).append("\">Продовжити</a></p></article>");
            }
        }
        body.append("</section></main>");
        return document("Продовжити читання", body.toString());
    }

    public String books(String title, String selfPath, String search, OpdsPage<OpdsBookDto> page) {
        StringBuilder body = new StringBuilder(4096);
        body.append(nav()).append("<main><section class=hero><h1>").append(h(title)).append("</h1>")
            .append("<form action=\"/web/search\" method=get class=search><label class=sr-only for=q>Пошук</label>")
            .append("<input id=q name=q type=search maxlength=200 placeholder=\"Назва, автор, серія\" value=\"")
            .append(a(search)).append("\"><button>Знайти</button></form></section>")
            .append("<p class=muted>Знайдено: ").append(page.total()).append("</p><section class=grid>");
        for (OpdsBookDto book : page.items()) body.append(card(book));
        if (page.items().isEmpty()) body.append("<p class=empty>Нічого не знайдено.</p>");
        body.append("</section>").append(pager(selfPath, page)).append("</main>");
        return document(title, body.toString());
    }

    public String book(OpdsBookDto book) {
        String id = u(book.id());
        StringBuilder b = new StringBuilder(2048).append(nav()).append("<main><article class=detail>")
            .append("<p><a href=\"/web/\">← До каталогу</a></p><h1>").append(h(book.title())).append("</h1>")
            .append("<p class=author>").append(h(book.authors())).append("</p>")
            .append(meta(book)).append("<div class=annotation>").append(h(book.annotation())).append("</div>")
            .append("<div class=actions><a class=button href=\"/web/download/").append(id).append("\">Завантажити</a>")
            .append("<a class=button-secondary href=\"/web/read/").append(id).append("\">Читати</a></div>")
            .append("</article></main>");
        return document(book.title(), b.toString());
    }

    public String notFound() { return document("Не знайдено", nav()+"<main><h1>Книгу не знайдено</h1><p><a href=\"/web/\">До каталогу</a></p></main>"); }

    private static String nav() {
        return "<header><nav><a class=brand href=\"/web/\">MyHomeLib</a><a href=\"/web/continue\">Продовжити читання</a></nav></header>";
    }

    private static String card(OpdsBookDto b) {
        String id=u(b.id());
        return "<article class=card><h2><a href=\"/web/books/"+id+"\">"+h(b.title())+"</a></h2>"
            +"<p class=author>"+h(b.authors())+"</p>"+meta(b)
            +"<p><a href=\"/web/read/"+id+"\">Читати</a> · <a href=\"/web/books/"+id+"\">Деталі</a> · <a href=\"/web/download/"+id+"\">Завантажити</a></p></article>";
    }

    private static String meta(OpdsBookDto b) {
        StringBuilder x=new StringBuilder("<p class=meta>");
        if (b.series()!=null && !b.series().isBlank()) x.append(h(b.series())).append(" · ");
        if (b.year()!=null) x.append(b.year()).append(" · ");
        if (b.language()!=null && !b.language().isBlank()) x.append(h(b.language())).append(" · ");
        x.append(h(b.format())).append("</p>");
        return x.toString();
    }

    private static String pager(String self, OpdsPage<?> page) {
        if (!page.hasPrevious() && !page.hasNext()) return "";
        String sep=self.contains("?")?"&":"?";
        StringBuilder p=new StringBuilder("<nav class=pager aria-label=\"Сторінки\">");
        if(page.hasPrevious()) p.append("<a href=\"").append(a(self)).append(sep).append("offset=").append(Math.max(0,page.offset()-page.limit())).append("&amp;limit=").append(page.limit()).append("\">← Назад</a>");
        if(page.hasNext()) p.append("<a href=\"").append(a(self)).append(sep).append("offset=").append(page.offset()+page.items().size()).append("&amp;limit=").append(page.limit()).append("\">Далі →</a>");
        return p.append("</nav>").toString();
    }

    private static String document(String title,String body){
        return "<!doctype html><html lang=\"uk\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"+h(title)+" — MyHomeLib</title><style>"
            +"*{box-sizing:border-box}body{margin:0;font:16px system-ui,sans-serif;background:#f7f7f8;color:#171717}header{background:#111827;color:white}nav,main{max-width:1100px;margin:auto;padding:1rem}header nav{display:flex;gap:1rem;align-items:center;flex-wrap:wrap}header a{color:white}.brand{font-weight:800;margin-right:auto}a{color:#1d4ed8}.hero{display:flex;gap:1rem;justify-content:space-between;align-items:end;flex-wrap:wrap}.search{display:flex;gap:.5rem;flex:1;max-width:560px}.search input{flex:1;min-width:0;padding:.7rem}.search button,.button,.button-secondary{padding:.65rem .9rem;border-radius:.45rem;border:0}.search button,.button{background:#1d4ed8;color:white;text-decoration:none}.button-secondary{border:1px solid #9ca3af;text-decoration:none}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:1rem}.card,.detail{background:white;border:1px solid #e5e7eb;border-radius:.7rem;padding:1rem}.card h2{font-size:1.05rem;margin:.2rem 0}.author{font-weight:600}.meta,.muted{color:#6b7280}.annotation{white-space:pre-wrap;line-height:1.55}.actions,.pager{display:flex;gap:.75rem;margin-top:1rem;flex-wrap:wrap}.pager{justify-content:space-between}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}@media(max-width:600px){main,nav{padding:.75rem}.search{width:100%}.grid{grid-template-columns:1fr}}</style></head><body>"+body+"</body></html>";
    }
    private static String h(String s){return s==null?"":s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static String a(String s){return h(s);}
    private static String u(String s){return URLEncoder.encode(s==null?"":s, StandardCharsets.UTF_8).replace("+","%20");}
}

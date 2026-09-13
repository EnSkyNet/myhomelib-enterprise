package com.myhomelibcorp.opds;

import com.myhomelibcorp.application.opds.OpdsBookDto;
import com.myhomelibcorp.application.opds.OpdsFacetDto;
import com.myhomelibcorp.application.opds.OpdsPage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Minimal dependency-free OPDS 2.0 JSON renderer for the embedded sidecar. */
final class Opds2JsonRenderer {
    static final String TYPE = "application/opds+json;charset=utf-8";
    private static final String TYPE_LINK = "application/opds+json";

    String root() {
        List<NavigationItem> items = List.of(
                new NavigationItem("Бібліотека", "/opds/v2/library", null),
                new NavigationItem("Пошук", "/opds/v2/search?q=", null),
                new NavigationItem("Колекції", "/opds/v2/collections", null),
                new NavigationItem("Групи", "/opds/v2/groups", null),
                new NavigationItem("Обране", "/opds/v2/favorites", null),
                new NavigationItem("Продовжити читання", "/opds/v2/continue", null)
        );
        return navigationDocument("MyHomeLib", "/opds/v2", items, List.of(), (long) items.size(), null, null);
    }

    String collections(Optional<OpdsFacetDto> collection) {
        List<NavigationItem> items = collection
                .map(value -> List.of(new NavigationItem(
                        value.label(),
                        "/opds/v2/collections/" + JdkOpdsServer.encodePathSegment(value.id()),
                        value.bookCount())))
                .orElseGet(List::of);
        return navigationDocument("Колекції", "/opds/v2/collections", items, List.of(), (long) items.size(), null, null);
    }

    String groups(OpdsPage<OpdsFacetDto> page) {
        List<NavigationItem> items = page.items().stream()
                .map(value -> new NavigationItem(
                        value.label(),
                        "/opds/v2/groups/" + JdkOpdsServer.encodePathSegment(value.id()),
                        value.bookCount()))
                .toList();
        return navigationDocument("Групи", "/opds/v2/groups", items,
                paginationLinks("/opds/v2/groups", page), page.total(), page.limit(),
                page.limit() <= 0 ? 1 : (page.offset() / page.limit()) + 1);
    }

    String publications(String title, String self, OpdsPage<OpdsBookDto> page) {
        StringBuilder json = new StringBuilder(512 + page.items().size() * 256);
        json.append('{');
        appendPageMetadata(json, title, page);
        json.append(',');
        appendLinks(json, merge(List.of(new Link("self", self, TYPE_LINK)), paginationLinks(self, page)));
        json.append(",\"publications\":[");
        for (int i = 0; i < page.items().size(); i++) {
            if (i > 0) json.append(',');
            appendPublication(json, page.items().get(i));
        }
        return json.append("]}").toString();
    }

    String publication(OpdsBookDto book) {
        StringBuilder json = new StringBuilder(512);
        json.append('{').append("\"metadata\":{\"title\":\"MyHomeLib\"},");
        appendLinks(json, List.of(new Link("self", "/opds/v2/books/" + JdkOpdsServer.encodePathSegment(book.id()), TYPE_LINK)));
        json.append(",\"publications\":[");
        appendPublication(json, book);
        return json.append("]}").toString();
    }

    private static String navigationDocument(String title, String self, List<NavigationItem> items, List<Link> extraLinks,
                                             Long total, Integer itemsPerPage, Integer currentPage) {
        StringBuilder json = new StringBuilder(512 + items.size() * 120);
        json.append('{');
        appendMetadata(json, title, total, itemsPerPage, currentPage);
        json.append(',');
        appendLinks(json, merge(List.of(new Link("self", self, TYPE_LINK)), extraLinks));
        json.append(",\"navigation\":[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) json.append(',');
            NavigationItem item = items.get(i);
            json.append('{')
                    .append("\"title\":").append(quote(item.title()))
                    .append(",\"href\":").append(quote(item.href()))
                    .append(",\"type\":").append(quote(TYPE_LINK));
            if (item.numberOfItems() != null) {
                json.append(",\"properties\":{\"numberOfItems\":").append(item.numberOfItems()).append('}');
            }
            json.append('}');
        }
        return json.append("]}").toString();
    }

    private static void appendPageMetadata(StringBuilder json, String title, OpdsPage<?> page) {
        int currentPage = page.limit() <= 0 ? 1 : (page.offset() / page.limit()) + 1;
        appendMetadata(json, title, page.total(), page.limit(), currentPage);
    }

    private static void appendMetadata(StringBuilder json, String title, Long total, Integer itemsPerPage, Integer currentPage) {
        json.append("\"metadata\":{")
                .append("\"title\":").append(quote(title));
        if (total != null) json.append(",\"numberOfItems\":").append(Math.max(0, total));
        if (itemsPerPage != null) json.append(",\"itemsPerPage\":").append(itemsPerPage);
        if (currentPage != null) json.append(",\"currentPage\":").append(currentPage);
        json.append('}');
    }

    private static void appendPublication(StringBuilder json, OpdsBookDto book) {
        String bookPath = "/opds/v2/books/" + JdkOpdsServer.encodePathSegment(book.id());
        json.append('{').append("\"metadata\":{")
                .append("\"identifier\":").append(quote("urn:myhomelib:book:" + safe(book.id())))
                .append(",\"title\":").append(quote(nonBlank(book.title(), "Без назви")));
        if (!blank(book.authors())) {
            json.append(",\"author\":[{\"name\":").append(quote(book.authors())).append("}]");
        }
        if (!blank(book.language())) json.append(",\"language\":[").append(quote(book.language())).append(']');
        if (book.year() != null) json.append(",\"published\":").append(quote(book.year() + "-01-01T00:00:00Z"));
        if (!blank(book.annotation())) json.append(",\"description\":").append(quote(book.annotation()));
        if (!blank(book.series())) {
            json.append(",\"belongsTo\":{\"series\":[{\"name\":").append(quote(book.series())).append("}]}");
        }
        json.append("},\"links\":[")
                .append(linkJson(new Link("self", bookPath, TYPE_LINK)));
        if (book.local()) {
            json.append(',').append(linkJson(new Link(
                    "http://opds-spec.org/acquisition/open-access",
                    "/opds/download/" + JdkOpdsServer.encodePathSegment(book.id()),
                    JdkOpdsServer.mimeType(book))));
        }
        json.append("]}");
    }

    private static List<Link> paginationLinks(String self, OpdsPage<?> page) {
        List<Link> links = new ArrayList<>();
        String separator = self.contains("?") ? "&" : "?";
        if (page.offset() > 0) {
            links.add(new Link("first", self + separator + "offset=0&limit=" + page.limit(), TYPE_LINK));
        }
        if (page.hasPrevious()) {
            links.add(new Link("previous", self + separator + "offset=" + Math.max(0, page.offset() - page.limit())
                    + "&limit=" + page.limit(), TYPE_LINK));
        }
        if (page.hasNext()) {
            links.add(new Link("next", self + separator + "offset=" + (page.offset() + page.limit())
                    + "&limit=" + page.limit(), TYPE_LINK));
        }
        return List.copyOf(links);
    }

    private static void appendLinks(StringBuilder json, List<Link> links) {
        json.append("\"links\":[");
        for (int i = 0; i < links.size(); i++) {
            if (i > 0) json.append(',');
            json.append(linkJson(links.get(i)));
        }
        json.append(']');
    }

    private static String linkJson(Link link) {
        return "{\"rel\":" + quote(link.rel()) + ",\"href\":" + quote(link.href()) + ",\"type\":" + quote(link.type()) + "}";
    }

    private static List<Link> merge(List<Link> first, List<Link> second) {
        ArrayList<Link> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return List.copyOf(result);
    }

    private static String quote(String value) {
        String text = safe(value);
        StringBuilder out = new StringBuilder(text.length() + 8).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    private static String nonBlank(String value, String fallback) { return blank(value) ? fallback : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String safe(String value) { return value == null ? "" : value; }

    private record Link(String rel, String href, String type) { }
    private record NavigationItem(String title, String href, Long numberOfItems) { }
}

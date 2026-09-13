package com.myhomelibcorp.shared.comic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared lexical rules for image pages stored inside CBZ/CBR containers. */
public final class ComicPageNameSupport {
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp");

    private ComicPageNameSupport() { }

    public static boolean isSupportedPage(String name) {
        if (!isSafeEntryName(name)) return false;
        String normalized = normalize(name);
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (fileName.isBlank() || fileName.startsWith(".")) return false;
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) return false;
        return EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static boolean isSafeEntryName(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0) return false;
        String normalized = normalize(name);
        if (normalized.startsWith("/") || normalized.startsWith("//")
                || normalized.matches("(?i)^[a-z]:/.*")) return false;
        for (String segment : normalized.split("/", -1)) {
            if ("..".equals(segment)) return false;
        }
        return true;
    }

    public static List<String> sortPages(Iterable<String> names) {
        if (names == null) return List.of();
        List<String> pages = new ArrayList<>();
        for (String name : names) {
            if (isSupportedPage(name)) pages.add(name);
        }
        pages.sort(NATURAL_ORDER);
        return List.copyOf(pages);
    }

    public static final Comparator<String> NATURAL_ORDER = ComicPageNameSupport::compareNatural;

    private static int compareNatural(String left, String right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        String a = normalize(left);
        String b = normalize(right);
        int ia = 0;
        int ib = 0;
        while (ia < a.length() && ib < b.length()) {
            char ca = a.charAt(ia);
            char cb = b.charAt(ib);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int enda = ia;
                while (enda < a.length() && Character.isDigit(a.charAt(enda))) enda++;
                int endb = ib;
                while (endb < b.length() && Character.isDigit(b.charAt(endb))) endb++;

                int za = ia;
                while (za < enda && a.charAt(za) == '0') za++;
                int zb = ib;
                while (zb < endb && b.charAt(zb) == '0') zb++;
                int lena = enda - za;
                int lenb = endb - zb;
                if (lena != lenb) return Integer.compare(lena, lenb);
                if (lena > 0) {
                    int numeric = a.regionMatches(true, za, b, zb, lena) ? 0
                            : a.substring(za, enda).compareTo(b.substring(zb, endb));
                    if (numeric != 0) return numeric;
                }
                int runLength = Integer.compare(enda - ia, endb - ib);
                if (runLength != 0) return runLength;
                ia = enda;
                ib = endb;
                continue;
            }
            int folded = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
            if (folded != 0) return folded;
            if (ca != cb) {
                int exact = Character.compare(ca, cb);
                if (exact != 0) return exact;
            }
            ia++;
            ib++;
        }
        return Integer.compare(a.length(), b.length());
    }

    private static String normalize(String value) {
        return value.trim().replace('\\', '/');
    }
}

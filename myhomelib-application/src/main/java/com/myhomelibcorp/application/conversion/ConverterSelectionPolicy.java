package com.myhomelibcorp.application.conversion;

import com.myhomelibcorp.application.port.out.exporter.BookConverter;

import java.util.Locale;

public final class ConverterSelectionPolicy {
    private ConverterSelectionPolicy() { }
    public static boolean allowed(BookConverter converter,String configuredMode) {
        if(converter==null)return false;
        String mode=configuredMode==null?"AUTO":configuredMode.trim().toUpperCase(Locale.ROOT);
        String id=converter.id()==null?"":converter.id().toLowerCase(Locale.ROOT);
        boolean direct = converter.priority() >= 1000;
        boolean toolchain = id.equals("myhomelib-toolchain");
        boolean calibre = id.startsWith("calibre") || id.contains(":calibre");
        boolean explicitExternal = converter.priority() == 300 && !toolchain && !calibre;
        return switch (mode) {
            case "TOOLCHAIN" -> direct || toolchain;
            case "CALIBRE" -> direct || calibre;
            case "MANUAL" -> direct || explicitExternal;
            default -> true;
        };
    }
}

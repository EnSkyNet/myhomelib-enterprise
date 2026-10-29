package com.myhomelibcorp.organizer.naming;

import com.myhomelibcorp.organizer.metadata.NormalizedBookIdentity;
import com.myhomelibcorp.organizer.policy.OrganizerProfile;
import com.myhomelibcorp.shared.util.SafeFileNameSupport;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;

@Service
public final class CanonicalBookPathService {
    private static final int COMPONENT_BYTES = 220;

    public Path relativePath(NormalizedBookIdentity identity, OrganizerProfile profile, String extension) {
        Objects.requireNonNull(identity,"identity"); Objects.requireNonNull(profile,"profile");
        Map<String,String> values=values(identity, extension);
        String directory=render(profile.directoryTemplate(),values);
        String filename=render(profile.filenameTemplate(),values);
        List<String> parts=new ArrayList<>();
        for(String raw:directory.replace('\\','/').split("/")) {
            if(raw.isBlank())continue;
            String safe=SafeFileNameSupport.sanitizeDirectoryName(raw,COMPONENT_BYTES);
            if(!safe.isBlank())parts.add(safe);
        }
        String safeFile=SafeFileNameSupport.sanitizeComponent(filename,240);
        if(safeFile.isBlank()) safeFile="Untitled."+safeExtension(extension);
        Path result=Path.of(""); for(String part:parts)result=result.resolve(part); result=result.resolve(safeFile);
        return SafeFileNameSupport.validateRelativePath(result);
    }

    public Path resolveUnderRoot(Path destinationRoot, Path relative) {
        Path root=destinationRoot.toAbsolutePath().normalize();
        Path safe=SafeFileNameSupport.validateRelativePath(relative);
        Path target=root.resolve(safe).normalize();
        if(!target.startsWith(root))throw new IllegalArgumentException("Target escapes destination root");
        return target;
    }

    public String collisionKey(Path relative) {
        return Normalizer.normalize(relative.normalize().toString().replace('\\','/'),Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private Map<String,String> values(NormalizedBookIdentity i,String ext) {
        String author=i.authors().isEmpty() ? "" : fileAuthorName(i.authors().getFirst());
        if (author.isBlank()) author="_Невідомий автор";
        String authors=i.authors().stream().map(CanonicalBookPathService::fileAuthorName)
                .filter(v->!v.isBlank()).reduce((a,b)->a+" & "+b).orElse(author);
        Map<String,String> v=new HashMap<>();
        v.put("Author",author);v.put("FirstAuthor",author);v.put("Authors",authors);v.put("Title",i.title().isBlank()?"Untitled":i.title());
        v.put("Series",i.series());v.put("SeriesNo",number(i.seriesNumber(),null));v.put("Language",i.language());v.put("ISBN",i.isbn());
        v.put("ext",safeExtension(ext));v.put("Format",safeExtension(ext));v.put("Year","");v.put("Id","");
        return v;
    }

    /** Path-only formatting: never infer a family name from whitespace-separated creator text. */
    private static String fileAuthorName(com.myhomelibcorp.organizer.metadata.NormalizedAuthor author) {
        if (author.structured() && !author.familyName().isBlank()) {
            return String.join(" ", List.of(author.familyName(), author.givenName(), author.middleName())
                    .stream().filter(value -> !value.isBlank()).toList());
        }
        // A comma is a declared family/given boundary even when metadata normalization
        // is disabled. Preserve unidentified creator strings exactly as supplied.
        String display = author.displayName();
        int comma = display.indexOf(',');
        if (comma > 0 && comma == display.lastIndexOf(',')) {
            String family = display.substring(0, comma).strip();
            String given = display.substring(comma + 1).strip();
            if (!family.isBlank() && !given.isBlank()) return family + " " + given;
        }
        return display;
    }

    private String render(String template,Map<String,String> values) {
        String expanded=expandConditionals(template==null?"":template,values);
        StringBuilder out=new StringBuilder();
        for(int i=0;i<expanded.length();) {
            if(expanded.charAt(i)=='{') {
                int end=expanded.indexOf('}',i+1);
                if(end>i) {
                    String token=expanded.substring(i+1,end); String field=token; String format=null;
                    int colon=token.indexOf(':'); if(colon>0){field=token.substring(0,colon);format=token.substring(colon+1);}
                    String value=values.getOrDefault(field,"");
                    if("SeriesNo".equals(field)&&format!=null)value=formatNumber(value,format);
                    out.append(value); i=end+1; continue;
                }
            }
            out.append(expanded.charAt(i++));
        }
        return out.toString().replaceAll("[ ]{2,}"," ").replaceAll("(?m)^\\s+|\\s+$","");
    }

    private String expandConditionals(String template,Map<String,String> values) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<template.length();) {
            if(i+2<template.length()&&template.charAt(i)=='{'&&template.charAt(i+1)=='?') {
                int colon=template.indexOf(':',i+2); if(colon<0){out.append(template.charAt(i++));continue;}
                String field=template.substring(i+2,colon); int depth=1,end=colon+1;
                while(end<template.length()&&depth>0){char c=template.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;end++;}
                if(depth!=0){out.append(template.charAt(i++));continue;}
                String body=template.substring(colon+1,end-1);
                // A present series without its numeric position must not render a
                // dangling separator from the legacy default template.
                boolean missingNumber = body.contains("{SeriesNo")
                        && values.getOrDefault("SeriesNo", "").isBlank();
                if (!values.getOrDefault(field, "").isBlank() && !missingNumber)
                    out.append(expandConditionals(body, values));
                i=end; continue;
            }
            out.append(template.charAt(i++));
        }
        return out.toString();
    }

    private String formatNumber(String value,String format) {
        if(value==null||value.isBlank())return "";
        try { BigDecimal n=new BigDecimal(value); if(format.chars().allMatch(c->c=='0')&&n.scale()<=0)return String.format(Locale.ROOT,"%0"+format.length()+"d",n.longValueExact()); } catch(Exception ignored){}
        return value;
    }
    private String number(BigDecimal n,String format){if(n==null)return "";return n.stripTrailingZeros().toPlainString();}
    private String safeExtension(String ext) {
        // Conversion format ids are not always filename extensions. In particular,
        // fb2_zip denotes the compound .fb2.zip suffix, never the invalid .fb2zip.
        String normalized = ext == null ? "" : ext.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("fb2_zip") || normalized.equals("fb2zip")
                || normalized.equals("fb2.zip")) return "fb2.zip";
        String safe = normalized.replaceAll("[^a-z0-9]", "");
        return safe.isBlank() ? "epub" : safe;
    }
}

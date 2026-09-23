package com.myhomelibcorp.organizer.policy;

import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.ConverterSelectionPolicy;
import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.organizer.detect.DetectedBookFormat;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public final class CanonicalFormatPolicy {
    private static final Set<String> REFLOWABLE=Set.of("epub","fb2","fb2_zip","mobi","prc","azw","azw3","azw4","docx","rtf","html","htmlz","txt","markdown","md","odt",
            "chm","lit","lrf","pdb","pml","pmlz","rb","snb","tcr","txtz");
    private final List<BookConverter> converters;
    private final ApplicationSettingsPort settings;

    public CanonicalFormatPolicy(List<BookConverter> converters, ApplicationSettingsPort settings) {
        this.converters=converters==null?List.of():converters.stream().filter(c->c!=null)
                .sorted(Comparator.comparingInt(BookConverter::priority).reversed().thenComparing(BookConverter::id)).toList();
        this.settings=settings;
    }

    public CanonicalFormatDecision decide(DetectedBookFormat detected, OrganizerProfile profile) {
        String source=detected==null?"unknown":BookConversionCapability.normalizeFormat(detected.format());
        if ("pdf".equals(source)) {
            String requestedTarget = profile.canonicalReflowableFormat();
            if (profile.pdfPolicy() == PdfPolicy.KEEP_PDF || "original".equalsIgnoreCase(requestedTarget)) {
                return new CanonicalFormatDecision("pdf", false, true, "PDF kept as PDF by policy");
            }
            if (profile.pdfPolicy() == PdfPolicy.CONVERT_WHEN_EXPLICITLY_SUPPORTED && canConvert("pdf", requestedTarget)) {
                return new CanonicalFormatDecision(requestedTarget, true, true,
                        "PDF conversion explicitly enabled and an available route exists");
            }
            return new CanonicalFormatDecision("pdf", false, true,
                    "PDF kept because no explicitly supported conversion route is available");
        }
        if("unknown".equals(source))return new CanonicalFormatDecision(source,false,false,"Unknown or unsupported book format");
        if("book_archive".equals(source))return new CanonicalFormatDecision(source,false,false,"Multi-book archive requires explicit archive expansion; not applied as a single book");
        if("zip".equals(source))return new CanonicalFormatDecision(source,false,false,"Generic ZIP is not treated as a book without a recognized book payload");
        String requestedTarget = profile.canonicalReflowableFormat();
        if ("original".equalsIgnoreCase(requestedTarget)) {
            return new CanonicalFormatDecision(source, false, !"unknown".equals(source), "Original format kept by profile");
        }
        if("epub".equals(source))return new CanonicalFormatDecision("epub",false,true,"EPUB is canonical reflowable format");
        if(REFLOWABLE.contains(source)) {
            String target=requestedTarget;
            boolean supported=canConvert(source,target);
            return new CanonicalFormatDecision(supported?target:source,supported&&!source.equals(target),supported,
                    supported?"Canonical reflowable conversion available":"No available converter route to canonical format");
        }
        return new CanonicalFormatDecision(source,false,!"unknown".equals(source),"Format is kept without automatic canonical conversion");
    }

    public boolean canConvert(String source,String target) {
        String s=BookConversionCapability.normalizeFormat(source),t=BookConversionCapability.normalizeFormat(target);
        if(s.equals(t))return true;
        String mode=settings==null?"AUTO":settings.get("converter.mode","AUTO");
        for(BookConverter converter:converters) if(ConverterSelectionPolicy.allowed(converter,mode)&&converter.isAvailable()) for(BookConversionCapability capability:converter.capabilities())
            if(capability.supportsSource(s)&&capability.targetFormat().equals(t))return true;
        return false;
    }
}

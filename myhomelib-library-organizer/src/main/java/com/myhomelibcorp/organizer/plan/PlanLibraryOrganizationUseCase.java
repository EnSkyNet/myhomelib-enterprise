package com.myhomelibcorp.organizer.plan;

import com.myhomelibcorp.epub.optimizer.EpubOptimizationProfile;
import com.myhomelibcorp.organizer.analysis.OrganizationAnalysis;
import com.myhomelibcorp.organizer.analysis.OrganizerAnalysisItem;
import com.myhomelibcorp.organizer.duplicate.DuplicateKind;
import com.myhomelibcorp.organizer.naming.CanonicalBookPathService;
import com.myhomelibcorp.organizer.policy.OrganizerDuplicatePolicy;
import com.myhomelibcorp.organizer.policy.OrganizerProfile;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.time.Instant;
import java.util.*;

/** Converts an immutable read-only analysis into a deterministic dry-run plan. */
@Service
public final class PlanLibraryOrganizationUseCase {
    private final CanonicalBookPathService paths;
    public PlanLibraryOrganizationUseCase(CanonicalBookPathService paths){this.paths=paths;}

    public OrganizationPlan plan(OrganizationAnalysis analysis,OrganizerProfile profile) {
        Objects.requireNonNull(analysis,"analysis");profile=profile==null?OrganizerProfile.safeDefault():profile;
        List<OrganizationPlanItem> result=new ArrayList<>();List<PlanIssue> planIssues=new ArrayList<>();Set<String> reserved=new HashSet<>();
        for(OrganizerAnalysisItem item:analysis.items())result.add(planItem(analysis,item,profile,reserved));
        return new OrganizationPlan(UUID.randomUUID(),Instant.now(),profile,analysis.sourceRoot(),analysis.destinationRoot(),result,PlanStatistics.from(result),planIssues);
    }

    private OrganizationPlanItem planItem(OrganizationAnalysis analysis,OrganizerAnalysisItem item,OrganizerProfile profile,Set<String> reserved) {
        List<PlanIssue> issues=new ArrayList<>();List<String>warnings=new ArrayList<>(item.warnings());
        String sourceFormat=item.detectedFormat().format(),targetFormat=item.formatDecision().targetFormat();
        if(!item.formatDecision().supported()) return item(item,null,sourceFormat,targetFormat,OrganizationAction.SKIP_UNSUPPORTED,false,warnings,List.of(new PlanIssue("UNSUPPORTED",item.formatDecision().reason(),false)));
        DuplicateKind duplicate=item.duplicate().kind();
        if (duplicate != DuplicateKind.NONE) {
            switch (profile.duplicatePolicy()) {
                case KEEP_ALL -> warnings.add("Знайдено дублікат " + duplicate + "; політика KEEP_ALL залишає обидва артефакти");
                case REVIEW -> {
                    issues.add(new PlanIssue("DUPLICATE_REVIEW","Потрібен review дубліката перед будь-якою destructive дією",false));
                    return item(item,null,sourceFormat,targetFormat,OrganizationAction.REVIEW_DUPLICATE,false,warnings,issues);
                }
                case DELETE_EXACT_ONLY -> {
                    if (duplicate == DuplicateKind.EXACT_BYTES && !item.archiveEntry()) {
                        return item(item,item.duplicate().matchedPath(),sourceFormat,targetFormat,
                                OrganizationAction.DELETE_EXACT_DUPLICATE,false,warnings,issues);
                    }
                    if (duplicate == DuplicateKind.EXACT_BYTES && item.archiveEntry()) {
                        issues.add(new PlanIssue("ARCHIVE_ENTRY_DUPLICATE_REVIEW",
                                "Exact duplicate is inside a multi-book archive; the container is never deleted automatically", false));
                        return item(item,null,sourceFormat,targetFormat,OrganizationAction.REVIEW_DUPLICATE,false,warnings,issues);
                    }
                    issues.add(new PlanIssue("DUPLICATE_REVIEW","Політика DELETE_EXACT_ONLY автоматично видаляє лише exact-bytes; цей дублікат потребує review",false));
                    return item(item,null,sourceFormat,targetFormat,OrganizationAction.REVIEW_DUPLICATE,false,warnings,issues);
                }
            }
        }
        Path target=paths.resolveUnderRoot(analysis.destinationRoot(),item.proposedRelativePath());
        String key=paths.collisionKey(analysis.destinationRoot().relativize(target));
        if(reserved.contains(key)||((item.archiveEntry() || !samePath(item.sourcePath(),target)) && Files.exists(target, LinkOption.NOFOLLOW_LINKS))) {
            target=collisionTarget(target,item.fingerprint().fileSha256(),reserved,analysis.destinationRoot());
            warnings.add("Цільовий шлях зайнятий; додано стабільний hash-suffix без перезапису");
            issues.add(new PlanIssue("TARGET_COLLISION_RESOLVED","Колізію імені вирішено hash-suffix",false));
            key=paths.collisionKey(analysis.destinationRoot().relativize(target));
        }
        reserved.add(key);
        boolean conversion=item.formatDecision().conversionRequired();
        boolean optimization = !conversion
                && "epub".equalsIgnoreCase(sourceFormat)
                && "epub".equalsIgnoreCase(targetFormat)
                && profile.epubOptimizationProfile() != EpubOptimizationProfile.NONE;
        if (optimization) warnings.add("EPUB буде структурно перевірено та оптимізовано перед фіналізацією");
        OrganizationAction action=action(item.sourcePath(),item.sourceEntry(),target,conversion,optimization);
        return item(item,target,sourceFormat,targetFormat,action,conversion,warnings,issues);
    }

    private Path collisionTarget(Path desired,String hash,Set<String> reserved,Path root) {
        String tag=hash==null||hash.length()<8?"conflict":hash.substring(0,8);String name=desired.getFileName().toString();int dot = name.toLowerCase(Locale.ROOT).endsWith(".fb2.zip")
                ? name.length() - ".fb2.zip".length() : name.lastIndexOf('.');
        String stem=dot>0?name.substring(0,dot):name,ext=dot>0?name.substring(dot):"";Path parent=desired.getParent();
        for(int i=1;i<10000;i++){String suffix=i==1?tag:tag+"-"+i;Path c=parent.resolve(stem+" ["+suffix+"]"+ext);String k=paths.collisionKey(root.relativize(c));if(!reserved.contains(k)&&!Files.exists(c, LinkOption.NOFOLLOW_LINKS))return c;}
        throw new IllegalStateException("Cannot resolve target collision for "+desired);
    }

    private OrganizationAction action(Path source,String sourceEntry,Path target,boolean conversion,boolean optimization) {
        if(sourceEntry!=null&&!sourceEntry.isBlank()) {
            if(conversion)return OrganizationAction.CONVERT;
            if(optimization)return OrganizationAction.OPTIMIZE;
            return OrganizationAction.EXTRACT;
        }
        Path s=source.toAbsolutePath().normalize(),t=target.toAbsolutePath().normalize();
        if(conversion)return Objects.equals(s.getParent(),t.getParent())?OrganizationAction.CONVERT:OrganizationAction.CONVERT_AND_MOVE;
        if(optimization)return s.equals(t)?OrganizationAction.OPTIMIZE:OrganizationAction.OPTIMIZE_AND_MOVE;
        if(s.equals(t))return OrganizationAction.NOOP;
        boolean sameParent=Objects.equals(s.getParent(),t.getParent()),sameName=Objects.equals(s.getFileName(),t.getFileName());
        if(sameParent)return OrganizationAction.RENAME;if(sameName)return OrganizationAction.MOVE;return OrganizationAction.MOVE_AND_RENAME;
    }
    private OrganizationPlanItem item(OrganizerAnalysisItem src,Path target,String sf,String tf,OrganizationAction a,boolean c,List<String>w,List<PlanIssue>i){
        return new OrganizationPlanItem(UUID.randomUUID(),src.sourcePath(),src.sourceEntry(),src.fingerprint().fileSha256(),
                target,sf,tf,a,c,reason(src,a,c),src.duplicate(),src.identity().confidence(),w,i);
    }
    private String reason(OrganizerAnalysisItem src, OrganizationAction action, boolean conversion) {
        if (action == null) return "";
        return switch (action) {
            case SKIP_UNSUPPORTED -> src.formatDecision().reason();
            case REVIEW_DUPLICATE, DELETE_EXACT_DUPLICATE -> src.duplicate().reason().isBlank()
                    ? "Duplicate policy requires review" : src.duplicate().reason();
            case CONVERT, CONVERT_AND_MOVE -> src.formatDecision().reason();
            case OPTIMIZE, OPTIMIZE_AND_MOVE -> "EPUB optimization is enabled by the selected Organizer profile";
            case EXTRACT -> "Logical book entry will be extracted from a multi-book archive; the source container is retained";
            case RENAME -> "Canonical filename differs from the current filename";
            case MOVE -> "Canonical directory differs from the current directory";
            case MOVE_AND_RENAME -> "Canonical directory and filename differ from the current path";
            case ARCHIVE_ORIGINAL -> "Original retention policy moves the source into the recoverable originals area";
            case NOOP, KEEP_AS_IS -> "Source already satisfies the selected organization policy";
            case CONFLICT -> "Target path conflict requires resolution";
            case ERROR -> "Planning failed for this source";
        };
    }
    private boolean samePath(Path a,Path b){return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());}

}

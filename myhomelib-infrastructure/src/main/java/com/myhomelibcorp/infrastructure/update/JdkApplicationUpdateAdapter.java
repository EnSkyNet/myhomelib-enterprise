package com.myhomelibcorp.infrastructure.update;

import com.myhomelibcorp.application.update.*;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.springframework.beans.factory.annotation.Autowired;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.io.StringReader;
import java.io.StringWriter;

/** HTTPS manifest/download adapter with bounded responses and an atomic local update journal. */
@Component
public final class JdkApplicationUpdateAdapter implements ApplicationUpdatePort {
    private static final long MAX_MANIFEST=1_048_576L;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final ApplicationSettingsPort settings;

    @Autowired
    public JdkApplicationUpdateAdapter(ObjectMapper mapper, ApplicationSettingsPort settings) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build(), settings);
    }

    /** Compatibility constructor for focused tests/tools without a settings store. */
    public JdkApplicationUpdateAdapter(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build(), null);
    }

    JdkApplicationUpdateAdapter(ObjectMapper mapper,HttpClient http){this(mapper,http,null);}
    JdkApplicationUpdateAdapter(ObjectMapper mapper,HttpClient http,ApplicationSettingsPort settings){
        this.mapper=mapper;this.http=http;this.settings=settings;
    }

    @Override public Optional<ReleaseManifest> fetchManifest(UpdateChannel channel) throws Exception {
        String property=switch(channel){case STABLE->"myhomelib.update.stableManifest";case BETA->"myhomelib.update.betaManifest";case MANUAL->"myhomelib.update.manualManifest";};
        String settingKey=switch(channel){case STABLE->"update.manifest.stable";case BETA->"update.manifest.beta";case MANUAL->"update.manifest.manual";};
        String configured=settings==null?"":settings.get(settingKey,"").trim();
        if(configured.isBlank()) configured=System.getProperty(property,"").trim();
        if(configured.isBlank()) return Optional.empty();
        URI uri=URI.create(configured); requireHttps(uri,"manifest");
        HttpRequest req=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Accept","application/json").GET().build();
        HttpResponse<InputStream> response=http.send(req,HttpResponse.BodyHandlers.ofInputStream());
        if(response.statusCode()!=200) { response.body().close(); throw new IllegalStateException("Update manifest HTTP "+response.statusCode()); }
        byte[] bytes;
        try(InputStream in=response.body()){bytes=in.readNBytes((int)MAX_MANIFEST+1);}
        if(bytes.length>MAX_MANIFEST) throw new SecurityException("Update manifest exceeds size limit");
        JsonNode n=mapper.readTree(bytes);
        if(n==null || !n.isObject()) throw new SecurityException("Update manifest must be a JSON object");
        ReleaseManifest manifest=new ReleaseManifest(text(n,"productId"),text(n,"version"),UpdateChannel.valueOf(text(n,"channel").toUpperCase(java.util.Locale.ROOT)),
                text(n,"platform"),text(n,"arch"),URI.create(text(n,"artifactUrl")),n.path("artifactLength").asLong(-1),
                text(n,"sha256"),text(n,"signature"),text(n,"keyId"),text(n,"minimumUpdaterVersion"),n.path("releaseNotes").asString(""));
        if(manifest.channel()!=channel && channel!=UpdateChannel.MANUAL) throw new SecurityException("Manifest channel mismatch");
        return Optional.of(manifest);
    }

    @Override public Path downloadVerifiedCandidateSource(ReleaseManifest manifest) throws Exception {
        requireHttps(manifest.artifactUri(),"artifact");
        Path dir=AtomicFileSupport.createDirectoriesNoLinks(AppPaths.cacheDir().resolve("application-updates").resolve(safe(manifest.version())),"update staging");
        Path tmp=Files.createTempFile(dir,"candidate-",".part");
        boolean ok=false;
        try {
            HttpRequest req=HttpRequest.newBuilder(manifest.artifactUri()).timeout(Duration.ofMinutes(10)).GET().build();
            HttpResponse<InputStream> response=http.send(req,HttpResponse.BodyHandlers.ofInputStream());
            if(response.statusCode()!=200){response.body().close();throw new IllegalStateException("Update artifact HTTP "+response.statusCode());}
            long declared=response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if(declared>=0 && declared!=manifest.artifactLength()){response.body().close();throw new SecurityException("Update Content-Length mismatch");}
            MessageDigest downloadedDigest=MessageDigest.getInstance("SHA-256");
            try(InputStream in=response.body(); var out=Files.newOutputStream(tmp,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,java.nio.file.StandardOpenOption.WRITE)){
                byte[] buf=new byte[64*1024]; long total=0;
                for(int n;(n=in.read(buf))>=0;){
                    if(n==0)continue;
                    total+=n;
                    if(total>manifest.artifactLength()) throw new SecurityException("Update artifact exceeds declared length");
                    out.write(buf,0,n);
                    downloadedDigest.update(buf,0,n);
                }
                if(total!=manifest.artifactLength()) throw new SecurityException("Update artifact is truncated");
            }
            if(!manifest.sha256().equals(HexFormat.of().formatHex(downloadedDigest.digest())))
                throw new SecurityException("Downloaded update artifact SHA-256 mismatch");
            Path candidate=dir.resolve("candidate-"+manifest.sha256()+".bin");
            try {
                // A normal no-replace move avoids overwriting a candidate created by another downloader.
                if(Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(candidate.toString());
                Files.move(tmp,candidate);
                ok=true; return candidate;
            } catch(FileAlreadyExistsException occupied) {
                // The filename is not evidence of content integrity: a previous attempt
                // may have left damaged bytes behind. Never delete an unowned cache entry.
                if(cachedArtifactMatches(candidate,manifest)) {
                    Files.delete(tmp); ok=true; return candidate;
                }
                Path fresh=dir.resolve("candidate-"+manifest.sha256()+"-"+UUID.randomUUID()+".bin");
                Files.move(tmp,fresh);
                ok=true; return fresh;
            }
        } finally { if(!ok) Files.deleteIfExists(tmp); }
    }

    private static boolean cachedArtifactMatches(Path path,ReleaseManifest manifest) throws Exception {
        AtomicFileSupport.requireSafeRegularFile(path,"existing update candidate");
        if(Files.size(path)!=manifest.artifactLength()) return false;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] buf=new byte[64*1024];
            for(int n;(n=in.read(buf))>=0;) if(n>0) digest.update(buf,0,n);
        }
        return manifest.sha256().equals(HexFormat.of().formatHex(digest.digest()));
    }

    @Override public void savePlan(UpdatePlan plan) throws Exception {
        Properties p=new Properties(); ReleaseManifest m=plan.manifest();
        p.setProperty("productId",m.productId());p.setProperty("version",m.version());p.setProperty("channel",m.channel().name());
        p.setProperty("platform",m.platform());p.setProperty("arch",m.arch());p.setProperty("artifactUrl",m.artifactUri().toASCIIString());
        p.setProperty("artifactLength",Long.toString(m.artifactLength()));p.setProperty("sha256",m.sha256());p.setProperty("signature",m.signature());
        p.setProperty("keyId",m.keyId());p.setProperty("minimumUpdaterVersion",m.minimumUpdaterVersion());p.setProperty("releaseNotes",m.releaseNotes());
        p.setProperty("stagedArtifact",plan.stagedArtifact().toString());p.setProperty("installationPath",plan.installationPath().toString());
        p.setProperty("backupPath",plan.backupPath().toString());p.setProperty("healthMarker",plan.healthMarker().toString());
        p.setProperty("portableMode",Boolean.toString(plan.portableMode()));p.setProperty("createdAt",plan.createdAt().toString());
        StringWriter w=new StringWriter();p.store(w,"MyHomeLib verified application update plan");
        AtomicFileSupport.writeStringReplacing(planPath(),w.toString(),StandardCharsets.UTF_8);
    }
    @Override public Optional<UpdatePlan> pendingPlan() throws Exception {
        Path path=planPath(); if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return Optional.empty(); AtomicFileSupport.requireSafeRegularFile(path,"update plan");
        Properties p=new Properties();p.load(new StringReader(Files.readString(path,StandardCharsets.UTF_8)));
        ReleaseManifest m=new ReleaseManifest(req(p,"productId"),req(p,"version"),UpdateChannel.valueOf(req(p,"channel")),req(p,"platform"),req(p,"arch"),
                URI.create(req(p,"artifactUrl")),Long.parseLong(req(p,"artifactLength")),req(p,"sha256"),req(p,"signature"),req(p,"keyId"),req(p,"minimumUpdaterVersion"),p.getProperty("releaseNotes",""));
        return Optional.of(new UpdatePlan(m,Path.of(req(p,"stagedArtifact")),Path.of(req(p,"installationPath")),Path.of(req(p,"backupPath")),Path.of(req(p,"healthMarker")),
                Boolean.parseBoolean(req(p,"portableMode")),Instant.parse(req(p,"createdAt"))));
    }
    @Override public void clearPlan() throws Exception { Path p=planPath(); if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)){AtomicFileSupport.requireSafeRegularFile(p,"update plan");Files.delete(p);} }

    private static Path planPath(){return AppPaths.dataDir().resolve("updates").resolve("pending-update.properties");}
    private static String text(JsonNode n,String key){String v=n.path(key).asString("");if(v.isBlank())throw new SecurityException("Missing manifest field: "+key);return v;}
    private static String req(Properties p,String key){String v=p.getProperty(key,"").trim();if(v.isBlank())throw new SecurityException("Missing update plan field: "+key);return v;}
    private static void requireHttps(URI uri,String role){if(uri==null||!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null)throw new SecurityException("Application update "+role+" must use an absolute HTTPS URL");}
    private static String safe(String v){return v.replaceAll("[^A-Za-z0-9._-]","_");}
}

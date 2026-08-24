package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.port.out.download.RemoteCatalogDownloadPort;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.util.AppPaths;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

@Component
public class HttpRemoteCatalogDownloadAdapter implements RemoteCatalogDownloadPort {
    private final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
    @Override public Path download(Collection collection,String url,AtomicBoolean cancel,DoubleConsumer progress)throws Exception{
        URI uri=URI.create(url.trim()); HttpRequest.Builder b=HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(15)).header("User-Agent","MyHomeLib/1.0.0").GET();
        if(collection.getUser()!=null&&!collection.getUser().isBlank()){
            String pass="";try{String p=collection.getDecryptedPassword();if(p!=null)pass=p;}catch(Exception ignored){}
            b.header("Authorization","Basic "+Base64.getEncoder().encodeToString((collection.getUser()+":"+pass).getBytes(StandardCharsets.UTF_8)));
        }
        HttpResponse<InputStream> r=client.send(b.build(),HttpResponse.BodyHandlers.ofInputStream());
        if(r.statusCode()<200||r.statusCode()>=300){try(InputStream ignored=r.body()){}throw new java.io.IOException("HTTP "+r.statusCode());}
        Files.createDirectories(AppPaths.cacheDir().resolve("catalog-updates")); Path part=Files.createTempFile(AppPaths.cacheDir().resolve("catalog-updates"),"catalog-",".inpx.part"); Path out=part.resolveSibling(part.getFileName().toString().replace(".part",""));
        long total=r.headers().firstValueAsLong("Content-Length").orElse(-1),done=0;
        try(InputStream in=r.body();var os=Files.newOutputStream(part)){byte[]buf=new byte[64*1024];int n;while((n=in.read(buf))!=-1){if(cancel.get()||Thread.currentThread().isInterrupted())throw new java.io.IOException("Оновлення скасовано");if(n==0)continue;os.write(buf,0,n);done+=n;if(total>0)progress.accept(Math.min(1d,(double)done/total));}}
        catch(Exception e){Files.deleteIfExists(part);throw e;}
        Files.move(part,out,StandardCopyOption.REPLACE_EXISTING);progress.accept(1d);return out;
    }
}

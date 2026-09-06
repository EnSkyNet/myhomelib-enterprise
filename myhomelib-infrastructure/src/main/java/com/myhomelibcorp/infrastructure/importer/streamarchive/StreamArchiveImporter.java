package com.myhomelibcorp.infrastructure.importer.streamarchive;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.infrastructure.importer.archive.ArchiveImportSupport;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** TAR/CPIO and compressed TAR importer.  It is sequential and memory bounded. */
@Component
@Slf4j
public class StreamArchiveImporter implements BookImporterPort {
    @Lazy @Autowired private ImporterRegistry registry;

    @Override public boolean supports(Path file) {
        return SupportedFormatRegistry.standard().isFormat(file, "tar", "cpio");
    }
    @Override public String getFormatName(){return "TAR/CPIO";}

    @Override public Stream<Book> importBooks(Path file) {
        try {
            IteratorImpl it=new IteratorImpl(file, open(file));
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it,Spliterator.ORDERED),false).onClose(it::close);
        } catch(Exception e){throw new RuntimeException("Не вдалося відкрити архів: "+file,e);}
    }
    @Override public long countBooks(Path file){
        long c=0; try(ArchiveInputStream<?> in=open(file)){ ArchiveEntry e; int n=0; while((e=in.getNextEntry())!=null){ if(++n>ArchiveSafetyLimits.MAX_ENTRY_COUNT) break; if(!e.isDirectory()&&isBook(e.getName()))c++; } }catch(Exception e){return -1;} return c;
    }

    private boolean isBook(String name){
        return ArchiveImportSupport.isSafeEntryName(name)
                && ArchiveImportSupport.isSupportedBookEntry(name, registry);
    }
    private ArchiveInputStream<?> open(Path p)throws Exception{
        String n=p.getFileName().toString().toLowerCase(Locale.ROOT);
        InputStream raw=new BufferedInputStream(Files.newInputStream(p),64*1024);
        InputStream source=raw;
        try{
            if(n.endsWith(".tar.gz")||n.endsWith(".tgz")) source=new CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.GZIP,raw,true);
            else if(n.endsWith(".tar.bz2")||n.endsWith(".tbz2")) source=new CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.BZIP2,raw,true);
            else if(n.endsWith(".tar.xz")||n.endsWith(".txz")) source=new CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.XZ,raw,true);
            String type=n.endsWith(".cpio")?ArchiveStreamFactory.CPIO:ArchiveStreamFactory.TAR;
            return new ArchiveStreamFactory().createArchiveInputStream(type,source);
        }catch(Exception e){try{source.close();}catch(Exception ignored){} if(source!=raw)try{raw.close();}catch(Exception ignored){} throw e;}
    }

    private final class IteratorImpl implements Iterator<Book>,AutoCloseable{
        private final Path path; private final ArchiveInputStream<?> in; private final Queue<Book> q=new ArrayDeque<>(); private boolean done; private int entries; private long totalDecompressedBytes;
        IteratorImpl(Path p,ArchiveInputStream<?> in){this.path=p;this.in=in;}
        @Override public boolean hasNext(){fill();return !q.isEmpty();}
        @Override public Book next(){if(!hasNext())throw new NoSuchElementException();return q.remove();}
        private void fill(){
            while(q.isEmpty()&&!done){
                try{
                    if(Thread.currentThread().isInterrupted()){close();return;}
                    ArchiveEntry e=in.getNextEntry();
                    if(e==null){close();return;}
                    if(++entries>ArchiveSafetyLimits.MAX_ENTRY_COUNT)throw new IOException("Забагато записів у архіві");
                    String name=e.getName();
                    if(e.isDirectory()||!isBook(name))continue;
                    if(ArchiveSafetyLimits.declaredEntryTooLarge(e.getSize()))continue;
                    BookImporterPort importer=registry.findImporter(ArchiveImportSupport.importerProbePath(name));
                    Path tmp=Files.createTempFile("mhl-archive-",ArchiveImportSupport.suffixFor(name));
                    try{
                        long extracted=copyBounded(in,tmp);
                        totalDecompressedBytes+=extracted;
                        if(totalDecompressedBytes>ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES)throw new IOException("archive exceeds cumulative decompression safety limit");
                        try(Stream<Book>s=importer.importBooks(tmp)){
                            s.filter(Objects::nonNull).map(b->ArchiveImportSupport.enrich(b,path,name,extracted)).forEach(q::add);
                        }
                    }finally{Files.deleteIfExists(tmp);}
                }catch(Exception ex){log.warn("Archive entry skipped in {}: {}",path,ex.getMessage());close();}
            }
        }
        private long copyBounded(InputStream src,Path dst)throws IOException{
            try(OutputStream out=Files.newOutputStream(dst)){
                byte[]b=new byte[64*1024];long total=0;int n;
                while((n=src.read(b))>0){
                    if(Thread.currentThread().isInterrupted())throw new IOException("archive import cancelled");
                    total+=n;
                    if(total>ArchiveSafetyLimits.MAX_ENTRY_BYTES)throw new IOException("entry exceeds limit");
                    out.write(b,0,n);
                }
                return total;
            }
        }
        @Override public void close(){if(done)return;done=true;try{in.close();}catch(Exception ignored){}}
    }
}

package com.myhomelibcorp.mcp;

import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Bounded, read-only extraction for MCP book text. */
final class BookContentReader {
    private static final int MAX_TEXT = 20_000_000;

    String text(LibraryDb.BookLocation l) throws Exception {
        if(l==null) throw new IllegalArgumentException("Book not found");
        Path container=resolve(l); String entry=l.archiveEntry();
        if(!entry.isBlank()) {
            try(InputStream in=openArchiveEntry(container,entry)){ return parseByName(entry,in); }
        }
        try(InputStream in=Files.newInputStream(container)){return parseByName(l.fileName(),in);}
    }

    List<TocItem> toc(LibraryDb.BookLocation l)throws Exception{
        String name=!l.archiveEntry().isBlank()?l.archiveEntry():l.fileName();
        String lower=name.toLowerCase(Locale.ROOT);
        Path p=resolve(l);
        try(InputStream in=l.archiveEntry().isBlank()?Files.newInputStream(p):openArchiveEntry(p,l.archiveEntry())){
            if(lower.endsWith(".fb2")||lower.endsWith(".fbd")) return fb2Toc(in);
            if(lower.endsWith(".epub")) return epubToc(in);
            return List.of();
        }
    }

    private Path resolve(LibraryDb.BookLocation l){
        Path root=l.collectionRoot().isBlank()?Path.of(""):Path.of(l.collectionRoot());
        if(!l.archiveEntry().isBlank()){
            String f=!l.folder().isBlank()?l.folder():l.fileName(); return root.resolve(f).normalize();
        }
        Path rel=l.folder().isBlank()?Path.of(l.fileName()):Path.of(l.folder()).resolve(l.fileName()); return root.resolve(rel).normalize();
    }

    private InputStream openArchiveEntry(Path archive,String requested)throws Exception{
        String n=archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if(n.endsWith(".7z")) return spool7z(archive,requested);
        if(n.endsWith(".rar")||n.endsWith(".cbr")) return spoolRar(archive,requested);
        if(isStreamArchive(n)) return spoolStreamArchive(archive, requested);
        ZipFile z=new ZipFile(archive.toFile()); ZipEntry e=findZip(z,requested); if(e==null){z.close();throw new FileNotFoundException(requested);}
        InputStream delegate=z.getInputStream(e); return new FilterInputStream(delegate){@Override public void close()throws IOException{try{super.close();}finally{z.close();}}};
    }
    private ZipEntry findZip(ZipFile z,String n){ZipEntry e=z.getEntry(n);if(e!=null)return e;String x=norm(n);Enumeration<? extends ZipEntry> it=z.entries();while(it.hasMoreElements()){e=it.nextElement();if(norm(e.getName()).equalsIgnoreCase(x))return e;}return null;}
    private InputStream spool7z(Path p,String wanted)throws Exception{
        Path tmp=Files.createTempFile("mhl-mcp-",suffix(wanted));
        try(SevenZFile z=SevenZFile.builder().setFile(p.toFile()).setMaxMemoryLimitKiB(ArchiveSafetyLimits.SEVEN_Z_MEMORY_LIMIT_KIB).get()){
            SevenZArchiveEntry e; while((e=z.getNextEntry())!=null){if(e.isDirectory()||!norm(e.getName()).equalsIgnoreCase(norm(wanted)))continue;copyBounded(new InputStream(){@Override public int read(byte[]b,int o,int l)throws IOException{return z.read(b,o,l);}@Override public int read()throws IOException{byte[]b=new byte[1];return z.read(b,0,1)==1?b[0]&255:-1;}},tmp);return deleting(tmp);}
        }catch(Exception ex){Files.deleteIfExists(tmp);throw ex;} Files.deleteIfExists(tmp);throw new FileNotFoundException(wanted);
    }
    private InputStream spoolRar(Path p,String wanted)throws Exception{
        Path tmp=Files.createTempFile("mhl-mcp-",suffix(wanted)); try(Archive a=new Archive(p.toFile())){
            for(FileHeader h:a.getFileHeaders()) if(!h.isDirectory()&&norm(h.getFileName()).equalsIgnoreCase(norm(wanted))){try(InputStream in=a.getInputStream(h)){copyBounded(in,tmp);}return deleting(tmp);}
        }catch(Exception ex){Files.deleteIfExists(tmp);throw ex;}Files.deleteIfExists(tmp);throw new FileNotFoundException(wanted);
    }
    private boolean isStreamArchive(String n) {
        return n.endsWith(".tar") || n.endsWith(".tar.gz") || n.endsWith(".tgz")
                || n.endsWith(".tar.bz2") || n.endsWith(".tbz2")
                || n.endsWith(".tar.xz") || n.endsWith(".txz") || n.endsWith(".cpio");
    }

    private ArchiveInputStream<?> openStreamArchive(Path path) throws Exception {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        InputStream raw = new BufferedInputStream(Files.newInputStream(path), 64 * 1024);
        InputStream source = raw;
        try {
            if (n.endsWith(".tar.gz") || n.endsWith(".tgz"))
                source = new CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.GZIP, raw, true);
            else if (n.endsWith(".tar.bz2") || n.endsWith(".tbz2"))
                source = new CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.BZIP2, raw, true);
            else if (n.endsWith(".tar.xz") || n.endsWith(".txz"))
                source = new CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.XZ, raw, true);
            String type = n.endsWith(".cpio") ? ArchiveStreamFactory.CPIO : ArchiveStreamFactory.TAR;
            return new ArchiveStreamFactory().createArchiveInputStream(type, source);
        } catch (Exception e) {
            try { source.close(); } catch (Exception ignored) { }
            if (source != raw) try { raw.close(); } catch (Exception ignored) { }
            throw e;
        }
    }

    private InputStream spoolStreamArchive(Path p, String wanted) throws Exception {
        Path tmp = Files.createTempFile("mhl-mcp-", suffix(wanted));
        try (ArchiveInputStream<?> in = openStreamArchive(p)) {
            ArchiveEntry e;
            int entries = 0;
            while ((e = in.getNextEntry()) != null) {
                if (++entries > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("Archive contains too many entries");
                if (e.isDirectory() || e.getName() == null || !norm(e.getName()).equalsIgnoreCase(norm(wanted))) continue;
                if (e.getSize() > ArchiveSafetyLimits.MAX_ENTRY_BYTES) throw new IOException("Archive entry exceeds MCP safety limit");
                copyBounded(in, tmp);
                return deleting(tmp);
            }
        } catch (Exception ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
        Files.deleteIfExists(tmp);
        throw new FileNotFoundException(wanted);
    }

    private void copyBounded(InputStream in,Path p)throws IOException{try(OutputStream o=Files.newOutputStream(p)){byte[]b=new byte[65536];long total=0;for(int n;(n=in.read(b))>=0;){if(n==0)continue;total+=n;if(total>ArchiveSafetyLimits.MAX_ENTRY_BYTES)throw new IOException("Archive entry exceeds MCP safety limit");o.write(b,0,n);}}}
    private InputStream deleting(Path p)throws IOException{InputStream in=Files.newInputStream(p);return new FilterInputStream(in){@Override public void close()throws IOException{try{super.close();}finally{Files.deleteIfExists(p);}}};}

    private String parseByName(String name,InputStream in)throws Exception{
        String n=name.toLowerCase(Locale.ROOT);
        if(n.endsWith(".fb2")||n.endsWith(".fbd"))return fb2Text(in);
        if(n.endsWith(".txt")||n.endsWith(".md"))return readText(in);
        if(n.endsWith(".epub"))return epubText(in);
        if(n.endsWith(".html")||n.endsWith(".htm")||n.endsWith(".xhtml"))return stripMarkup(readText(in));
        if(n.endsWith(".rtf")) return stripRtf(readText(in));
        throw new IOException("Unsupported book format for MCP text extraction: " + name + ". Supported: FB2/FBD, EPUB, TXT/MD, HTML/XHTML, RTF");
    }
    private String readText(InputStream in)throws IOException{StringBuilder s=new StringBuilder();try(Reader r=new InputStreamReader(in,StandardCharsets.UTF_8)){char[]b=new char[8192];for(int n;(n=r.read(b))>=0&&s.length()<MAX_TEXT;)s.append(b,0,Math.min(n,MAX_TEXT-s.length()));}return s.toString();}
    private String fb2Text(InputStream in)throws Exception{
        XMLInputFactory f=XMLInputFactory.newFactory();f.setProperty(XMLInputFactory.IS_COALESCING,false);StringBuilder out=new StringBuilder();XMLStreamReader r=f.createXMLStreamReader(in);
        boolean inBody=false,skipBinary=false;while(r.hasNext()&&out.length()<MAX_TEXT){int e=r.next();if(e==XMLStreamConstants.START_ELEMENT){String n=r.getLocalName();if("body".equals(n))inBody=true;if("binary".equals(n))skipBinary=true;if(inBody&&(n.equals("p")||n.equals("title")||n.equals("subtitle")||n.equals("v")||n.equals("empty-line")))out.append('\n');}else if(e==XMLStreamConstants.CHARACTERS&&inBody&&!skipBinary){String t=r.getText();if(!t.isBlank())out.append(t);}else if(e==XMLStreamConstants.END_ELEMENT){String n=r.getLocalName();if("binary".equals(n))skipBinary=false;if("body".equals(n))inBody=false;}}r.close();return normalize(out.toString());
    }
    private List<TocItem> fb2Toc(InputStream in)throws Exception{
        XMLInputFactory f=XMLInputFactory.newFactory();f.setProperty(XMLInputFactory.IS_COALESCING,false);XMLStreamReader r=f.createXMLStreamReader(in);List<TocItem> out=new ArrayList<>();int depth=0;boolean inTitle=false;StringBuilder t=new StringBuilder();long ord=0;
        while(r.hasNext()){int e=r.next();if(e==XMLStreamConstants.START_ELEMENT){String n=r.getLocalName();if(n.equals("section"))depth++;if(n.equals("title")&&depth>0){inTitle=true;t.setLength(0);}}else if(e==XMLStreamConstants.CHARACTERS&&inTitle)t.append(r.getText());else if(e==XMLStreamConstants.END_ELEMENT){String n=r.getLocalName();if(n.equals("title")&&inTitle){String x=normalize(t.toString());if(!x.isBlank())out.add(new TocItem(depth,x,ord++));inTitle=false;}if(n.equals("section"))depth=Math.max(0,depth-1);}}r.close();return out;
    }
    private String epubText(InputStream in)throws Exception{
        Path tmp=Files.createTempFile("mhl-mcp-epub-",".epub");
        try {
            copyBounded(in,tmp);
            StringBuilder out=new StringBuilder();
            try(ZipFile zip=new ZipFile(tmp.toFile())) {
                EpubPackage pkg=readEpubPackage(zip);
                List<String> documents=pkg.spinePaths().isEmpty()?fallbackHtmlEntries(zip):pkg.spinePaths();
                for(String path:documents){
                    ZipEntry entry=findZip(zip,path);
                    if(entry==null||entry.isDirectory()) continue;
                    try(InputStream x=zip.getInputStream(entry)){
                        String part=stripMarkup(readText(x));
                        if(!part.isBlank()) out.append('\n').append(part);
                    }
                    if(out.length()>=MAX_TEXT) break;
                }
            }
            return normalize(out.substring(0,Math.min(out.length(),MAX_TEXT)));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private List<TocItem> epubToc(InputStream in)throws Exception {
        Path tmp=Files.createTempFile("mhl-mcp-epub-toc-",".epub");
        try {
            copyBounded(in,tmp);
            try(ZipFile zip=new ZipFile(tmp.toFile())) {
                EpubPackage pkg=readEpubPackage(zip);
                if(pkg.navPath()!=null) {
                    ZipEntry nav=findZip(zip,pkg.navPath());
                    if(nav!=null) try(InputStream x=zip.getInputStream(nav)) {
                        List<TocItem> items=parseEpubNav(x);
                        if(!items.isEmpty()) return items;
                    }
                }
                if(pkg.ncxPath()!=null) {
                    ZipEntry ncx=findZip(zip,pkg.ncxPath());
                    if(ncx!=null) try(InputStream x=zip.getInputStream(ncx)) {
                        List<TocItem> items=parseNcx(x);
                        if(!items.isEmpty()) return items;
                    }
                }
                return List.of();
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private EpubPackage readEpubPackage(ZipFile zip)throws Exception {
        String opfPath=locateOpf(zip);
        if(opfPath==null) return new EpubPackage(List.of(),null,null);
        ZipEntry opf=findZip(zip,opfPath);
        if(opf==null) return new EpubPackage(List.of(),null,null);

        Map<String,ManifestItem> manifest=new LinkedHashMap<>();
        List<String> spineIds=new ArrayList<>();
        String tocId=null;
        XMLInputFactory f=secureXmlFactory();
        try(InputStream in=zip.getInputStream(opf)) {
            XMLStreamReader r=f.createXMLStreamReader(in);
            try {
                while(r.hasNext()) {
                    int e=r.next();
                    if(e!=XMLStreamConstants.START_ELEMENT) continue;
                    String local=r.getLocalName();
                    if("item".equals(local)) {
                        String id=attr(r,"id"), href=attr(r,"href"), media=attr(r,"media-type"), props=attr(r,"properties");
                        if(id!=null&&href!=null) manifest.put(id,new ManifestItem(id,href,media,props));
                    } else if("spine".equals(local)) {
                        tocId=attr(r,"toc");
                    } else if("itemref".equals(local)) {
                        String idref=attr(r,"idref");
                        String linear=attr(r,"linear");
                        if(idref!=null&&!"no".equalsIgnoreCase(linear)) spineIds.add(idref);
                    }
                }
            } finally { r.close(); }
        }

        String base=parentZipPath(opfPath);
        List<String> spine=new ArrayList<>();
        for(String id:spineIds) {
            ManifestItem item=manifest.get(id);
            if(item!=null) spine.add(resolveZipPath(base,item.href()));
        }
        String navPath=null;
        for(ManifestItem item:manifest.values()) {
            if(item.properties()!=null&&Arrays.asList(item.properties().split("\\s+")).contains("nav")) {
                navPath=resolveZipPath(base,item.href());
                break;
            }
        }
        String ncxPath=null;
        if(tocId!=null&&manifest.containsKey(tocId)) ncxPath=resolveZipPath(base,manifest.get(tocId).href());
        if(ncxPath==null) {
            for(ManifestItem item:manifest.values()) {
                if("application/x-dtbncx+xml".equalsIgnoreCase(item.mediaType())) {
                    ncxPath=resolveZipPath(base,item.href());
                    break;
                }
            }
        }
        return new EpubPackage(List.copyOf(spine),navPath,ncxPath);
    }

    private String locateOpf(ZipFile zip)throws Exception {
        ZipEntry container=findZip(zip,"META-INF/container.xml");
        if(container==null) return null;
        XMLInputFactory f=secureXmlFactory();
        try(InputStream in=zip.getInputStream(container)) {
            XMLStreamReader r=f.createXMLStreamReader(in);
            try {
                while(r.hasNext()) {
                    if(r.next()==XMLStreamConstants.START_ELEMENT&&"rootfile".equals(r.getLocalName())) {
                        String path=attr(r,"full-path");
                        if(path!=null&&!path.isBlank()) return norm(path);
                    }
                }
            } finally { r.close(); }
        }
        return null;
    }

    private List<TocItem> parseEpubNav(InputStream in)throws Exception {
        XMLInputFactory f=secureXmlFactory();
        XMLStreamReader r=f.createXMLStreamReader(in);
        List<TocItem> out=new ArrayList<>();
        boolean inToc=false;
        int navDepth=0, listDepth=0;
        boolean inAnchor=false;
        StringBuilder text=new StringBuilder();
        long ordinal=0;
        try {
            while(r.hasNext()) {
                int e=r.next();
                if(e==XMLStreamConstants.START_ELEMENT) {
                    String n=r.getLocalName();
                    if("nav".equals(n)) {
                        String type=firstNonBlank(attr(r,"type"),attrByLocal(r,"type"));
                        if(type!=null&&(type.equalsIgnoreCase("toc")||Arrays.asList(type.split("\\s+")).contains("toc"))) {
                            inToc=true; navDepth=1; listDepth=0;
                        } else if(inToc) navDepth++;
                    } else if(inToc&&"ol".equals(n)) {
                        listDepth++;
                    } else if(inToc&&"a".equals(n)) {
                        inAnchor=true; text.setLength(0);
                    }
                } else if(e==XMLStreamConstants.CHARACTERS&&inAnchor) {
                    text.append(r.getText());
                } else if(e==XMLStreamConstants.END_ELEMENT) {
                    String n=r.getLocalName();
                    if(inToc&&"a".equals(n)&&inAnchor) {
                        String title=normalize(text.toString());
                        if(!title.isBlank()) out.add(new TocItem(Math.max(1,listDepth),title,ordinal++));
                        inAnchor=false;
                    } else if(inToc&&"ol".equals(n)) {
                        listDepth=Math.max(0,listDepth-1);
                    } else if(inToc&&"nav".equals(n)) {
                        navDepth--;
                        if(navDepth<=0) inToc=false;
                    }
                }
            }
        } finally { r.close(); }
        return out;
    }

    private List<TocItem> parseNcx(InputStream in)throws Exception {
        XMLInputFactory f=secureXmlFactory();
        XMLStreamReader r=f.createXMLStreamReader(in);
        List<TocItem> out=new ArrayList<>();
        int depth=0;
        boolean inText=false;
        StringBuilder text=new StringBuilder();
        long ordinal=0;
        try {
            while(r.hasNext()) {
                int e=r.next();
                if(e==XMLStreamConstants.START_ELEMENT) {
                    String n=r.getLocalName();
                    if("navPoint".equals(n)) depth++;
                    else if(depth>0&&"text".equals(n)) { inText=true; text.setLength(0); }
                } else if(e==XMLStreamConstants.CHARACTERS&&inText) {
                    text.append(r.getText());
                } else if(e==XMLStreamConstants.END_ELEMENT) {
                    String n=r.getLocalName();
                    if("text".equals(n)&&inText) {
                        String title=normalize(text.toString());
                        if(!title.isBlank()) out.add(new TocItem(Math.max(1,depth),title,ordinal++));
                        inText=false;
                    } else if("navPoint".equals(n)) depth=Math.max(0,depth-1);
                }
            }
        } finally { r.close(); }
        return out;
    }

    private List<String> fallbackHtmlEntries(ZipFile zip) {
        return Collections.list(zip.entries()).stream()
                .filter(e->!e.isDirectory())
                .map(ZipEntry::getName)
                .filter(n->{String x=n.toLowerCase(Locale.ROOT);return x.endsWith(".xhtml")||x.endsWith(".html")||x.endsWith(".htm");})
                .sorted()
                .toList();
    }

    private XMLInputFactory secureXmlFactory() {
        XMLInputFactory f=XMLInputFactory.newFactory();
        try{f.setProperty(XMLInputFactory.SUPPORT_DTD,false);}catch(Exception ignored){}
        try{f.setProperty("javax.xml.stream.isSupportingExternalEntities",false);}catch(Exception ignored){}
        try{f.setProperty(XMLInputFactory.IS_COALESCING,true);}catch(Exception ignored){}
        return f;
    }

    private String attr(XMLStreamReader r,String local) {
        String v=r.getAttributeValue(null,local);
        return v==null||v.isBlank()?null:v.trim();
    }
    private String attrByLocal(XMLStreamReader r,String local) {
        for(int i=0;i<r.getAttributeCount();i++) if(local.equals(r.getAttributeLocalName(i))) {
            String v=r.getAttributeValue(i); return v==null||v.isBlank()?null:v.trim();
        }
        return null;
    }
    private String firstNonBlank(String... values) {
        for(String v:values) if(v!=null&&!v.isBlank()) return v.trim();
        return null;
    }
    private String parentZipPath(String path) {
        String n=norm(path); int slash=n.lastIndexOf('/'); return slash<0?"":n.substring(0,slash);
    }
    private String resolveZipPath(String base,String href) {
        String raw=href==null?"":href;
        int hash=raw.indexOf('#'); if(hash>=0) raw=raw.substring(0,hash);
        int query=raw.indexOf('?'); if(query>=0) raw=raw.substring(0,query);
        try{raw=URLDecoder.decode(raw,StandardCharsets.UTF_8);}catch(Exception ignored){}
        String combined=base==null||base.isBlank()?raw:base+"/"+raw;
        Deque<String> parts=new ArrayDeque<>();
        for(String part:combined.replace('\\','/').split("/")) {
            if(part.isBlank()||".".equals(part)) continue;
            if("..".equals(part)){if(!parts.isEmpty())parts.removeLast();continue;}
            parts.addLast(part);
        }
        return String.join("/",parts);
    }

    private record ManifestItem(String id,String href,String mediaType,String properties){}
    private record EpubPackage(List<String> spinePaths,String navPath,String ncxPath){}
    private String stripRtf(String x) {
        return normalize(x.replaceAll("\\\\'[0-9a-fA-F]{2}", " ")
                .replaceAll("\\\\[a-zA-Z]+-?\\d* ?", " ")
                .replace('{', ' ').replace('}', ' '));
    }
    private String stripMarkup(String x){return normalize(x.replaceAll("(?is)<script.*?</script>"," ").replaceAll("(?is)<style.*?</style>"," ").replaceAll("<[^>]+>"," ").replace("&nbsp;"," ").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'"));}
    private String normalize(String s){return s.replace('\r',' ').replaceAll("[\\t ]+"," ").replaceAll(" *\\n *","\n").replaceAll("\\n{3,}","\n\n").trim();}
    private static String norm(String s){return s==null?"":s.replace('\\','/').replaceAll("^/+","");}
    private static String suffix(String n){String x=Path.of(norm(n)).getFileName().toString();int i=x.lastIndexOf('.');return i>=0?x.substring(i):".tmp";}
    record TocItem(int level,String title,long ordinal){}
}

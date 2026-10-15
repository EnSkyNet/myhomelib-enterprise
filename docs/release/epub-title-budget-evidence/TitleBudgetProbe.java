import com.myhomelibcorp.application.content.*;
import com.myhomelibcorp.infrastructure.content.EpubContentExtractor;
import java.io.InputStream;
import java.nio.file.*;
import java.util.OptionalLong;

/** Fresh-process reproduction of retained EPUB-title memory, without storing book text in output. */
public final class TitleBudgetProbe {
    public static void main(String[] args) throws Exception {
        Path path=Path.of(args[0]);
        var source=new ContentExtractionSource() {
            public String id() { return "title-stress"; }
            public String name() { return path.getFileName().toString(); }
            public InputStream openStream() throws java.io.IOException { return Files.newInputStream(path); }
            public OptionalLong size() { return OptionalLong.empty(); }
        };
        var result=new ContentExtractionService(java.util.List.of(new EpubContentExtractor()))
                .extract(ContentExtractionRequest.of(source),ContentExtractionContext.none());
        long titles=result.content()==null ? 0 : result.content().chapters().stream().mapToLong(c->c.title().length()).sum();
        System.out.println("status="+result.status()+"; retained_title_units="+titles+"; message="+result.message());
    }
}

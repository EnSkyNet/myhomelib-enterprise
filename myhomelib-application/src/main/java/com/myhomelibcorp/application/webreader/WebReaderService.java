package com.myhomelibcorp.application.webreader;

import com.myhomelibcorp.application.content.*;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.ReadingProgressDto;
import com.myhomelibcorp.application.port.out.repository.ReadingProgressRepository;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import com.myhomelibcorp.application.usecase.book.ResolvedBookContent;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Application boundary for the basic browser reader. Reuses the same extraction and progress storage as desktop. */
@Service
public class WebReaderService implements WebReaderUseCase {
    private static final SupportedFormatRegistry FORMATS = SupportedFormatRegistry.standard();
    private static final java.util.Set<String> WEB_FORMATS = java.util.Set.of("fb2", "epub");

    private final LoadBookByIdUseCase loadBook;
    private final ResolveBookContentUseCase resolveBookContent;
    private final ContentExtractionService extraction;
    private final ReadingProgressRepository progressRepository;

    public WebReaderService(LoadBookByIdUseCase loadBook,
                            ResolveBookContentUseCase resolveBookContent,
                            ContentExtractionService extraction,
                            ReadingProgressRepository progressRepository) {
        this.loadBook = Objects.requireNonNull(loadBook, "loadBook");
        this.resolveBookContent = Objects.requireNonNull(resolveBookContent, "resolveBookContent");
        this.extraction = Objects.requireNonNull(extraction, "extraction");
        this.progressRepository = Objects.requireNonNull(progressRepository, "progressRepository");
    }

    public Optional<WebReaderDocument> open(String bookId, int requestedChapter) throws IOException {
        BookDto book = load(bookId).orElse(null);
        if (book == null) return Optional.empty();
        String format = detectFormat(book);
        if (!WEB_FORMATS.contains(format)) {
            return Optional.of(new WebReaderDocument(bookId, book.getTitle(), format, false,
                    "Формат не підтримується Web Reader. Завантажте файл для читання локально.",
                    List.of(), 0, 0, 0L, existingPercent(bookId)));
        }

        try (ResolvedBookContent resolved = resolveBookContent.execute(book, ResolveBookContentUseCase.READER_EXTENSIONS)) {
            Path path = resolved.path();
            ContentExtractionResult result = extraction.extract(
                    new ContentExtractionRequest(new PathSource(bookId, path), format), ContentExtractionContext.none());
            if (!result.isSuccess()) {
                throw new IOException(result.message().isBlank() ? "Cannot extract book contents" : result.message());
            }
            ExtractedContent content = result.content();
            List<WebReaderChapter> chapters = toChapters(content.chapters());
            if (chapters.isEmpty()) throw new IOException("Book has no readable chapters");
            ReadingProgressDto saved = progressRepository.findByBookId(bookId).orElse(null);
            int resumeChapter = resolveResumeChapter(chapters, saved);
            long resumeOffset = saved == null ? 0L : clamp(saved.getAnchorId(), saved.getPercent(), chapters);
            int selected = requestedChapter < 0 ? resumeChapter : Math.max(0, Math.min(requestedChapter, chapters.size() - 1));
            double percent = saved == null ? 0.0 : saved.getPercent();
            return Optional.of(new WebReaderDocument(bookId, book.getTitle(), format, true, "",
                    chapters, selected, resumeChapter, resumeOffset, percent));
        }
    }

    public ReadingProgressDto saveProgress(String bookId, int chapterIndex, long absoluteOffset) throws IOException {
        WebReaderDocument doc = open(bookId, chapterIndex)
                .orElseThrow(() -> new IOException("Book not found"));
        if (!doc.supported() || doc.chapters().isEmpty()) throw new IOException("Web Reader format is unsupported");
        WebReaderChapter chapter = doc.currentChapter();
        long clamped = Math.max(chapter.startOffset(), Math.min(absoluteOffset, chapter.endOffset()));
        long total = doc.chapters().getLast().endOffset();
        int localOffset = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, clamped - chapter.startOffset()));
        ReadingProgressDto existing = progressRepository.findByBookId(bookId).orElse(null);
        ReadingProgressDto dto = ReadingProgressDto.builder()
                .bookId(bookId)
                .anchorId(chapter.index() + ":" + clamped + ":0:" + localOffset)
                .paragraphIndex(0)
                .paragraphId("web:" + chapter.id())
                .charOffset(localOffset)
                .percent(total <= 0 ? 0.0 : Math.min(100.0, (double) clamped / total * 100.0))
                .chapterTitle(chapter.title())
                .chapterId(chapter.id())
                .updatedAt(LocalDateTime.now())
                .readingTimeSeconds(existing == null ? 0L : existing.getReadingTimeSeconds())
                .lastDevice("web")
                .build();
        progressRepository.save(dto);
        return dto;
    }

    private Optional<BookDto> load(String bookId) {
        try { return loadBook.execute(BookId.fromString(bookId)); }
        catch (RuntimeException invalidId) { return Optional.empty(); }
    }

    private static String detectFormat(BookDto book) {
        String candidate = book.getArchiveEntry();
        if (candidate == null || candidate.isBlank()) candidate = book.getFileName();
        return FORMATS.detect(candidate == null ? "" : candidate)
                .map(f -> f.id().toLowerCase(Locale.ROOT)).orElse("");
    }

    private double existingPercent(String bookId) {
        return progressRepository.findByBookId(bookId).map(ReadingProgressDto::getPercent).orElse(0.0);
    }

    private static List<WebReaderChapter> toChapters(List<ExtractedChapter> source) {
        List<WebReaderChapter> result = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            ExtractedChapter chapter = source.get(i);
            result.add(new WebReaderChapter(i, chapter.id(), chapter.title(), chapter.startOffset(), chapter.endOffset(), chapter.text()));
        }
        return List.copyOf(result);
    }

    private static int resolveResumeChapter(List<WebReaderChapter> chapters, ReadingProgressDto saved) {
        if (saved == null) return 0;
        if (saved.getChapterId() != null && !saved.getChapterId().isBlank()) {
            for (WebReaderChapter chapter : chapters) if (saved.getChapterId().equals(chapter.id())) return chapter.index();
        }
        long absolute = parseAbsoluteOffset(saved.getAnchorId());
        if (absolute >= 0) {
            for (WebReaderChapter chapter : chapters) {
                if (absolute >= chapter.startOffset() && absolute <= chapter.endOffset()) return chapter.index();
            }
        }
        return 0;
    }

    private static long clamp(String anchorId, double percent, List<WebReaderChapter> chapters) {
        long parsed = parseAbsoluteOffset(anchorId);
        long total = chapters.getLast().endOffset();
        if (parsed >= 0) return Math.max(0L, Math.min(parsed, total));
        return Math.round(Math.max(0.0, Math.min(100.0, percent)) / 100.0 * total);
    }

    private static long parseAbsoluteOffset(String anchorId) {
        if (anchorId == null || anchorId.isBlank()) return -1L;
        try {
            String[] parts = anchorId.split(":");
            if (parts.length == 4) return Long.parseLong(parts[1]);
            if (parts.length == 1) return Long.parseLong(parts[0]);
        } catch (NumberFormatException ignored) { }
        return -1L;
    }

    private record PathSource(String id, Path path) implements ContentExtractionSource {
        private PathSource {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(path, "path");
        }
        @Override public String name() { return path.getFileName() == null ? id : path.getFileName().toString(); }
        @Override public InputStream openStream() throws IOException { return Files.newInputStream(path); }
        @Override public OptionalLong size() {
            try { return OptionalLong.of(Files.size(path)); }
            catch (IOException ignored) { return OptionalLong.empty(); }
        }
    }
}

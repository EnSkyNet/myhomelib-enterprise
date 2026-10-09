package com.myhomelibcorp.ui.integration;

import com.myhomelibcorp.application.ai.AiRequest;
import com.myhomelibcorp.application.content.ContentExtractionResult;
import com.myhomelibcorp.application.content.ContentExtractionService;
import com.myhomelibcorp.application.content.ExtractedContent;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import com.myhomelibcorp.application.usecase.book.ResolvedBookContent;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiBookAssistantUiServiceUnicodeTest {
    @TempDir Path temp;

    @Test
    void largeBookSamplingDoesNotSplitSurrogatePairAtHeadBoundary() throws Exception {
        int limit = AiRequest.MAX_BOOK_CONTENT_CHARS;
        int head = (int) (limit * 0.7);
        String text = "a".repeat(head - 1) + "😀" + "b".repeat(limit - head + 2_000);

        String sampled = extractSample(text);

        assertThat(hasUnpairedSurrogate(sampled)).isFalse();
    }

    @Test
    void largeBookSamplingDoesNotDetachCombiningMarkAtHeadBoundary() throws Exception {
        int limit = AiRequest.MAX_BOOK_CONTENT_CHARS;
        int head = (int) (limit * 0.7);
        String text = "a".repeat(head - 2) + "Xe\u0301" + "b".repeat(limit - head + 2_000);

        String sampled = extractSample(text);

        assertThat(sampled).doesNotContain("Xe");
    }

    @Test
    void largeBookSamplingDoesNotSplitSurrogatePairAtTailBoundary() throws Exception {
        int limit = AiRequest.MAX_BOOK_CONTENT_CHARS;
        int head = (int) (limit * 0.7);
        int tail = limit - head - 120;
        int totalLength = limit + 4_000;
        int tailStart = totalLength - tail;
        String text = "a".repeat(tailStart - 1) + "😀" + "b".repeat(totalLength - tailStart - 1);

        String sampled = extractSample(text);

        assertThat(hasUnpairedSurrogate(sampled)).isFalse();
    }

    private String extractSample(String text) throws Exception {
        IntegrationBackend backend = mock(IntegrationBackend.class);
        ResolveBookContentUseCase resolver = mock(ResolveBookContentUseCase.class);
        ContentExtractionService extraction = mock(ContentExtractionService.class);
        UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        DialogService dialogs = mock(DialogService.class);
        ApplicationState state = mock(ApplicationState.class);
        AiBookAssistantUiService service = new AiBookAssistantUiService(
                backend, resolver, extraction, background, dialogs, state);

        BookDto book = BookDto.builder().id("book-1").title("Unicode").build();
        Path source = temp.resolve("book.txt");
        Files.writeString(source, "fixture");
        when(resolver.execute(any(BookDto.class), anySet())).thenReturn(new ResolvedBookContent(source, false));
        when(extraction.extract(any(), any())).thenReturn(ContentExtractionResult.success(
                "fixture", new ExtractedContent("book-1", "txt", text, List.of())));

        Method extract = AiBookAssistantUiService.class.getDeclaredMethod("extract", BookDto.class);
        extract.setAccessible(true);
        Object result = extract.invoke(service, book);
        Method textAccessor = result.getClass().getDeclaredMethod("text");
        textAccessor.setAccessible(true);
        return (String) textAccessor.invoke(result);
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }
}

package com.myhomelibcorp.infrastructure.ai;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.myhomelibcorp.application.ai.AiOperation;
import com.myhomelibcorp.application.ai.AiRequest;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OpenAiResponsesProviderPromptTest {

    @Test
    void promptDoesNotForceUkrainianAndPreservesEnglishUserRequest() throws Exception {
        OpenAiResponsesProvider provider = new OpenAiResponsesProvider(
                mock(ApplicationSettingsPort.class), new ObjectMapper());
        AiRequest request = new AiRequest("book-1", AiOperation.SUMMARY,
                "Summarize this chapter in concise English.", "Chapter text");

        ObjectNode body = requestBody(provider, request);
        String developerText = body.path("input").path(0).path("content").path(0).path("text").asString();
        String userText = body.path("input").path(1).path("content").path(0).path("text").asString();

        assertThat(developerText)
                .contains("Answer in the language of the user's request")
                .doesNotContain("українською")
                .doesNotContain("Ukrainian");
        assertThat(userText)
                .contains("Summarize this chapter in concise English.")
                .contains("Chapter text")
                .doesNotContain("Відповідай українською");
    }

    @Test
    void promptDoesNotOverrideBulgarianQuestionLanguage() throws Exception {
        OpenAiResponsesProvider provider = new OpenAiResponsesProvider(
                mock(ApplicationSettingsPort.class), new ObjectMapper());
        AiRequest request = new AiRequest("book-2", AiOperation.QUESTION_ANSWER,
                "Отговори кратко: кой е главният герой?", "Текст на книгата");

        ObjectNode body = requestBody(provider, request);
        String userText = body.path("input").path(1).path("content").path(0).path("text").asString();

        assertThat(userText)
                .contains("Отговори кратко: кой е главният герой?")
                .contains("Текст на книгата")
                .doesNotContain("Відповідай українською");
    }

    private static ObjectNode requestBody(OpenAiResponsesProvider provider, AiRequest request) throws Exception {
        Method method = OpenAiResponsesProvider.class
                .getDeclaredMethod("requestBody", String.class, int.class, AiRequest.class);
        method.setAccessible(true);
        return (ObjectNode) method.invoke(provider, "test-model", 256, request);
    }
}

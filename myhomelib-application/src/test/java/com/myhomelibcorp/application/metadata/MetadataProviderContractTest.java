package com.myhomelibcorp.application.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataProviderContractTest {

    static Stream<MetadataProvider> providers() {
        return Stream.of(new MockProvider("mock-a", "Mock A"), new MockProvider("mock-b", "Mock B"));
    }

    @ParameterizedTest
    @MethodSource("providers")
    void providersAreInterchangeableForIsbnTitleAndAuthorQueries(MetadataProvider provider) throws Exception {
        MetadataRequestContext context = MetadataRequestContext.create(Duration.ofSeconds(2), new AtomicBoolean(false));

        assertContract(provider, MetadataQuery.byIsbn("9780132350884"), context);
        assertContract(provider, MetadataQuery.byTitle("Clean Code"), context);
        assertContract(provider, MetadataQuery.byAuthor("Robert C. Martin"), context);
    }

    @ParameterizedTest
    @MethodSource("providers")
    void providersUseOneCancellationContract(MetadataProvider provider) {
        MetadataRequestContext cancelled = MetadataRequestContext.create(
                Duration.ofSeconds(2), new AtomicBoolean(true));

        assertThatThrownBy(() -> provider.search(MetadataQuery.byTitle("Clean Code"), cancelled))
                .isInstanceOf(MetadataProviderException.class)
                .satisfies(error -> assertThat(((MetadataProviderException) error).kind())
                        .isEqualTo(MetadataProviderErrorKind.CANCELLED));
    }

    @Test
    void queryNormalizesValidIsbnAndRejectsInvalidOrEmptyCriteria() {
        assertThat(MetadataQuery.byIsbn("978-0-13-235088-4").isbn()).isEqualTo("9780132350884");
        assertThatThrownBy(() -> MetadataQuery.byIsbn("not-an-isbn"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MetadataQuery("", " ", null, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void candidateIsImmutableAndRequiresNormalizedConfidenceAndSource() {
        MetadataCandidate candidate = candidate("mock", "Mock", 0.9, "id-1");
        assertThat(candidate.authors()).containsExactly("Robert C. Martin");
        assertThatThrownBy(() -> candidate("mock", "Mock", 1.01, "id-2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertContract(
            MetadataProvider provider,
            MetadataQuery query,
            MetadataRequestContext context) throws Exception {
        List<MetadataCandidate> candidates = provider.search(query, context);

        assertThat(candidates).hasSize(1);
        MetadataCandidate candidate = candidates.getFirst();
        assertThat(candidate.source().providerId()).isEqualTo(provider.id());
        assertThat(candidate.source().providerName()).isEqualTo(provider.displayName());
        assertThat(candidate.confidence()).isBetween(0.0, 1.0);
        assertThat(candidate.title()).isNotBlank();
    }

    private static MetadataCandidate candidate(String id, String name, double confidence, String recordId) {
        return new MetadataCandidate(
                new MetadataSource(id, name, recordId, "https://example.invalid/" + recordId),
                confidence,
                "Clean Code",
                List.of(" Robert C. Martin ", "Robert C. Martin"),
                "9780132350884",
                2008,
                "Prentice Hall",
                "en",
                "A handbook of agile software craftsmanship.",
                "https://example.invalid/cover.jpg");
    }

    private static final class MockProvider implements MetadataProvider {
        private final String id;
        private final String name;

        private MockProvider(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return name;
        }

        @Override
        public List<MetadataCandidate> search(
                MetadataQuery query,
                MetadataRequestContext context) throws MetadataProviderException {
            context.throwIfStopped();
            if (!query.hasIsbn() && !query.hasTitle() && !query.hasAuthor()) {
                throw new MetadataProviderException(MetadataProviderErrorKind.INVALID_RESPONSE, "query is empty");
            }
            return List.of(candidate(id, name, 0.87, id + "-record"));
        }
    }
}

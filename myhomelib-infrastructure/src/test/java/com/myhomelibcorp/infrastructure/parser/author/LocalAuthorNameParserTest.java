package com.myhomelibcorp.infrastructure.parser.author;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAuthorNameParserTest {

    @Test
    void repairsSwappedCyrillicFirstAndLastNameWhenSurnameSignalIsStrong() {
        var authors = LocalAuthorNameParser.fromStructured("Дорничев", "", "Дмитрий");

        assertThat(authors).hasSize(1);
        assertThat(authors.getFirst().getFirstName()).isEqualTo("Дмитрий");
        assertThat(authors.getFirst().getLastName()).isEqualTo("Дорничев");
        assertThat(authors.getFirst().getFullName()).isEqualTo("Дорничев Дмитрий");
    }

    @Test
    void keepsCorrectStructuredOrder() {
        var authors = LocalAuthorNameParser.fromStructured("Дмитрий", "", "Дорничев");

        assertThat(authors).hasSize(1);
        assertThat(authors.getFirst().getFirstName()).isEqualTo("Дмитрий");
        assertThat(authors.getFirst().getLastName()).isEqualTo("Дорничев");
    }

    @Test
    void splitsTwoConcatenatedCyrillicCreatorsInEitherOrder() {
        var firstOrder = LocalAuthorNameParser.parseCreators("Дмитрий Дорничев Алексей Ковтунов");
        var lastOrder = LocalAuthorNameParser.parseCreators("Дорничев Дмитрий Ковтунов Алексей");

        assertThat(firstOrder).extracting(a -> a.getFullName())
                .containsExactly("Дорничев Дмитрий", "Ковтунов Алексей");
        assertThat(lastOrder).extracting(a -> a.getFullName())
                .containsExactly("Дорничев Дмитрий", "Ковтунов Алексей");
    }

    @Test
    void doesNotSplitNormalFourPartNameWithPatronymicSignal() {
        var authors = LocalAuthorNameParser.parseCreators("Иван Петрович де Сильва");

        assertThat(authors).hasSize(1);
    }

    @Test
    void splitsExplicitMultipleCreatorSeparators() {
        var authors = LocalAuthorNameParser.parseCreators("Дмитрий Дорничев; Алексей Ковтунов");

        assertThat(authors).extracting(a -> a.getFullName())
                .containsExactly("Дорничев Дмитрий", "Ковтунов Алексей");
    }

    @Test
    void splitsBothConcatenatedAuthorRowsObservedInLocalNavigation() {
        // Author.getFullName() displays last + first + middle, so these structured layouts
        // correspond to the two malformed rows visible in the local-author facet.
        var surnameFirstRow = LocalAuthorNameParser.fromStructured(
                "Дмитрий", "Ковтунов Алексей", "Дорничев");
        var nameFirstRow = LocalAuthorNameParser.fromStructured(
                "Дорничев", "Алексей Ковтунов", "Дмитрий");

        assertThat(surnameFirstRow).extracting(a -> a.getFullName())
                .containsExactly("Дорничев Дмитрий", "Ковтунов Алексей");
        assertThat(nameFirstRow).extracting(a -> a.getFullName())
                .containsExactly("Дорничев Дмитрий", "Ковтунов Алексей");
    }

    @Test
    void repairsMalformedStructuredSecondAuthorStoredInMiddleName() {
        var authors = LocalAuthorNameParser.fromStructured("Дмитрий", "Ковтунов Алексей", "Дорничев");

        assertThat(authors).extracting(a -> a.getFullName())
                .containsExactly("Дорничев Дмитрий", "Ковтунов Алексей");
    }
}

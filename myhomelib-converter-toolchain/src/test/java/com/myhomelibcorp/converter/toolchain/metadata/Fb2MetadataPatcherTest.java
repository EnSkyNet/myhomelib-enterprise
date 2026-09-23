package com.myhomelibcorp.converter.toolchain.metadata;

import com.myhomelibcorp.converter.toolchain.ConversionFailureKind;
import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Fb2MetadataPatcherTest {
    @TempDir Path tempDir;
    private final Fb2MetadataPatcher patcher = new Fb2MetadataPatcher();

    @Test
    void fillsMissingValuesAndAddsOnlyMissingAuthorsAndGenres() throws Exception {
        Path fb2 = write("book.fb2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description>
                    <title-info>
                      <genre>sf</genre>
                      <author><first-name>Existing</first-name><last-name>Author</last-name></author>
                      <book-title></book-title>
                      <annotation></annotation>
                      <lang></lang>
                      <sequence name="" number=""/>
                    </title-info>
                    <publish-info><publisher/><year/><isbn/></publish-info>
                  </description>
                  <body><section><p>Body</p></section></body>
                </FictionBook>
                """);
        CanonicalBookMetadata metadata = metadata();

        patcher.patchMissing(fb2, metadata);

        String xml = Files.readString(fb2, StandardCharsets.UTF_8);
        assertThat(xml).contains("Canonical title", "Annotation", ">uk<", "Publisher", ">2026<", "9780000000001");
        assertThat(xml).contains("name=\"Series\"", "number=\"2\"");
        assertThat(count(xml, "<book-title")).isEqualTo(1);
        assertThat(count(xml, "<genre")).isEqualTo(2);
        assertThat(xml).contains(">sf<", ">fantasy<");
        assertThat(count(xml, "<author")).isEqualTo(2);
        assertThat(xml).contains("Existing", "Author", "New", "Writer");
    }

    @Test
    void preservesMultipleSequenceHierarchyInsteadOfFlatteningIt() throws Exception {
        Path fb2 = write("series.fb2", """
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description><title-info>
                    <genre>sf</genre><author><nickname>A</nickname></author><book-title>T</book-title><lang>uk</lang>
                    <sequence name="Outer"/><sequence name="Inner" number="7"/>
                  </title-info></description><body><section><p>x</p></section></body>
                </FictionBook>
                """);

        patcher.patchMissing(fb2, metadata());

        String xml = Files.readString(fb2, StandardCharsets.UTF_8);
        assertThat(count(xml, "<sequence")).isEqualTo(2);
        assertThat(xml).contains("name=\"Outer\"", "name=\"Inner\"", "number=\"7\"");
        assertThat(xml).doesNotContain("name=\"Series\"");
    }


    @Test
    void structuredButEmptyAuthorIsPreservedAndCanonicalAuthorIsAddedSeparately() throws Exception {
        Path fb2 = write("structured-empty-author.fb2", """
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description><title-info>
                    <genre>sf</genre><author><first-name/></author><book-title>Existing title</book-title><lang>uk</lang>
                  </title-info></description><body><section><p>x</p></section></body>
                </FictionBook>
                """);
        CanonicalBookMetadata metadata = new CanonicalBookMetadata("Canonical title", List.of(
                new CanonicalBookMetadata.CanonicalAuthor("New", "", "Writer")),
                List.of("sf"), "", null, "", "uk", "", null, "", "",
                new CanonicalBookMetadata.CanonicalCover("", new byte[0]));

        patcher.patchMissing(fb2, metadata);

        String xml = Files.readString(fb2, StandardCharsets.UTF_8);
        assertThat(count(xml, "<author")).isEqualTo(2);
        assertThat(count(xml, "<first-name")).isEqualTo(2);
        assertThat(xml).contains("<first-name></first-name>", ">New<", ">Writer<");
        assertThat(xml).contains(">Existing title<").doesNotContain(">Canonical title<");
    }

    @Test
    void existingRicherMetadataWinsOverCanonicalSnapshot() throws Exception {
        Path fb2 = write("richer.fb2", """
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description><title-info>
                    <genre>sf</genre><author><first-name>Rich</first-name><last-name>Author</last-name></author>
                    <book-title>Richer source title</book-title><annotation><p>Richer annotation</p></annotation>
                    <lang>pl</lang><sequence name="Richer Series" number="9"/>
                  </title-info><publish-info><publisher>Richer Publisher</publisher><year>2024</year><isbn>rich-isbn</isbn></publish-info></description>
                  <body><section><p>x</p></section></body>
                </FictionBook>
                """);

        patcher.patchMissing(fb2, metadata());

        String xml = Files.readString(fb2, StandardCharsets.UTF_8);
        assertThat(xml).contains("Richer source title", "Richer annotation", ">pl<", "Richer Series", "number=\"9\"",
                "Richer Publisher", ">2024<", "rich-isbn");
        assertThat(xml).doesNotContain("Canonical title", "name=\"Series\"", "number=\"2\"", "9780000000001");
    }

    @Test
    void rejectsDtdInsteadOfResolvingEntities() throws Exception {
        Path fb2 = write("unsafe.fb2", """
                <?xml version="1.0"?>
                <!DOCTYPE FictionBook [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description><title-info><book-title>&xxe;</book-title></title-info></description><body/>
                </FictionBook>
                """);

        assertThatThrownBy(() -> patcher.patchMissing(fb2, metadata()))
                .isInstanceOf(ConversionToolchainException.class)
                .satisfies(error -> assertThat(((ConversionToolchainException) error).kind())
                        .isEqualTo(ConversionFailureKind.SECURITY_REJECTED));
    }

    @Test
    void mapsCooperativeCancellationToCancelledFailure() throws Exception {
        Path fb2 = write("cancel.fb2", """
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description><title-info><book-title></book-title></title-info></description>
                  <body><section><p>Body</p></section></body>
                </FictionBook>
                """);

        assertThatThrownBy(() -> patcher.patchMissing(fb2, metadata(), () -> true))
                .isInstanceOf(ConversionToolchainException.class)
                .satisfies(error -> assertThat(((ConversionToolchainException) error).kind())
                        .isEqualTo(ConversionFailureKind.CANCELLED));
    }

    private CanonicalBookMetadata metadata() {
        return new CanonicalBookMetadata("Canonical title", List.of(
                new CanonicalBookMetadata.CanonicalAuthor("Existing", "", "Author"),
                new CanonicalBookMetadata.CanonicalAuthor("New", "", "Writer")),
                List.of("sf", "fantasy"), "Series", 2, "Annotation", "uk",
                "9780000000001", 2026, "Publisher", "Translator",
                new CanonicalBookMetadata.CanonicalCover("", new byte[0]));
    }

    private Path write(String name, String value) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, value, StandardCharsets.UTF_8);
        return file;
    }

    private static int count(String value, String needle) {
        int result = 0, index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) { result++; index += needle.length(); }
        return result;
    }
}

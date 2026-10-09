package com.myhomelibcorp.infrastructure.image;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class Fb2CoverParserMemorySafetyTest {
    @Test
    void skipsHugeEmbeddedImageAndFindsLaterDeclaredCover() {
        byte[] valid = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        String book = "<FictionBook xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
                + "<description><title-info><coverpage><image xlink:href=\"#small\"/>"
                + "</coverpage></title-info></description>"
                + "<binary id=\"huge.jpg\" content-type=\"image/jpeg\">"
                + "A".repeat(15_000_000) + "</binary>"
                + "<binary id=\"small\" content-type=\"image/jpeg\">"
                + Base64.getEncoder().encodeToString(valid) + "</binary></FictionBook>";
        assertThat(new Fb2CoverParser().parseToBytes(
                new ByteArrayInputStream(book.getBytes(StandardCharsets.UTF_8))))
                .containsExactly(valid);
    }

    @Test
    void rejectsOversizeDeclaredCoverWithoutDecodingIt() {
        String book = "<FictionBook xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
                + "<description><title-info><coverpage><image xlink:href=\"#large\"/>"
                + "</coverpage></title-info></description>"
                + "<binary id=\"large\" content-type=\"image/jpeg\">"
                + "A".repeat(15_000_000) + "</binary></FictionBook>";
        assertThat(new Fb2CoverParser().parseToBytes(
                new ByteArrayInputStream(book.getBytes(StandardCharsets.UTF_8))))
                .isNull();
    }
}

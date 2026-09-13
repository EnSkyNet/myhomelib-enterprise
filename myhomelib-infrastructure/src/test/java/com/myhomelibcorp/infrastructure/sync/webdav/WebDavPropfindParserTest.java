package com.myhomelibcorp.infrastructure.sync.webdav;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebDavPropfindParserTest {
    private final WebDavPropfindParser parser = new WebDavPropfindParser();

    @Test
    void readsDavHrefValues() {
        byte[] xml = ("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">" +
                "<d:response><d:href>/sync/a.mhl-sync.enc</d:href></d:response>" +
                "<d:response><d:href>/sync/b.mhl-sync.enc</d:href></d:response>" +
                "</d:multistatus>").getBytes(StandardCharsets.UTF_8);

        assertThat(parser.hrefs(xml)).containsExactly("/sync/a.mhl-sync.enc", "/sync/b.mhl-sync.enc");
    }

    @Test
    void rejectsDtdPayloads() {
        byte[] xml = ("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
                "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>&xxe;</d:href></d:response></d:multistatus>")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parser.hrefs(xml)).isInstanceOf(IllegalArgumentException.class);
    }
}

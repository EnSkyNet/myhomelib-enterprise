package com.myhomelibcorp.infrastructure.sync.webdav;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/** Small testable HTTP boundary for WebDAV-specific methods. */
public interface WebDavHttpClient {
    Response exchange(String method, URI uri, Map<String, String> headers, byte[] body) throws IOException, InterruptedException;

    record Response(int statusCode, Map<String, List<String>> headers, byte[] body) {
        public Response {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? new byte[0] : body.clone();
        }

        @Override public byte[] body() { return body.clone(); }
    }
}

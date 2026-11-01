import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.TimeValue;
import org.xmlresolver.ResolverFeature;
import org.xmlresolver.ResourceConnection;
import org.xmlresolver.XMLResolverConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class HttpClientPoolLeakProbe {
    private HttpClientPoolLeakProbe() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("xmlresolver.properties", "");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/unsupported", exchange -> {
            requests.incrementAndGet();
            byte[] body = "unsupported".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Encoding", "whatever");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/valid", exchange -> {
            requests.incrementAndGet();
            byte[] body = "<root/>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;

        try {
            verifyXmlResolverRemoteFetch(base + "/valid");
            verifyConnectionPoolRecovery(base, requests);
            if (requests.get() != 17) {
                throw new AssertionError("Unexpected HTTP request count: " + requests.get());
            }
            System.out.println("PASS: XMLResolver remote fetch works; 8 unsupported Content-Encoding responses "
                    + "released the one-slot pool; 8 subsequent valid requests succeeded.");
        } finally {
            server.stop(0);
        }
    }

    private static void verifyXmlResolverRemoteFetch(String url) throws Exception {
        XMLResolverConfiguration config = new XMLResolverConfiguration();
        config.setFeature(ResolverFeature.CLASSPATH_CATALOGS, false);
        ResourceConnection connection = new ResourceConnection(config, url);
        try {
            if (connection.getStatusCode() != 200) {
                throw new AssertionError("XMLResolver HTTP status: " + connection.getStatusCode());
            }
            InputStream stream = connection.getStream();
            if (stream == null) {
                throw new AssertionError("XMLResolver returned no remote resource stream");
            }
            try (stream) {
                if (stream.readAllBytes().length == 0) {
                    throw new AssertionError("XMLResolver returned an empty resource");
                }
            }
        } finally {
            connection.close();
        }
    }

    private static void verifyConnectionPoolRecovery(String base, AtomicInteger requests) throws Exception {
        PoolingHttpClientConnectionManager manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(TimeValue.ofSeconds(2))
                .setResponseTimeout(TimeValue.ofSeconds(2))
                .build();

        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(requestConfig)
                .build()) {
            for (int i = 0; i < 8; i++) {
                try (CloseableHttpResponse ignored = client.execute(new HttpGet(base + "/unsupported"))) {
                    throw new AssertionError("Unsupported Content-Encoding was accepted");
                } catch (IOException expected) {
                    // The patched classic client must close/discard the failed response.
                }
                if (manager.getTotalStats().getLeased() != 0) {
                    throw new AssertionError("Connection remained leased after Content-Encoding failure");
                }

                try (CloseableHttpResponse response = client.execute(new HttpGet(base + "/valid"))) {
                    if (response.getCode() != 200) {
                        throw new AssertionError("Recovery response status: " + response.getCode());
                    }
                    EntityUtils.consume(response.getEntity());
                }
                if (manager.getTotalStats().getLeased() != 0) {
                    throw new AssertionError("Connection remained leased after valid response close");
                }
            }
        }
    }
}

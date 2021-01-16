package tech.intac.devtools.cachingproxy;

import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;
import java.util.Properties;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.util.security.Credential;

public class ProxyServlet extends HttpServlet {

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        var config = Config.getInstance();
        var requestMethod = request.getMethod().toLowerCase();

        if (("get".equals(requestMethod) && config.cacheGetRequests) ||
                ("post".equals(requestMethod) && config.cachePostRequests)) {
            try {
                passThrough(config, request, response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return;
        }

        var reqBody = String.join("\n", IOUtils.readLines(request.getInputStream()));

        var requestId = request.getQueryString() + reqBody;
        var checksum = Credential.MD5.digest(requestId).replace(":", "_");
        var reqUri = request.getRequestURI().toString();

        var localPath = LocalCacheResolver.resolve(new URL(config.baseUrl + reqUri));

        var localResponseFolder = config.
                localOverridesPath.resolve(localPath)
                .resolve(checksum);

        if (!Files.exists(localResponseFolder)) {
            localResponseFolder.toFile().mkdirs();
        }

        var cachedRespHeadersPath = localResponseFolder.resolve("response_headers");
        var cachedRespContentPath = localResponseFolder.resolve("response_body");
        var cachedRespHeaders = new Properties();

        if (Files.exists(localResponseFolder)) {
            if (Files.exists(cachedRespHeadersPath)) {
                try (var reader = new FileReader(cachedRespHeadersPath.toFile())) {
                    cachedRespHeaders.load(reader);
                    cachedRespHeaders.forEach((key, value) -> response.setHeader(key.toString(), value + ""));
                }
            }
            if (Files.exists(cachedRespContentPath)) {
                Files.copy(cachedRespContentPath, response.getOutputStream());
                return; // end response
            }
        }

        try {
            var client = HttpClient.newBuilder().build();
            var remoteUri = new URI(config.baseUrl + request.getRequestURI() +
                    (request.getQueryString() != null ? "?" + request.getQueryString() : ""));

            var reqbldr = HttpRequest.newBuilder()
                    .uri(remoteUri);

            if ("get".equals(requestMethod)) {
                reqbldr.GET();
            } else {
                reqbldr.POST(HttpRequest.BodyPublishers.ofString(reqBody));
            }

            var cachedReqHeadersPath = localResponseFolder.resolve("request_headers");
            var cachedReqContentPath = localResponseFolder.resolve("request_body");
            var cachedReqHeaders = new Properties();

            request.getHeaderNames().asIterator()
                    .forEachRemaining(headerName -> {
                        try {
                            var value = request.getHeader(headerName);
                            cachedReqHeaders.setProperty(headerName, value);
                            reqbldr.header(headerName, value);
                        } catch (Exception ex) {
                            log("Ingored header: " + headerName);
                        }
                    });

            var remoteResponse = client.send(reqbldr.build(), HttpResponse.BodyHandlers.ofFile(cachedRespContentPath));
            remoteResponse.headers().map()
                    .forEach((k, v) -> cachedRespHeaders.put(k, v.size() > 0 ? v.get(0) : ""));

            try (OutputStream os = new FileOutputStream(cachedRespHeadersPath.toFile())) {
                cachedRespHeaders.store(os, "cached headers @ " + new Date());
            }

            Files.copy(cachedRespContentPath, response.getOutputStream());

            // store the request
            try (OutputStream os = new FileOutputStream(cachedReqHeadersPath.toFile())) {
                cachedReqHeaders.store(os, "cached headers @ " + new Date());
            }

            Files.write(cachedReqContentPath, reqBody.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private void passThrough(Config config, HttpServletRequest request, HttpServletResponse response) throws Exception {
        var client = HttpClient.newBuilder().build();
        var remoteUri = new URI(config.baseUrl + request.getRequestURI() +
                (request.getQueryString() != null ? "?" + request.getQueryString() : ""));

        var reqbldr = HttpRequest.newBuilder()
                .uri(remoteUri);

        if (request.getMethod().equalsIgnoreCase("get")) {
            reqbldr.GET();
        } else {
            reqbldr.POST(HttpRequest.BodyPublishers.ofInputStream(() -> {
                try {
                    return request.getInputStream();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        request.getHeaderNames().asIterator()
                .forEachRemaining(headerName -> {
                    try {
                        var value = request.getHeader(headerName);
                        reqbldr.header(headerName, value);
                    } catch (Exception ex) {
                        log("Ingored header: " + headerName);
                    }
                });

        var remoteResponse = client.send(reqbldr.build(), HttpResponse.BodyHandlers.ofInputStream());
        IOUtils.copyLarge(remoteResponse.body(), response.getOutputStream());
    }
}

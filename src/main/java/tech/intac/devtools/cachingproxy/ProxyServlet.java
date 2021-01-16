package tech.intac.devtools.cachingproxy;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;

public class ProxyServlet extends HttpServlet {

    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        var config = Config.getInstance();
        var requestMethod = request.getMethod().toLowerCase();
        var reqBody = String.join("\n", IOUtils.readLines(request.getInputStream()));

        checkIfPassThrough:
        {
            if ("get".equals(requestMethod) && config.isCacheGetRequests()) {
                break checkIfPassThrough;
            }

            if ("post".equals(requestMethod) && config.isCachePostRequests()) {
                break checkIfPassThrough;
            }

            try {
                var remoteResponse = sendRemoteRequest(config, request, reqBody);
                IOUtils.copyLarge(remoteResponse.body(), response.getOutputStream());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return;
        }

        var cacheFolderName = LocalCacheResolver.generateCacheFolderName(request, requestMethod, reqBody);
        var localPath = LocalCacheResolver.resolve(new URL(config.getBaseUrl() + request.getRequestURI()));

        var localResponseFolder = config.
                getLocalOverridesPath().resolve(localPath)
                .resolve(cacheFolderName);

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
            var remoteResponse = sendRemoteRequest(config, request, reqBody);
            remoteResponse.headers().map()
                    .forEach((k, v) -> cachedRespHeaders.put(k, v.size() > 0 ? v.get(0) : ""));

            // cache the response headers
            try (OutputStream os = new FileOutputStream(cachedRespHeadersPath.toFile())) {
                cachedRespHeaders.store(os, "cached headers @ " + new Date());
            }

            // cache the response
            try (OutputStream os = new FileOutputStream(cachedRespContentPath.toFile())) {
                IOUtils.copyLarge(remoteResponse.body(), os);
            }

            // send the file to the http request
            try (InputStream is = new FileInputStream(cachedRespContentPath.toFile())) {
                IOUtils.copyLarge(is, response.getOutputStream());
            }

            // store the request details
            var cachedReqHeadersPath = localResponseFolder.resolve("request_headers");
            var cachedReqContentPath = localResponseFolder.resolve("request_body");
            var cachedReqHeaders = new Properties();

            request.getHeaderNames().asIterator()
                    .forEachRemaining(headerName -> {
                        var value = request.getHeader(headerName);
                        cachedReqHeaders.setProperty(headerName, value);
                    });

            try (OutputStream os = new FileOutputStream(cachedReqHeadersPath.toFile())) {
                cachedReqHeaders.store(os, "cached headers @ " + new Date());
            }

            try (OutputStream os = new FileOutputStream(cachedReqContentPath.toFile())) {
                var contentInBytes = reqBody.getBytes(StandardCharsets.UTF_8);
                IOUtils.copyLarge(new ByteArrayInputStream(contentInBytes), os);
            }
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private HttpResponse<InputStream> sendRemoteRequest(Config config, HttpServletRequest request, String reqBody) throws Exception {
        var client = HttpClient.newBuilder().build();
        var remoteUri = new URI(config.getBaseUrl() + request.getRequestURI() +
                (request.getQueryString() != null ? "?" + request.getQueryString() : ""));

        var reqbldr = HttpRequest.newBuilder()
                .uri(remoteUri);

        if (request.getMethod().equalsIgnoreCase("get")) {
            reqbldr.GET();
        } else {
            reqbldr.POST(HttpRequest.BodyPublishers.ofString(reqBody));
        }

        request.getHeaderNames().asIterator()
                .forEachRemaining(headerName -> {
                    try {
                        var value = request.getHeader(headerName);
                        reqbldr.header(headerName, value);
                    } catch (Exception ex) {
                        // do nothing
                    }
                });

        return client.send(reqbldr.build(), HttpResponse.BodyHandlers.ofInputStream());
    }
}

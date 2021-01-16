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
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;

public class ProxyServlet extends HttpServlet {

    private final Map<String, String> cachedResponses = new HashMap<>();
    private final Map<String, Properties> cachedHeaders = new HashMap<>();

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

        var reqCacheFolder = LocalCacheResolver.generateCacheFolderName(request, reqBody);
        var reqCacheParentFolder = LocalCacheResolver.resolve(new URL(config.getBaseUrl() + request.getRequestURI()));

        var reqCacheAbsoluteFolder = config.getLocalOverridesPath()
                .resolve(reqCacheParentFolder)
                .resolve(reqCacheFolder);

        if (!Files.exists(reqCacheAbsoluteFolder)) {
            reqCacheAbsoluteFolder.toFile().mkdirs();
        }

        // check from in-memory cache
        String inMemCacheId = reqCacheAbsoluteFolder.toString();
        if (cachedResponses.containsKey(inMemCacheId)) {
            cachedHeaders.get(inMemCacheId).forEach((k, v) -> response.setHeader(k.toString(), v.toString()));
            response.getWriter().println(cachedResponses.get(inMemCacheId));
            return;
        }

        var cachedRespHeadersPath = reqCacheAbsoluteFolder.resolve("response_headers");
        var cachedRespContentPath = reqCacheAbsoluteFolder.resolve("response_body");
        var cachedRespHeaders = new Properties();

        if (Files.exists(reqCacheAbsoluteFolder)) {
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
                cachedHeaders.put(inMemCacheId, cachedRespHeaders);
            }

            String responseBody = IOUtils.readLines(remoteResponse.body()).stream().map(String::strip).collect(Collectors.joining(""));
            cachedResponses.put(inMemCacheId, responseBody);

            // cache the response
            try (OutputStream os = new FileOutputStream(cachedRespContentPath.toFile())) {
                IOUtils.copyLarge(new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)), os);
            }

            // send the file to the http request
            try (InputStream is = new FileInputStream(cachedRespContentPath.toFile())) {
                IOUtils.copyLarge(is, response.getOutputStream());
            }

            // store the request details
            var cachedReqHeadersPath = reqCacheAbsoluteFolder.resolve("request_headers");
            var cachedReqContentPath = reqCacheAbsoluteFolder.resolve("request_body");
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

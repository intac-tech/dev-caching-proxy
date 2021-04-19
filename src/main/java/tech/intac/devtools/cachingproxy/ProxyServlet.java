package tech.intac.devtools.cachingproxy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;

import static java.net.http.HttpRequest.BodyPublishers;
import static java.net.http.HttpRequest.newBuilder;

public class ProxyServlet extends HttpServlet {

    public static final Map<String, byte[]> cachedContent = new HashMap<>();
    public static final Map<String, Properties> cachedHeaders = new HashMap<>();

    @SuppressWarnings("Convert2MethodRef")
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
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

        var cachedRespHeadersPath = reqCacheAbsoluteFolder.resolve("response_headers");
        var cachedRespContentPath = reqCacheAbsoluteFolder.resolve("response_body");
        var cachedRespHeaders = new Properties();

        if (cachedHeaders.containsKey(cachedRespHeadersPath.toString())) {
            cachedRespHeaders = cachedHeaders.get(cachedRespHeadersPath.toString());
            cachedRespHeaders.forEach((key, value) -> response.setHeader(key.toString(), value + ""));
        }

        if (cachedContent.containsKey(cachedRespContentPath.toString())) {
            byte[] bytes = cachedContent.get(cachedRespContentPath.toString());
            response.setHeader("Content-Length", String.valueOf(bytes.length));
            IOUtils.copy(new ByteArrayInputStream(bytes), response.getOutputStream());
            return;
        }

        try {
            var remoteResponse = sendRemoteRequest(config, request, reqBody);
            var remoteHeaders = remoteResponse.headers().map()
                    .entrySet()
                    .stream()
                    .collect(Collectors.toMap(entry -> entry.getKey(), entry -> entry.getValue().isEmpty() ? "" : entry.getValue().get(0)));

            cachedRespHeaders.putAll(remoteHeaders);

            cachedHeaders.put(cachedRespHeadersPath.toString(), cachedRespHeaders);

            // cache the response in memory
            var respBody = String.join("\n", IOUtils.readLines(new InputStreamReader(remoteResponse.body())));
            var respBodyInBytes = respBody.getBytes(StandardCharsets.UTF_8);

            cachedContent.put(cachedRespContentPath.toString(), respBodyInBytes);

            response.setContentLength(respBodyInBytes.length);
            IOUtils.copyLarge(new ByteArrayInputStream(respBodyInBytes), response.getOutputStream());

        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private HttpResponse<InputStream> sendRemoteRequest(Config config, HttpServletRequest request, String reqBody) throws Exception {
        var client = HttpClient.newBuilder().build();
        var remoteUri = new URI(config.getBaseUrl() + request.getRequestURI() +
                (request.getQueryString() != null ? "?" + request.getQueryString() : ""));

        var reqbldr = newBuilder()
                .uri(remoteUri);

        switch (request.getMethod().toLowerCase()) {
            case "get":
                reqbldr.GET();
                break;
            case "post":
                reqbldr.POST(BodyPublishers.ofString(reqBody));
                break;
            case "head":
                reqbldr.method("HEAD", BodyPublishers.noBody());
                break;
            default:
                // no-op
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

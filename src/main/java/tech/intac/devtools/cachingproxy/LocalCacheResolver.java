package tech.intac.devtools.cachingproxy;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.security.Credential;

public class LocalCacheResolver {

    public static Path resolve(URL url) {
        var parts = new ArrayList<String>();
        parts.add(url.getProtocol());
        parts.add(url.getHost());
        parts.add(url.getPort() + "");
        parts.addAll(Arrays.asList(url.getPath().split("/")));

        return Paths.get(parts.remove(0), parts.toArray(String[]::new));
    }

    static String generateCacheFolderName(HttpServletRequest request, String reqBody) {
        var requestId = String.join("_", request.getQueryString(), reqBody);
        var checksum = Credential.MD5.digest(requestId).replace(":", "_");
        return String.join("_", request.getMethod().toLowerCase(), checksum);
    }
}

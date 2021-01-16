package tech.intac.devtools.cachingproxy;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

public class LocalCacheResolver {

    public static Path resolve(URL url) {
        var parts = new ArrayList<String>();
        parts.add(url.getProtocol());
        parts.add(url.getHost());
        parts.add(url.getPort() + "");
        parts.add(url.getPath().replace("/", "_"));

        if (parts.size() == 1) {
            return Paths.get(parts.get(0));
        }

        return Paths.get(parts.remove(0), parts.toArray(String[]::new));
    }
}

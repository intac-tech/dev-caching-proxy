package tech.intac.devtools.cachingproxy;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Config implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final String configFileName = "app.config";

    private static final Path configPath;

    private static final Config instance;

    static {
        Path resolvedConfigPath = null;
        Config config = null;

        loadConfig:
        try {
            URI jarUri = Config.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            resolvedConfigPath = Paths.get(jarUri).resolveSibling(configFileName);

            if (!Files.exists(resolvedConfigPath)) {
                break loadConfig;
            }

            try (InputStream is = new FileInputStream(resolvedConfigPath.toFile())) {
                ObjectInputStream ois = new ObjectInputStream(is);
                config = (Config) ois.readObject();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        configPath = resolvedConfigPath;

        if (config == null) {
            config = new Config();
        }

        instance = config;
    }

    public static final Config getInstance() {
        return instance;
    }

    private String baseUrl = "http://host:port";

    private String _localOverridesPath;
    transient private Path localOverridesPath;

    private boolean cacheGetRequests = true;
    private boolean cachePostRequests = false;

    private Config() {
        _localOverridesPath = configPath.resolveSibling("cache").toString();
    }

    public void save() {
        if (configPath == null) {
            return;
        }

        try (OutputStream os = new FileOutputStream(configPath.toFile())) {
            ObjectOutputStream oos = new ObjectOutputStream(os);
            oos.writeObject(this);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Path getLocalOverridesPath() {
        if (localOverridesPath == null) {
            localOverridesPath = Paths.get(_localOverridesPath);
        }
        return localOverridesPath;
    }

    public void setLocalOverridesPath(Path localOverridesPath) {
        this.localOverridesPath = localOverridesPath;
        this._localOverridesPath = localOverridesPath.toString();
    }

    public boolean isCacheGetRequests() {
        return cacheGetRequests;
    }

    public void setCacheGetRequests(boolean cacheGetRequests) {
        this.cacheGetRequests = cacheGetRequests;
    }

    public boolean isCachePostRequests() {
        return cachePostRequests;
    }

    public void setCachePostRequests(boolean cachePostRequests) {
        this.cachePostRequests = cachePostRequests;
    }
}

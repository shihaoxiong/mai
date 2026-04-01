import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class MavenWrapperDownloader {

    private static final String DEFAULT_WRAPPER_URL =
            "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar";

    public static void main(String[] args) throws Exception {
        Path wrapperDirectory = args.length > 0
                ? Paths.get(args[0]).toAbsolutePath().normalize()
                : Paths.get(".mvn", "wrapper").toAbsolutePath().normalize();
        Files.createDirectories(wrapperDirectory);

        Path propertiesPath = wrapperDirectory.resolve("maven-wrapper.properties");
        String wrapperUrl = loadWrapperUrl(propertiesPath);
        Path jarPath = wrapperDirectory.resolve("maven-wrapper.jar");

        download(wrapperUrl, jarPath);
        System.out.println("Downloaded Maven wrapper jar to " + jarPath);
    }

    private static String loadWrapperUrl(Path propertiesPath) throws IOException {
        if (!Files.exists(propertiesPath)) {
            return DEFAULT_WRAPPER_URL;
        }

        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(propertiesPath)) {
            properties.load(inputStream);
        }

        return properties.getProperty("wrapperUrl", DEFAULT_WRAPPER_URL);
    }

    private static void download(String wrapperUrl, Path jarPath) throws IOException {
        URL url = new URL(wrapperUrl);
        try (InputStream inputStream = url.openStream()) {
            Files.deleteIfExists(jarPath);
            Files.copy(inputStream, jarPath);
        }
    }
}

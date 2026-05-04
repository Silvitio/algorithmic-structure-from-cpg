package app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class RuntimeSettings {
    public static final String NEO4J_URI = readSetting("neo4j.uri", "NEO4J_URI", "bolt://localhost:17687");
    public static final String NEO4J_USER = readSetting("neo4j.user", "NEO4J_USER", "neo4j");
    public static final String NEO4J_PASSWORD = readSetting("neo4j.password", "NEO4J_PASSWORD", "strongPasswordHere");
    public static final String NEO4J_DATABASE = readSetting("neo4j.database", "NEO4J_DATABASE", "neo4j");
    public static final boolean CPG_LOAD_INCLUDES =
            Boolean.parseBoolean(readSetting("cpg.loadIncludes", "CPG_LOAD_INCLUDES", "false"));
    public static final boolean CPG_PURGE_DATABASE =
            !Boolean.parseBoolean(readSetting("cpg.noPurgeDb", "CPG_NO_PURGE_DB", "false"));

    private RuntimeSettings() {
    }

    public static Path resolveTopLevel(Path sourceFile) {
        String configuredTopLevel = readOptionalSetting("cpg.topLevel", "CPG_TOP_LEVEL");
        if (configuredTopLevel != null) {
            return Path.of(configuredTopLevel).toAbsolutePath().normalize();
        }

        Path normalizedSource = sourceFile.toAbsolutePath().normalize();
        Path parent = normalizedSource.getParent();
        return parent == null ? normalizedSource : parent;
    }

    public static List<Path> readIncludePaths() throws IOException {
        String includesFile = readOptionalSetting("cpg.includesFile", "CPG_INCLUDES_FILE");
        if (includesFile == null) {
            return List.of();
        }

        return Files.readAllLines(Path.of(includesFile)).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("#"))
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toList());
    }

    private static String readSetting(String propertyName, String envName, String defaultValue) {
        String value = readOptionalSetting(propertyName, envName);
        return value == null ? defaultValue : value;
    }

    private static String readOptionalSetting(String propertyName, String envName) {
        String propertyValue = System.getProperty(propertyName);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }

        String envValue = System.getenv(envName);
        if (envValue != null && !envValue.isBlank()) {
            return envValue;
        }

        return null;
    }
}

package app;

import de.fraunhofer.aisec.cpg.ConfigurationException;
import de.fraunhofer.aisec.cpg.TranslationConfiguration;
import de.fraunhofer.aisec.cpg.TranslationManager;
import de.fraunhofer.aisec.cpg.TranslationResult;
import de.fraunhofer.aisec.cpg.persistence.Neo4JKt;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

public final class CpgLoaderService {
    public void load(Path sourcePath) throws Exception {
        Path normalizedSourcePath = sourcePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedSourcePath)) {
            throw new IllegalArgumentException("Source file was not found: " + normalizedSourcePath);
        }

        TranslationResult translationResult = analyze(normalizedSourcePath);

        try (Driver driver = GraphDatabase.driver(
                RuntimeSettings.NEO4J_URI,
                AuthTokens.basic(RuntimeSettings.NEO4J_USER, RuntimeSettings.NEO4J_PASSWORD));
             Session session = driver.session(SessionConfig.forDatabase(RuntimeSettings.NEO4J_DATABASE))) {
            if (RuntimeSettings.CPG_PURGE_DATABASE) {
                session.run("MATCH (n) DETACH DELETE n").consume();
            }

            Neo4JKt.persist(session, translationResult);
        }
    }

    private TranslationResult analyze(Path sourcePath)
            throws ExecutionException, InterruptedException, ConfigurationException, java.io.IOException {
        TranslationConfiguration.Builder configurationBuilder = new TranslationConfiguration.Builder()
                .defaultPasses()
                .registerLanguage("de.fraunhofer.aisec.cpg.frontends.cxx.CLanguage")
                .sourceLocations(sourcePath.toFile())
                .topLevel(RuntimeSettings.resolveTopLevel(sourcePath).toFile())
                .loadIncludes(RuntimeSettings.CPG_LOAD_INCLUDES)
                .failOnError(false);

        for (Path includePath : RuntimeSettings.readIncludePaths()) {
            configurationBuilder.includePath(includePath);
        }

        return TranslationManager.builder()
                .config(configurationBuilder.build())
                .build()
                .analyze()
                .get();
    }
}

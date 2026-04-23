package app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class CpgLoaderService {
    private static final Path CPG_NEO4J_BAT = Path.of(
            "..", "..", "cpg-10.8.2", "cpg-neo4j", "build", "install", "cpg-neo4j", "bin", "cpg-neo4j.bat"
    );

    public void load(Path sourcePath) throws Exception {
        Path normalizedSourcePath = sourcePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedSourcePath)) {
            throw new IllegalArgumentException("Source file was not found: " + normalizedSourcePath);
        }

        Path loaderPath = CPG_NEO4J_BAT.toAbsolutePath().normalize();
        if (!Files.isRegularFile(loaderPath)) {
            throw new IllegalStateException("cpg-neo4j.bat was not found: " + loaderPath);
        }

        List<String> command = List.of(
                "cmd.exe",
                "/c",
                loaderPath.toString(),
                "--host=localhost",
                "--port=17687",
                "--user=" + AnalysisService.USER,
                "--password=" + AnalysisService.PASSWORD,
                normalizedSourcePath.toString()
        );

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(Path.of("").toAbsolutePath().toFile());
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))
        ) {
            output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "Loading code into Neo4j failed with exit code "
                            + exitCode + System.lineSeparator() + output
            );
        }
    }
}

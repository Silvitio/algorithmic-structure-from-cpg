package app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ModelAnalysisReportRunner {
    private static final Path TEST_CASES_ROOT = Path.of("test_cases");
    private static final Path REPORT_PATH = Path.of("docs", "model_analysis_full_report.txt");
    private static final String SOURCE_FILE_NAME = "source.c";

    public static void main(String[] args) throws Exception {
        new ModelAnalysisReportRunner().runAll();
    }

    private void runAll() throws Exception {
        List<TestCase> testCases = loadTestCases();
        if (testCases.isEmpty()) {
            System.out.println("Test cases were not found in " + TEST_CASES_ROOT.toAbsolutePath());
            return;
        }

        List<TestReport> reports = new ArrayList<>();
        for (TestCase testCase : testCases) {
            reports.add(runTestCase(testCase));
        }

        writeReport(reports);
        System.out.println("Processed tests: " + reports.size());
        System.out.println("Report: " + REPORT_PATH.toAbsolutePath());
    }

    private List<TestCase> loadTestCases() throws IOException {
        if (!Files.isDirectory(TEST_CASES_ROOT)) {
            return List.of();
        }

        try (var paths = Files.list(TEST_CASES_ROOT)) {
            return paths
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .map(path -> new TestCase(path.getFileName().toString(), path.resolve(SOURCE_FILE_NAME)))
                    .filter(testCase -> Files.isRegularFile(testCase.sourcePath()))
                    .toList();
        }
    }

    private TestReport runTestCase(TestCase testCase) throws Exception {
        CpgLoaderService cpgLoaderService = new CpgLoaderService();
        cpgLoaderService.load(testCase.sourcePath());

        ModelAnalysisDebugService debugService = new ModelAnalysisDebugService();
        List<String> lines = debugService.collectDebugLines();
        return new TestReport(testCase.name(), lines);
    }

    private void writeReport(List<TestReport> reports) throws IOException {
        Files.createDirectories(REPORT_PATH.getParent());

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(REPORT_PATH))) {
            for (TestReport report : reports) {
                writer.println("===== " + report.name() + " =====");
                for (String line : report.lines()) {
                    writer.println(line);
                }
                writer.println();
            }
        }
    }

    private record TestCase(String name, Path sourcePath) {
    }

    private record TestReport(String name, List<String> lines) {
    }
}

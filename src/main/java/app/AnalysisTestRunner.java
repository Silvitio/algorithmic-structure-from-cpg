package app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;

public final class AnalysisTestRunner {
    private static final Path TEST_CASES_ROOT = Path.of("test_cases");
    private static final Path REPORT_PATH = Path.of("tmp_docs", "analysis_test_runner_report.txt");
    private static final String SOURCE_FILE_NAME = "source.c";
    private static final String EXPECTED_FILE_NAME = "expected.txt";

    public static void main(String[] args) throws Exception {
        new AnalysisTestRunner().runInteractive();
    }

    private void runInteractive() throws Exception {
        List<TestCase> allCases = loadTestCases();
        if (allCases.isEmpty()) {
            System.out.println("Test cases were not found in " + TEST_CASES_ROOT.toAbsolutePath());
            return;
        }

        printTestList(allCases);
        List<TestCase> selectedCases = promptSelection(allCases);
        if (selectedCases.isEmpty()) {
            System.out.println("No tests were selected.");
            return;
        }

        List<TestResult> results = new ArrayList<>();
        for (TestCase testCase : selectedCases) {
            results.add(runTestCase(testCase));
        }

        writeReport(results);
        printSummary(results);
    }

    private List<TestCase> loadTestCases() throws IOException {
        if (!Files.isDirectory(TEST_CASES_ROOT)) {
            return List.of();
        }

        try (var paths = Files.list(TEST_CASES_ROOT)) {
            List<Path> directories = paths
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .toList();

            List<TestCase> testCases = new ArrayList<>();
            int number = 1;
            for (Path directory : directories) {
                Path sourcePath = directory.resolve(SOURCE_FILE_NAME);
                Path expectedPath = directory.resolve(EXPECTED_FILE_NAME);
                if (!Files.isRegularFile(sourcePath) || !Files.isRegularFile(expectedPath)) {
                    continue;
                }

                testCases.add(new TestCase(
                        number++,
                        directory.getFileName().toString(),
                        sourcePath,
                        expectedPath
                ));
            }

            return testCases;
        }
    }

    private void printTestList(List<TestCase> testCases) {
        for (TestCase testCase : testCases) {
            System.out.println(testCase.number() + " - " + testCase.name());
        }
    }

    private List<TestCase> promptSelection(List<TestCase> allCases) {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println();
            System.out.println("Enter test numbers. Empty input runs all tests.");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                return allCases;
            }

            String[] tokens = input.split("\\s+");
            Set<Integer> selectedNumbers = new LinkedHashSet<>();
            boolean valid = true;
            for (String token : tokens) {
                try {
                    int number = Integer.parseInt(token);
                    if (number < 1 || number > allCases.size()) {
                        System.out.println("Invalid test number: " + token);
                        valid = false;
                        break;
                    }
                    selectedNumbers.add(number);
                } catch (NumberFormatException exception) {
                    System.out.println("Invalid input: " + token);
                    valid = false;
                    break;
                }
            }

            if (!valid) {
                continue;
            }

            List<TestCase> selectedCases = new ArrayList<>();
            for (Integer number : selectedNumbers) {
                selectedCases.add(allCases.get(number - 1));
            }
            return selectedCases;
        }
    }

    private TestResult runTestCase(TestCase testCase) throws Exception {
        List<String> expectedLines = Files.readAllLines(testCase.expectedPath());
        String sourceCode = Files.readString(testCase.sourcePath());

        CpgLoaderService cpgLoaderService = new CpgLoaderService();
        cpgLoaderService.load(testCase.sourcePath());

        AnalysisService analysisService = new AnalysisService();
        List<String> actualLines = analysisService.collectMarkedCodes();

        LinkedHashSet<String> normalizedExpected = normalizeToSet(expectedLines);
        LinkedHashSet<String> normalizedActual = normalizeToSet(actualLines);

        LinkedHashSet<String> missing = new LinkedHashSet<>(normalizedExpected);
        missing.removeAll(normalizedActual);

        LinkedHashSet<String> unexpected = new LinkedHashSet<>(normalizedActual);
        unexpected.removeAll(normalizedExpected);

        boolean passed = missing.isEmpty() && unexpected.isEmpty();

        return new TestResult(
                testCase.number(),
                testCase.name(),
                sourceCode,
                expectedLines,
                actualLines,
                missing,
                unexpected,
                passed
        );
    }

    private LinkedHashSet<String> normalizeToSet(Collection<String> lines) {
        return lines.stream()
                .map(this::normalizeLine)
                .filter(line -> !line.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String normalizeLine(String line) {
        String normalized = line == null ? "" : line.trim();
        normalized = normalized.replaceAll("\\s+", " ");
        normalized = normalized.replaceAll(";+$", "");
        return normalized;
    }

    private void writeReport(List<TestResult> results) throws IOException {
        Files.createDirectories(REPORT_PATH.getParent());

        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(REPORT_PATH))) {
            for (TestResult result : results) {
                writer.println("Test #" + result.number() + ": " + result.name());
                writer.println("Match: " + (result.passed() ? "PASS" : "FAIL"));
                writer.println();

                writer.println("Code:");
                writer.println(result.sourceCode().stripTrailing());
                writer.println();

                writer.println("Expected:");
                writeLines(writer, result.expectedLines());
                writer.println();

                writer.println("Actual:");
                writeLines(writer, result.actualLines());
                writer.println();

                if (!result.missing().isEmpty()) {
                    writer.println("Missing:");
                    writeLines(writer, result.missing());
                    writer.println();
                }

                if (!result.unexpected().isEmpty()) {
                    writer.println("Unexpected:");
                    writeLines(writer, result.unexpected());
                    writer.println();
                }
            }
        }
    }

    private void writeLines(PrintWriter writer, Collection<String> lines) {
        if (lines.isEmpty()) {
            writer.println("(empty)");
            return;
        }

        for (String line : lines) {
            writer.println(line);
        }
    }

    private void printSummary(List<TestResult> results) {
        long passed = results.stream().filter(TestResult::passed).count();
        System.out.println();
        System.out.println("Passed tests: " + passed + " / " + results.size());

        List<TestResult> failed = results.stream()
                .filter(result -> !result.passed())
                .toList();
        if (!failed.isEmpty()) {
            System.out.println("Failed tests:");
            for (TestResult result : failed) {
                System.out.println("- " + result.name());
            }
        }

        System.out.println("Report: " + REPORT_PATH.toAbsolutePath());
    }

    private record TestCase(
            int number,
            String name,
            Path sourcePath,
            Path expectedPath
    ) {
    }

    private record TestResult(
            int number,
            String name,
            String sourceCode,
            List<String> expectedLines,
            List<String> actualLines,
            Set<String> missing,
            Set<String> unexpected,
            boolean passed
    ) {
    }
}

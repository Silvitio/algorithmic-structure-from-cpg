package app;

import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Path sourcePath = resolveSourcePath(args);

        CpgLoaderService cpgLoaderService = new CpgLoaderService();
        cpgLoaderService.load(sourcePath);

        AnalysisService analysisService = new AnalysisService();
        List<String> markedCodes = analysisService.collectMarkedCodes();
        for (String code : markedCodes) {
            System.out.println(code);
        }
    }

    private static Path resolveSourcePath(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return Path.of(args[0].trim());
        }

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("Enter path to C source file:");
            String input = scanner.nextLine().trim();
            if (!input.isBlank()) {
                return Path.of(input);
            }
        }
    }
}

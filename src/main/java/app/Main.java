package app;

import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

public class Main {
    private static final String DEFS_USES_DEBUG_FLAG = "--defs-uses-debug";
    private static final String FUNCTION_MODEL_DEBUG_FLAG = "--function-model-debug";

    public static void main(String[] args) throws Exception {
        if (isDefsUsesDebugMode(args)) {
            Path sourcePath = resolveDebugSourcePath(args);
            CpgLoaderService cpgLoaderService = new CpgLoaderService();
            cpgLoaderService.load(sourcePath);

            DefsUsesDebugService defsUsesDebugService = new DefsUsesDebugService();
            for (String line : defsUsesDebugService.collectDebugLines()) {
                System.out.println(line);
            }
            return;
        }
        if (isFunctionModelDebugMode(args)) {
            Path sourcePath = resolveDebugSourcePath(args);
            CpgLoaderService cpgLoaderService = new CpgLoaderService();
            cpgLoaderService.load(sourcePath);

            FunctionModelDebugService functionModelDebugService = new FunctionModelDebugService();
            for (String line : functionModelDebugService.collectDebugLines()) {
                System.out.println(line);
            }
            return;
        }

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

    private static boolean isDefsUsesDebugMode(String[] args) {
        return args.length > 0 && DEFS_USES_DEBUG_FLAG.equals(args[0]);
    }

    private static boolean isFunctionModelDebugMode(String[] args) {
        return args.length > 0 && FUNCTION_MODEL_DEBUG_FLAG.equals(args[0]);
    }

    private static Path resolveDebugSourcePath(String[] args) {
        if (args.length > 1 && !args[1].isBlank()) {
            return Path.of(args[1].trim());
        }
        return resolveSourcePath(new String[0]);
    }
}

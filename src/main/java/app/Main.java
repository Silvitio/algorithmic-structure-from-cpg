package app;

import java.nio.file.Path;
import java.util.Scanner;

public class Main {
    private static final String DEFS_USES_DEBUG_FLAG = "--defs-uses-debug";
    private static final String FUNCTION_MODEL_DEBUG_FLAG = "--function-model-debug";
    private static final String ALGO_TREE_DEBUG_FLAG = "--algo-tree-debug";

    public static void main(String[] args) throws Exception {
        LoggingConfigurator.configure();

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
        if (isAlgoTreeDebugMode(args)) {
            Path sourcePath = resolveDebugSourcePath(args);
            CpgLoaderService cpgLoaderService = new CpgLoaderService();
            cpgLoaderService.load(sourcePath);

            AnalysisService analysisService = new AnalysisService();
            analysisService.collectMarkedCodes();

            AlgoTreeDebugService algoTreeDebugService = new AlgoTreeDebugService();
            for (String line : algoTreeDebugService.collectDebugLines()) {
                System.out.println(line);
            }
            return;
        }

        Path sourcePath = resolveSourcePath(args);

        CpgLoaderService cpgLoaderService = new CpgLoaderService();
        cpgLoaderService.load(sourcePath);

        AnalysisService analysisService = new AnalysisService();
        analysisService.collectMarkedCodes();

        AlgoTreeDebugService algoTreeDebugService = new AlgoTreeDebugService();
        for (String line : algoTreeDebugService.collectDebugLines()) {
            System.out.println(line);
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

    private static boolean isAlgoTreeDebugMode(String[] args) {
        return args.length > 0 && ALGO_TREE_DEBUG_FLAG.equals(args[0]);
    }

    private static Path resolveDebugSourcePath(String[] args) {
        if (args.length > 1 && !args[1].isBlank()) {
            return Path.of(args[1].trim());
        }
        return resolveSourcePath(new String[0]);
    }
}

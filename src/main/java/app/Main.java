package app;

public class Main {
    public static void main(String[] args) {
        AnalysisService analysisService = new AnalysisService();
        for (String code : analysisService.collectMarkedCodes()) {
            System.out.println(code);
        }
    }
}

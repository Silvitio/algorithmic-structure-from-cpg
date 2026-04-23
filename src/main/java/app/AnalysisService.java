package app;

import analysis.ReturnInfluenceAnalyzer;
import analysis.ReturnInfluenceAnalyzer.AnalysisSummary;
import analysis.ReturnInfluenceAnalyzer.FunctionInfluence;
import cpg.AlgoGraphBuilder;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

import java.util.ArrayList;
import java.util.List;

public final class AnalysisService {
    public static final String URI = "bolt://localhost:17687";
    public static final String USER = "neo4j";
    public static final String PASSWORD = "strongPasswordHere";
    public static final String DATABASE = "neo4j";

    public List<String> collectMarkedCodes() {
        try (Driver driver = GraphDatabase.driver(URI, AuthTokens.basic(USER, PASSWORD));
             Session session = driver.session(SessionConfig.forDatabase(DATABASE))) {
            return collectMarkedCodes(session);
        }
    }

    public List<String> collectMarkedCodes(Session session) {
        ReturnInfluenceAnalyzer returnInfluenceAnalyzer = new ReturnInfluenceAnalyzer();
        AnalysisSummary analysisSummary = returnInfluenceAnalyzer.analyze(session);

        AlgoGraphBuilder algoGraphBuilder = new AlgoGraphBuilder();
        algoGraphBuilder.rebuild(session);

        List<String> markedCodes = new ArrayList<>();
        for (FunctionInfluence functionInfluence : analysisSummary.functions()) {
            if (!functionInfluence.analyzed()) {
                continue;
            }
            markedCodes.addAll(functionInfluence.markedCodes());
        }

        return markedCodes;
    }
}

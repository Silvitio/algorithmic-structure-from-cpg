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

public class Main {
    public static void main(String[] args) {
        String uri = "bolt://localhost:17687";
        String user = "neo4j";
        String password = "strongPasswordHere";

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
             Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            ReturnInfluenceAnalyzer returnInfluenceAnalyzer = new ReturnInfluenceAnalyzer();
            AnalysisSummary analysisSummary = returnInfluenceAnalyzer.analyze(session);

            AlgoGraphBuilder algoGraphBuilder = new AlgoGraphBuilder();
            algoGraphBuilder.rebuild(session);

            for (FunctionInfluence functionInfluence : analysisSummary.functions()) {
                if (!functionInfluence.analyzed()) {
                    continue;
                }
                for (String code : functionInfluence.markedCodes()) {
                    System.out.println(code);
                }
            }
        }
    }
}

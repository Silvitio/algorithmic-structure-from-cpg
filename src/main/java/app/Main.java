package app;

import analysis.SelfAssignmentAnalyzer;
import cpg.AlgoGraphBuilder;
import cpg.AlgoGraphBuilder.BuildSummary;
import cpg.AlgoGraphBuilder.FunctionProjection;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;

import java.util.List;

public class Main {
    private static final String ALGO_NODE_QUERY = """
            MATCH (algo)
            WHERE elementId(algo) = $algoNodeId
            OPTIONAL MATCH (algo)-[:ALGO_REFERENCE]->(source)
            RETURN
              labels(algo) AS algoLabels,
              labels(source) AS sourceLabels,
              source.name AS sourceName,
              source.code AS sourceCode
            """;
    private static final String ALGO_SEQUENCE_CHILDREN_QUERY = """
            MATCH (algo)-[r:ALGO_ACTIONS]->(child)
            WHERE elementId(algo) = $algoNodeId
            RETURN
              r.action_index AS actionIndex,
              elementId(child) AS childNodeId
            ORDER BY actionIndex, childNodeId
            """;
    private static final String ALGO_BODY_QUERY = """
            MATCH (algo)-[:ALGO_BODY]->(child)
            WHERE elementId(algo) = $algoNodeId
            RETURN elementId(child) AS childNodeId
            LIMIT 1
            """;
    private static final String ALGO_THEN_QUERY = """
            MATCH (algo)-[:ALGO_THEN]->(child)
            WHERE elementId(algo) = $algoNodeId
            RETURN elementId(child) AS childNodeId
            LIMIT 1
            """;
    private static final String ALGO_ELSE_QUERY = """
            MATCH (algo)-[:ALGO_ELSE]->(child)
            WHERE elementId(algo) = $algoNodeId
            RETURN elementId(child) AS childNodeId
            LIMIT 1
            """;

    public static void main(String[] args) {
        String uri = "bolt://localhost:17687";
        String user = "neo4j";
        String password = "strongPasswordHere";

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
             Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            SelfAssignmentAnalyzer selfAssignmentAnalyzer = new SelfAssignmentAnalyzer();
            int deadCodeMarked = selfAssignmentAnalyzer.markDeadCode(session);

            AlgoGraphBuilder algoGraphBuilder = new AlgoGraphBuilder();
            BuildSummary summary = algoGraphBuilder.rebuild(session);

            System.out.println("Algo graph rebuilt");
            System.out.printf(
                    """
                    deadCodeMarked=%d
                    functions=%d
                    algoNodes=%d
                    codeActions=%d
                    sequences=%d
                    loops=%d
                    branches=%d

                    """,
                    deadCodeMarked,
                    summary.functions().size(),
                    summary.algoNodes(),
                    summary.codeActions(),
                    summary.sequences(),
                    summary.loops(),
                    summary.branches()
            );

            for (FunctionProjection functionProjection : summary.functions()) {
                System.out.printf(
                        "function=%s sourceNodeId=%s rootAlgoNodeId=%s%n",
                        functionProjection.functionName(),
                        functionProjection.functionNodeId(),
                        functionProjection.rootAlgoNodeId()
                );
                printAlgoTree(session, functionProjection);
                System.out.println();
            }
        }
    }

    private static void printAlgoTree(Session session, FunctionProjection functionProjection) {
        System.out.printf("AST for function %s%n", functionProjection.functionName());
        if (functionProjection.rootAlgoNodeId() == null) {
            System.out.println("  <empty>");
            return;
        }

        printAlgoNode(session, functionProjection.rootAlgoNodeId(), "  ");
    }

    private static void printAlgoNode(Session session, String algoNodeId, String indent) {
        Record nodeRecord = session.executeRead(tx ->
                tx.run(ALGO_NODE_QUERY, Values.parameters("algoNodeId", algoNodeId)).single()
        );

        List<String> labels = nodeRecord.get("algoLabels").asList(org.neo4j.driver.Value::asString);

        if (labels.contains("AlgoSequence")) {
            System.out.printf("%sSequence%n", indent);
            List<Record> children = session.executeRead(tx ->
                    tx.run(ALGO_SEQUENCE_CHILDREN_QUERY, Values.parameters("algoNodeId", algoNodeId)).list()
            );
            for (Record childRecord : children) {
                printAlgoNode(session, childRecord.get("childNodeId").asString(), indent + "  ");
            }
            return;
        }

        if (labels.contains("AlgoLoop")) {
            System.out.printf("%sLoop%n", indent);
            String bodyNodeId = loadSingleChildId(session, ALGO_BODY_QUERY, algoNodeId);
            if (bodyNodeId != null) {
                System.out.printf("%s  Body%n", indent);
                printAlgoNode(session, bodyNodeId, indent + "    ");
            }
            return;
        }

        if (labels.contains("AlgoBranch")) {
            System.out.printf("%sBranch%n", indent);

            String thenNodeId = loadSingleChildId(session, ALGO_THEN_QUERY, algoNodeId);
            if (thenNodeId != null) {
                System.out.printf("%s  Then%n", indent);
                printAlgoNode(session, thenNodeId, indent + "    ");
            }

            String elseNodeId = loadSingleChildId(session, ALGO_ELSE_QUERY, algoNodeId);
            if (elseNodeId != null) {
                System.out.printf("%s  Else%n", indent);
                printAlgoNode(session, elseNodeId, indent + "    ");
            }
            return;
        }

        if (labels.contains("AlgoCodeAction")) {
            System.out.printf("%sAction%n", indent);
            return;
        }

        System.out.printf("%sUnknownAlgoNode labels=%s%n", indent, labels);
    }

    private static String loadSingleChildId(Session session, String query, String algoNodeId) {
        List<Record> records = session.executeRead(tx ->
                tx.run(query, Values.parameters("algoNodeId", algoNodeId)).list()
        );

        if (records.isEmpty()) {
            return null;
        }

        return records.get(0).get("childNodeId").asString();
    }
}

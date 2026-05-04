package app;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AlgoTreeDebugService {
    private static final String FUNCTION_ROOTS_QUERY = """
            MATCH (f:FunctionDeclaration)-[:FUNC_REFERENCE]->(root:AlgoNode)
            RETURN
              elementId(f) AS functionNodeId,
              coalesce(f.name, f.code, "<unnamed-function>") AS functionName,
              elementId(root) AS rootAlgoNodeId
            ORDER BY functionName, functionNodeId
            """;
    private static final String ALGO_NODE_QUERY = """
            MATCH (algo:AlgoNode)
            WHERE elementId(algo) = $algoNodeId
            OPTIONAL MATCH (algo)-[:ALGO_REFERENCE]->(source)
            RETURN
              labels(algo) AS algoLabels,
              source.code AS sourceCode,
              source.name AS sourceName
            """;
    private static final String ALGO_CHILDREN_QUERY = """
            MATCH (algo:AlgoNode)-[rel]->(child:AlgoNode)
            WHERE elementId(algo) = $algoNodeId
              AND type(rel) STARTS WITH 'ALGO_'
            RETURN
              type(rel) AS relType,
              rel.action_index AS actionIndex,
              elementId(child) AS childAlgoNodeId
            """;

    public List<String> collectDebugLines() {
        try (Driver driver = GraphDatabase.driver(
                AnalysisService.URI,
                AuthTokens.basic(AnalysisService.USER, AnalysisService.PASSWORD));
             Session session = driver.session(SessionConfig.forDatabase(AnalysisService.DATABASE))) {
            return collectDebugLines(session);
        }
    }

    public List<String> collectDebugLines(Session session) {
        List<String> lines = new ArrayList<>();
        List<Record> functionRoots = session.executeRead(tx -> tx.run(FUNCTION_ROOTS_QUERY).list());

        for (Record functionRoot : functionRoots) {
            String functionName = functionRoot.get("functionName").asString();
            String rootAlgoNodeId = functionRoot.get("rootAlgoNodeId").asString();

            lines.add("Function: " + functionName);
            lines.addAll(renderNodeLines(session, rootAlgoNodeId, new ArrayList<>(), ""));
            lines.add("");
        }

        return lines;
    }

    private List<String> renderNodeLines(
            Session session,
            String algoNodeId,
            List<String> visitedNodeIds,
            String indent
    ) {
        if (visitedNodeIds.contains(algoNodeId)) {
            return List.of(indent + "<cycle:" + algoNodeId + ">");
        }

        Record nodeRecord = session.executeRead(tx ->
                tx.run(ALGO_NODE_QUERY, Values.parameters("algoNodeId", algoNodeId)).single()
        );

        List<String> labels = nodeRecord.get("algoLabels").asList(Value::asString);
        String sourceCode = nullableString(nodeRecord, "sourceCode");
        String sourceName = nullableString(nodeRecord, "sourceName");

        List<String> lines = new ArrayList<>();
        lines.add(indent + formatAlgoNode(labels, sourceCode, sourceName));

        List<AlgoChild> children = loadChildren(session, algoNodeId);
        if (children.isEmpty()) {
            return lines;
        }

        List<String> nextVisited = new ArrayList<>(visitedNodeIds);
        nextVisited.add(algoNodeId);

        for (AlgoChild child : children) {
            List<String> childLines = renderNodeLines(session, child.childAlgoNodeId(), nextVisited, indent + "    ");
            if (childLines.isEmpty()) {
                continue;
            }

            lines.add(indent + "  " + formatRelation(child) + " -> " + trimExpectedIndent(childLines.get(0), indent + "    "));
            for (int index = 1; index < childLines.size(); index++) {
                lines.add(childLines.get(index));
            }
        }

        return lines;
    }

    private List<AlgoChild> loadChildren(Session session, String algoNodeId) {
        List<AlgoChild> children = new ArrayList<>(session.executeRead(tx ->
                tx.run(ALGO_CHILDREN_QUERY, Values.parameters("algoNodeId", algoNodeId)).list(record ->
                        new AlgoChild(
                                record.get("relType").asString(),
                                record.get("actionIndex").isNull() ? null : record.get("actionIndex").asInt(),
                                record.get("childAlgoNodeId").asString()
                        )
                )
        ));

        children.sort(Comparator
                .comparingInt((AlgoChild child) -> relationOrder(child.relType()))
                .thenComparingInt(child -> child.actionIndex() == null ? Integer.MAX_VALUE : child.actionIndex())
                .thenComparing(AlgoChild::childAlgoNodeId));
        return children;
    }

    private String formatAlgoNode(List<String> labels, String sourceCode, String sourceName) {
        String payload = sanitize(firstNonBlank(sourceCode, sourceName));
        if (labels.contains("AlgoBranch")) {
            return payload == null ? "AlgoBranch" : "AlgoBranch(" + payload + ")";
        }
        if (labels.contains("AlgoLoop")) {
            return payload == null ? "AlgoLoop" : "AlgoLoop(" + payload + ")";
        }
        if (labels.contains("AlgoCodeAction")) {
            return payload == null ? "AlgoCodeAction" : "AlgoCodeAction(" + payload + ")";
        }
        if (labels.contains("AlgoSequence")) {
            return payload == null ? "AlgoSequence" : "AlgoSequence(" + payload + ")";
        }
        return payload == null ? String.join("/", labels) : String.join("/", labels) + "(" + payload + ")";
    }

    private String formatRelation(AlgoChild child) {
        if ("ALGO_ACTIONS".equals(child.relType()) && child.actionIndex() != null) {
            return "ALGO_ACTIONS[" + child.actionIndex() + "]";
        }
        return child.relType();
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String trimExpectedIndent(String value, String indent) {
        return value.startsWith(indent) ? value.substring(indent.length()) : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String nullableString(Record record, String key) {
        Value value = record.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private int relationOrder(String relType) {
        return switch (relType) {
            case "ALGO_BODY" -> 0;
            case "ALGO_THEN" -> 1;
            case "ALGO_ELSE" -> 2;
            case "ALGO_ACTIONS" -> 3;
            default -> 10;
        };
    }

    private record AlgoChild(String relType, Integer actionIndex, String childAlgoNodeId) {
    }
}

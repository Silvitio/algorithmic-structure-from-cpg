package analysis;

import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SelfAssignmentAnalyzer {
    private static final String CLEAR_MARKS_QUERY = """
            MATCH (assignment:AssignExpression:DEAD_CODE)
            REMOVE assignment:DEAD_CODE
            """;
    private static final String ASSIGNMENT_IDS_QUERY = """
            MATCH (assignment:AssignExpression)
            RETURN elementId(assignment) AS assignmentNodeId
            ORDER BY assignmentNodeId
            """;
    private static final String NODE_QUERY = """
            MATCH (node)
            WHERE elementId(node) = $nodeId
            RETURN
              elementId(node) AS nodeId,
              labels(node) AS labels,
              node.name AS name,
              node.code AS code,
              node.operatorCode AS operatorCode
            """;
    private static final String ASSIGNMENT_SIDES_QUERY = """
            MATCH (assignment)
            WHERE elementId(assignment) = $assignmentNodeId
            OPTIONAL MATCH (assignment)-[:LHS]->(lhs)
            OPTIONAL MATCH (assignment)-[:RHS]->(rhs)
            RETURN
              elementId(lhs) AS lhsNodeId,
              elementId(rhs) AS rhsNodeId
            """;
    private static final String MARK_DEAD_CODE_QUERY = """
            MATCH (assignment)
            WHERE elementId(assignment) = $assignmentNodeId
            SET assignment:DEAD_CODE
            """;

    public int markDeadCode(Session session) {
        return session.executeWrite(tx -> {
            tx.run(CLEAR_MARKS_QUERY).consume();

            List<String> assignmentNodeIds = tx.run(ASSIGNMENT_IDS_QUERY).list(record ->
                    record.get("assignmentNodeId").asString()
            );

            Map<String, NodeInfo> nodeCache = new HashMap<>();
            Map<String, AssignmentSides> sidesCache = new HashMap<>();
            int markedCount = 0;

            for (String assignmentNodeId : assignmentNodeIds) {
                if (isDeadSelfAssignment(tx, assignmentNodeId, nodeCache, sidesCache, new HashSet<>())) {
                    tx.run(MARK_DEAD_CODE_QUERY, Values.parameters("assignmentNodeId", assignmentNodeId)).consume();
                    markedCount++;
                }
            }

            return markedCount;
        });
    }

    private boolean isDeadSelfAssignment(
            TransactionContext tx,
            String assignmentNodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, AssignmentSides> sidesCache,
            Set<String> visitingAssignments
    ) {
        NodeInfo assignmentNode = loadNodeInfo(tx, assignmentNodeId, nodeCache);
        if (assignmentNode == null
                || !assignmentNode.labels().contains("AssignExpression")
                || !"=".equals(normalizedOperator(assignmentNode.operatorCode()))) {
            return false;
        }
        if (!visitingAssignments.add(assignmentNodeId)) {
            return false;
        }

        try {
            AssignmentSides sides = loadAssignmentSides(tx, assignmentNodeId, sidesCache);
            if (sides == null || sides.lhsNodeId() == null || sides.rhsNodeId() == null) {
                return false;
            }

            return expressionPreservesTarget(
                    tx,
                    sides.lhsNodeId(),
                    sides.rhsNodeId(),
                    nodeCache,
                    sidesCache,
                    visitingAssignments
            );
        } finally {
            visitingAssignments.remove(assignmentNodeId);
        }
    }

    private boolean expressionPreservesTarget(
            TransactionContext tx,
            String targetNodeId,
            String expressionNodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, AssignmentSides> sidesCache,
            Set<String> visitingAssignments
    ) {
        if (sameExpression(targetNodeId, expressionNodeId, nodeCache, tx)) {
            return true;
        }

        NodeInfo expressionNode = loadNodeInfo(tx, expressionNodeId, nodeCache);
        if (expressionNode == null
                || !expressionNode.labels().contains("AssignExpression")
                || !"=".equals(normalizedOperator(expressionNode.operatorCode()))) {
            return false;
        }
        if (!visitingAssignments.add(expressionNodeId)) {
            return false;
        }

        try {
            AssignmentSides nestedSides = loadAssignmentSides(tx, expressionNodeId, sidesCache);
            if (nestedSides == null || nestedSides.lhsNodeId() == null || nestedSides.rhsNodeId() == null) {
                return false;
            }

            return expressionPreservesTarget(
                    tx,
                    targetNodeId,
                    nestedSides.lhsNodeId(),
                    nodeCache,
                    sidesCache,
                    visitingAssignments
            ) && expressionPreservesTarget(
                    tx,
                    targetNodeId,
                    nestedSides.rhsNodeId(),
                    nodeCache,
                    sidesCache,
                    visitingAssignments
            );
        } finally {
            visitingAssignments.remove(expressionNodeId);
        }
    }

    private boolean sameExpression(
            String leftNodeId,
            String rightNodeId,
            Map<String, NodeInfo> nodeCache,
            TransactionContext tx
    ) {
        NodeInfo leftNode = loadNodeInfo(tx, leftNodeId, nodeCache);
        NodeInfo rightNode = loadNodeInfo(tx, rightNodeId, nodeCache);
        if (leftNode == null || rightNode == null) {
            return false;
        }

        String leftText = normalizedExpressionText(leftNode);
        String rightText = normalizedExpressionText(rightNode);
        return !leftText.isBlank() && leftText.equals(rightText);
    }

    private NodeInfo loadNodeInfo(
            TransactionContext tx,
            String nodeId,
            Map<String, NodeInfo> nodeCache
    ) {
        if (nodeId == null) {
            return null;
        }
        if (nodeCache.containsKey(nodeId)) {
            return nodeCache.get(nodeId);
        }

        List<Record> records = tx.run(NODE_QUERY, Values.parameters("nodeId", nodeId)).list();
        if (records.isEmpty()) {
            nodeCache.put(nodeId, null);
            return null;
        }

        Record record = records.get(0);
        NodeInfo nodeInfo = new NodeInfo(
                nodeId,
                record.get("labels").asList(Value::asString),
                getNullableString(record, "name"),
                getNullableString(record, "code"),
                getNullableString(record, "operatorCode")
        );
        nodeCache.put(nodeId, nodeInfo);
        return nodeInfo;
    }

    private AssignmentSides loadAssignmentSides(
            TransactionContext tx,
            String assignmentNodeId,
            Map<String, AssignmentSides> sidesCache
    ) {
        if (assignmentNodeId == null) {
            return null;
        }
        if (sidesCache.containsKey(assignmentNodeId)) {
            return sidesCache.get(assignmentNodeId);
        }

        List<Record> records = tx.run(
                ASSIGNMENT_SIDES_QUERY,
                Values.parameters("assignmentNodeId", assignmentNodeId)
        ).list();
        if (records.isEmpty()) {
            sidesCache.put(assignmentNodeId, null);
            return null;
        }

        Record record = records.get(0);
        AssignmentSides sides = new AssignmentSides(
                getNullableString(record, "lhsNodeId"),
                getNullableString(record, "rhsNodeId")
        );
        sidesCache.put(assignmentNodeId, sides);
        return sides;
    }

    private String normalizedExpressionText(NodeInfo nodeInfo) {
        String value = nodeInfo.code();
        if (value == null || value.isBlank()) {
            value = nodeInfo.name();
        }
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private String normalizedOperator(String operatorCode) {
        return operatorCode == null || operatorCode.isBlank() ? "=" : operatorCode;
    }

    private String getNullableString(Record record, String key) {
        Value value = record.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private record NodeInfo(
            String nodeId,
            List<String> labels,
            String name,
            String code,
            String operatorCode
    ) {
    }

    private record AssignmentSides(
            String lhsNodeId,
            String rhsNodeId
    ) {
    }
}

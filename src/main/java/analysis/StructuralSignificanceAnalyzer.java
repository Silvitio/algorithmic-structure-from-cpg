package analysis;

import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StructuralSignificanceAnalyzer {
    private static final String CLEAR_QUERY = """
            MATCH (n:STRUCTURALLY_SIGNIFICANT)
            REMOVE n:STRUCTURALLY_SIGNIFICANT
            """;
    private static final String FUNCTION_QUERY = """
            MATCH (f:FunctionDeclaration)-[:BODY]->(body:Block)
            RETURN
              elementId(f) AS functionNodeId,
              elementId(body) AS bodyNodeId
            ORDER BY functionNodeId
            """;
    private static final String BLOCK_STATEMENTS_QUERY = """
            MATCH (block)-[stmtRel:STATEMENTS]->(statement)
            WHERE elementId(block) = $blockNodeId
            RETURN
              elementId(statement) AS statementNodeId,
              labels(statement) AS labels,
              stmtRel.index AS statementIndex
            ORDER BY statementIndex, statementNodeId
            """;
    private static final String DECLARATIONS_QUERY = """
            MATCH (statement)-[declRel:DECLARATIONS]->(declaration:ValueDeclaration)
            WHERE elementId(statement) = $statementNodeId
            RETURN
              elementId(declaration) AS declarationNodeId,
              declRel.index AS declarationIndex
            ORDER BY declarationIndex, declarationNodeId
            """;
    private static final String IF_DETAILS_QUERY = """
            MATCH (statement)
            WHERE elementId(statement) = $statementNodeId
            OPTIONAL MATCH (statement)-[:THEN_STATEMENT]->(thenBranch)
            OPTIONAL MATCH (statement)-[:ELSE_STATEMENT]->(elseBranch)
            RETURN
              elementId(thenBranch) AS thenNodeId,
              labels(thenBranch) AS thenLabels,
              elementId(elseBranch) AS elseNodeId,
              labels(elseBranch) AS elseLabels
            """;
    private static final String WHILE_BODY_QUERY = """
            MATCH (statement)
            WHERE elementId(statement) = $statementNodeId
            OPTIONAL MATCH (statement)-[:CONDITION]->(condition)
            OPTIONAL MATCH (statement)-[:STATEMENT]->(body)
            RETURN
              elementId(condition) AS conditionNodeId,
              elementId(body) AS bodyNodeId,
              labels(body) AS bodyLabels
            """;
    private static final String FOR_DETAILS_QUERY = """
            MATCH (statement)
            WHERE elementId(statement) = $statementNodeId
            OPTIONAL MATCH (statement)-[:INITIALIZER_STATEMENT]->(initializer)
            OPTIONAL MATCH (statement)-[:CONDITION]->(condition)
            OPTIONAL MATCH (statement)-[:STATEMENT]->(body)
            OPTIONAL MATCH (statement)-[:ITERATION_STATEMENT]->(iteration)
            RETURN
              elementId(initializer) AS initializerNodeId,
              labels(initializer) AS initializerLabels,
              elementId(condition) AS conditionNodeId,
              elementId(body) AS bodyNodeId,
              labels(body) AS bodyLabels,
              elementId(iteration) AS iterationNodeId,
              labels(iteration) AS iterationLabels
            """;
    private static final String SWITCH_DETAILS_QUERY = """
            MATCH (statement)
            WHERE elementId(statement) = $statementNodeId
            OPTIONAL MATCH (statement)-[:STATEMENT]->(body)
            RETURN elementId(body) AS bodyNodeId
            """;
    private static final String RETURN_VALUE_QUERY = """
            MATCH (ret)
            WHERE elementId(ret) = $returnNodeId
            OPTIONAL MATCH (ret)-[:RETURN_VALUE]->(expr)
            WITH ret, expr
            OPTIONAL MATCH (ret)-[:RETURN_VALUES]->(exprAlt)
            RETURN coalesce(elementId(expr), elementId(exprAlt)) AS returnValueNodeId
            """;
    private static final String SUBSCRIPT_BASE_QUERY = """
            MATCH (node)
            WHERE elementId(node) = $nodeId
            OPTIONAL MATCH (node)-[:BASE]->(base)
            WITH node, base
            OPTIONAL MATCH (node)-[:ARRAY_EXPRESSION]->(arrayExpr)
            RETURN coalesce(elementId(base), elementId(arrayExpr)) AS baseNodeId
            """;
    private static final String NODE_QUERY = """
            MATCH (node)
            WHERE elementId(node) = $nodeId
            RETURN
              elementId(node) AS nodeId,
              labels(node) AS labels,
              node.name AS name,
              node.code AS code,
              node.value AS value
            """;
    private static final String AST_CHILDREN_QUERY = """
            MATCH (node)-[astRel:AST]->(child)
            WHERE elementId(node) = $nodeId
            RETURN
              astRel.index AS childIndex,
              elementId(child) AS childNodeId
            ORDER BY childIndex, childNodeId
            """;
    private static final String MARK_QUERY = """
            MATCH (node)
            WHERE elementId(node) = $nodeId
            SET node:STRUCTURALLY_SIGNIFICANT
            """;

    public int markStructuralNodes(Session session) {
        return session.executeWrite(tx -> {
            tx.run(CLEAR_QUERY).consume();

            Map<String, NodeInfo> nodeCache = new HashMap<>();
            Map<String, List<String>> astChildrenCache = new HashMap<>();
            Map<String, String> subscriptBaseCache = new HashMap<>();
            Set<String> markedNodeIds = new LinkedHashSet<>();

            List<Record> functionRecords = tx.run(FUNCTION_QUERY).list();
            for (Record functionRecord : functionRecords) {
                String functionNodeId = functionRecord.get("functionNodeId").asString();
                String bodyNodeId = functionRecord.get("bodyNodeId").asString();
                if (containsSignificantCode(
                        tx,
                        bodyNodeId,
                        List.of("Block"),
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        markedNodeIds,
                        new HashSet<>()
                )) {
                    markNode(tx, functionNodeId, markedNodeIds);
                }
            }

            return markedNodeIds.size();
        });
    }

    private boolean containsSignificantCode(
            TransactionContext tx,
            String nodeId,
            List<String> labels,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, String> subscriptBaseCache,
            Set<String> markedNodeIds,
            Set<String> visiting
    ) {
        if (nodeId == null) {
            return false;
        }
        if (markedNodeIds.contains(nodeId)) {
            return true;
        }
        if (!visiting.add(nodeId)) {
            return false;
        }

        try {
            List<String> effectiveLabels = labels;
            if (effectiveLabels == null || effectiveLabels.isEmpty()) {
                NodeInfo nodeInfo = loadNodeInfo(tx, nodeId, nodeCache);
                effectiveLabels = nodeInfo == null ? List.of() : nodeInfo.labels();
            }

            if (containsSemanticSignificance(tx, nodeId, effectiveLabels, nodeCache, astChildrenCache, subscriptBaseCache)) {
                return true;
            }

            if (effectiveLabels.contains("Block")) {
                boolean significant = false;
                List<Record> statementRecords = tx.run(
                        BLOCK_STATEMENTS_QUERY,
                        Values.parameters("blockNodeId", nodeId)
                ).list();
                for (Record statementRecord : statementRecords) {
                    if (containsSignificantCode(
                            tx,
                            statementRecord.get("statementNodeId").asString(),
                            statementRecord.get("labels").asList(Value::asString),
                            nodeCache,
                            astChildrenCache,
                            subscriptBaseCache,
                            markedNodeIds,
                            visiting
                    )) {
                        significant = true;
                    }
                }
                if (significant) {
                    markNode(tx, nodeId, markedNodeIds);
                }
                return significant;
            }

            if (effectiveLabels.contains("DeclarationStatement")) {
                boolean significant = false;
                List<Record> declarationRecords = tx.run(
                        DECLARATIONS_QUERY,
                        Values.parameters("statementNodeId", nodeId)
                ).list();
                for (Record declarationRecord : declarationRecords) {
                    if (containsSignificantCode(
                            tx,
                            declarationRecord.get("declarationNodeId").asString(),
                            List.of("ValueDeclaration"),
                            nodeCache,
                            astChildrenCache,
                            subscriptBaseCache,
                            markedNodeIds,
                            visiting
                    )) {
                        significant = true;
                    }
                }
                if (significant) {
                    markNode(tx, nodeId, markedNodeIds);
                }
                return significant;
            }

            if (effectiveLabels.contains("IfStatement")) {
                Record record = tx.run(
                        IF_DETAILS_QUERY,
                        Values.parameters("statementNodeId", nodeId)
                ).single();
                boolean thenSignificant = containsOptionalStatement(
                        tx,
                        getNullableString(record, "thenNodeId"),
                        record.get("thenLabels"),
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        markedNodeIds,
                        visiting
                );
                boolean elseSignificant = containsOptionalStatement(
                        tx,
                        getNullableString(record, "elseNodeId"),
                        record.get("elseLabels"),
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        markedNodeIds,
                        visiting
                );
                if (thenSignificant || elseSignificant) {
                    markNode(tx, nodeId, markedNodeIds);
                    return true;
                }
                return false;
            }

            if (effectiveLabels.contains("WhileStatement")
                    || effectiveLabels.contains("DoStatement")
                    || effectiveLabels.contains("DoWhileStatement")) {
                Record record = tx.run(
                        WHILE_BODY_QUERY,
                        Values.parameters("statementNodeId", nodeId)
                ).single();
                boolean bodySignificant = containsOptionalStatement(
                        tx,
                        getNullableString(record, "bodyNodeId"),
                        record.get("bodyLabels"),
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        markedNodeIds,
                        visiting
                );
                boolean conditionSignificant = containsSignificantCode(
                        tx,
                        getNullableString(record, "conditionNodeId"),
                        List.of(),
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        markedNodeIds,
                        visiting
                );
                if (bodySignificant || conditionSignificant) {
                    markNode(tx, nodeId, markedNodeIds);
                    return true;
                }
                return false;
            }

            if (effectiveLabels.contains("ForStatement")) {
                Record record = tx.run(
                        FOR_DETAILS_QUERY,
                        Values.parameters("statementNodeId", nodeId)
                ).single();
                boolean initializerSignificant = containsOptionalStatement(
                        tx,
                        getNullableString(record, "initializerNodeId"),
                        record.get("initializerLabels"),
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        markedNodeIds,
                        visiting
                );
                boolean bodySignificant = containsOptionalStatement(
                        tx,
                        getNullableString(record, "bodyNodeId"),
                        record.get("bodyLabels"),
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        markedNodeIds,
                        visiting
                );
                boolean iterationSignificant = containsOptionalStatement(
                        tx,
                        getNullableString(record, "iterationNodeId"),
                        record.get("iterationLabels"),
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        markedNodeIds,
                        visiting
                );
                boolean conditionSignificant = containsSignificantCode(
                        tx,
                        getNullableString(record, "conditionNodeId"),
                        List.of(),
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        markedNodeIds,
                        visiting
                );
                if (initializerSignificant || bodySignificant || iterationSignificant || conditionSignificant) {
                    markNode(tx, nodeId, markedNodeIds);
                    return true;
                }
                return false;
            }

            if (effectiveLabels.contains("SwitchStatement")) {
                Record record = tx.run(
                        SWITCH_DETAILS_QUERY,
                        Values.parameters("statementNodeId", nodeId)
                ).single();
                String bodyNodeId = getNullableString(record, "bodyNodeId");
                if (bodyNodeId == null) {
                    return false;
                }

                containsSignificantCode(
                        tx,
                        bodyNodeId,
                        List.of("Block"),
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        markedNodeIds,
                        visiting
                );

                List<Record> statementRecords = tx.run(
                        BLOCK_STATEMENTS_QUERY,
                        Values.parameters("blockNodeId", bodyNodeId)
                ).list();

                boolean switchSignificant = false;
                for (SwitchBranch branch : splitSwitchBranches(statementRecords)) {
                    boolean branchSignificant = false;
                    for (Record statementRecord : branch.statementRecords()) {
                        if (containsSignificantCode(
                                tx,
                                statementRecord.get("statementNodeId").asString(),
                                statementRecord.get("labels").asList(Value::asString),
                                nodeCache,
                                astChildrenCache,
                                subscriptBaseCache,
                                markedNodeIds,
                                visiting
                        )) {
                            branchSignificant = true;
                        }
                    }
                    if (branchSignificant) {
                        markNode(tx, branch.markerNodeId(), markedNodeIds);
                        switchSignificant = true;
                    }
                }

                if (switchSignificant) {
                    markNode(tx, nodeId, markedNodeIds);
                }
                return switchSignificant;
            }

            return false;
        } finally {
            visiting.remove(nodeId);
        }
    }

    private boolean containsOptionalStatement(
            TransactionContext tx,
            String statementNodeId,
            Value labelsValue,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, String> subscriptBaseCache,
            Set<String> markedNodeIds,
            Set<String> visiting
    ) {
        if (statementNodeId == null) {
            return false;
        }

        List<String> labels = labelsValue == null || labelsValue.isNull()
                ? List.of()
                : labelsValue.asList(Value::asString);

        return containsSignificantCode(
                tx,
                statementNodeId,
                labels,
                nodeCache,
                astChildrenCache,
                subscriptBaseCache,
                markedNodeIds,
                visiting
        );
    }

    private boolean containsSemanticSignificance(
            TransactionContext tx,
            String nodeId,
            List<String> labels,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, String> subscriptBaseCache
    ) {
        NodeInfo nodeInfo = loadNodeInfo(tx, nodeId, nodeCache);
        if (nodeInfo != null && nodeInfo.labels().contains("INFLUENCES_RETURN")) {
            return true;
        }

        if (labels.contains("ReturnStatement")) {
            Record record = tx.run(
                    RETURN_VALUE_QUERY,
                    Values.parameters("returnNodeId", nodeId)
            ).single();
            String returnValueNodeId = getNullableString(record, "returnValueNodeId");
            return returnValueNodeId != null && !collectReadEntities(
                    tx,
                    returnValueNodeId,
                    nodeCache,
                    astChildrenCache,
                    subscriptBaseCache,
                    new LinkedHashSet<>()
            ).isEmpty();
        }

        return false;
    }

    private void markNode(
            TransactionContext tx,
            String nodeId,
            Set<String> markedNodeIds
    ) {
        if (nodeId == null || !markedNodeIds.add(nodeId)) {
            return;
        }
        tx.run(MARK_QUERY, Values.parameters("nodeId", nodeId)).consume();
    }

    private List<SwitchBranch> splitSwitchBranches(List<Record> statementRecords) {
        List<SwitchBranch> branches = new ArrayList<>();
        SwitchBranch current = null;

        for (Record statementRecord : statementRecords) {
            String statementNodeId = statementRecord.get("statementNodeId").asString();
            List<String> labels = statementRecord.get("labels").asList(Value::asString);
            if (labels.contains("CaseStatement") || labels.contains("DefaultStatement")) {
                current = new SwitchBranch(statementNodeId, new ArrayList<>());
                branches.add(current);
                continue;
            }

            if (current != null) {
                current.statementRecords().add(statementRecord);
            }
        }

        return branches;
    }

    private Set<String> collectReadEntities(
            TransactionContext tx,
            String nodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, String> subscriptBaseCache,
            Set<String> visiting
    ) {
        Set<String> entities = new LinkedHashSet<>();
        if (nodeId == null || !visiting.add(nodeId)) {
            return entities;
        }

        try {
            NodeInfo nodeInfo = loadNodeInfo(tx, nodeId, nodeCache);
            if (nodeInfo == null) {
                return entities;
            }

            if (nodeInfo.labels().contains("Reference")) {
                String entity = firstNonBlank(nodeInfo.name(), nodeInfo.code());
                if (entity != null && !entity.isBlank()) {
                    entities.add(entity);
                }
                return entities;
            }

            if (isSubscriptExpression(nodeInfo.labels())) {
                String baseNodeId = loadSubscriptBase(tx, nodeId, subscriptBaseCache);
                if (baseNodeId != null) {
                    entities.addAll(collectReadEntities(
                            tx,
                            baseNodeId,
                            nodeCache,
                            astChildrenCache,
                            subscriptBaseCache,
                            visiting
                    ));
                }
                entities.addAll(collectArraySummaryEntities(
                        tx,
                        nodeId,
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        new LinkedHashSet<>()
                ));
                for (String childNodeId : loadAstChildren(tx, nodeId, astChildrenCache)) {
                    entities.addAll(collectReadEntities(
                            tx,
                            childNodeId,
                            nodeCache,
                            astChildrenCache,
                            subscriptBaseCache,
                            visiting
                    ));
                }
                return entities;
            }

            for (String childNodeId : loadAstChildren(tx, nodeId, astChildrenCache)) {
                entities.addAll(collectReadEntities(
                        tx,
                        childNodeId,
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        visiting
                ));
            }

            return entities;
        } finally {
            visiting.remove(nodeId);
        }
    }

    private Set<String> collectArraySummaryEntities(
            TransactionContext tx,
            String nodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, String> subscriptBaseCache,
            Set<String> visiting
    ) {
        Set<String> entities = new LinkedHashSet<>();
        if (nodeId == null || !visiting.add(nodeId)) {
            return entities;
        }

        try {
            NodeInfo nodeInfo = loadNodeInfo(tx, nodeId, nodeCache);
            if (nodeInfo == null) {
                return entities;
            }

            if (nodeInfo.labels().contains("Reference")) {
                String entity = firstNonBlank(nodeInfo.name(), nodeInfo.code());
                if (entity != null && !entity.isBlank()) {
                    entities.add(arraySummaryEntity(entity));
                }
                return entities;
            }

            if (isSubscriptExpression(nodeInfo.labels())) {
                String baseNodeId = loadSubscriptBase(tx, nodeId, subscriptBaseCache);
                if (baseNodeId != null) {
                    entities.addAll(collectArraySummaryEntities(
                            tx,
                            baseNodeId,
                            nodeCache,
                            astChildrenCache,
                            subscriptBaseCache,
                            visiting
                    ));
                }
                return entities;
            }

            for (String childNodeId : loadAstChildren(tx, nodeId, astChildrenCache)) {
                entities.addAll(collectArraySummaryEntities(
                        tx,
                        childNodeId,
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        visiting
                ));
            }

            return entities;
        } finally {
            visiting.remove(nodeId);
        }
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
                getNullableString(record, "value")
        );
        nodeCache.put(nodeId, nodeInfo);
        return nodeInfo;
    }

    private List<String> loadAstChildren(
            TransactionContext tx,
            String nodeId,
            Map<String, List<String>> astChildrenCache
    ) {
        if (nodeId == null) {
            return List.of();
        }
        if (astChildrenCache.containsKey(nodeId)) {
            return astChildrenCache.get(nodeId);
        }

        List<String> children = tx.run(AST_CHILDREN_QUERY, Values.parameters("nodeId", nodeId)).list(record ->
                record.get("childNodeId").asString()
        );
        astChildrenCache.put(nodeId, children);
        return children;
    }

    private String loadSubscriptBase(
            TransactionContext tx,
            String nodeId,
            Map<String, String> subscriptBaseCache
    ) {
        if (nodeId == null) {
            return null;
        }
        if (subscriptBaseCache.containsKey(nodeId)) {
            return subscriptBaseCache.get(nodeId);
        }

        List<Record> records = tx.run(
                SUBSCRIPT_BASE_QUERY,
                Values.parameters("nodeId", nodeId)
        ).list();
        String baseNodeId = records.isEmpty() ? null : getNullableString(records.get(0), "baseNodeId");
        subscriptBaseCache.put(nodeId, baseNodeId);
        return baseNodeId;
    }

    private boolean isSubscriptExpression(List<String> labels) {
        return labels.contains("SubscriptExpression")
                || labels.contains("ArraySubscriptionExpression")
                || labels.contains("ArrayRef");
    }

    private String arraySummaryEntity(String entity) {
        return entity.endsWith("[*]") ? entity : entity + "[*]";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
            String value
    ) {
    }

    private record SwitchBranch(
            String markerNodeId,
            List<Record> statementRecords
    ) {
    }
}

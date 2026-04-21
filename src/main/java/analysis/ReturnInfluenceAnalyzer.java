package analysis;

import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ReturnInfluenceAnalyzer {
    private static final String CLEAR_INFLUENCE_QUERY = """
            MATCH (n:INFLUENCES_RETURN)
            REMOVE n:INFLUENCES_RETURN
            """;
    private static final String CLEAR_DEAD_CODE_QUERY = """
            MATCH (n:DEAD_CODE)
            REMOVE n:DEAD_CODE
            """;
    private static final String FUNCTION_QUERY = """
            MATCH (f:FunctionDeclaration)-[:BODY]->(body:Block)
            OPTIONAL MATCH (body)-[:AST*0..]->(ret:ReturnStatement)
            WITH f, body, collect(DISTINCT elementId(ret)) AS returnNodeIds
            RETURN
              elementId(f) AS functionNodeId,
              coalesce(f.name, f.code, "<unnamed-function>") AS functionName,
              elementId(body) AS bodyNodeId,
              returnNodeIds
            ORDER BY functionName, functionNodeId
            """;
    private static final String RETURN_VALUE_QUERY = """
            MATCH (ret)
            WHERE elementId(ret) = $returnNodeId
            OPTIONAL MATCH (ret)-[:RETURN_VALUE]->(expr)
            WITH ret, expr
            OPTIONAL MATCH (ret)-[:RETURN_VALUES]->(exprAlt)
            RETURN coalesce(elementId(expr), elementId(exprAlt)) AS returnValueNodeId
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
            OPTIONAL MATCH (declaration)-[:INITIALIZER]->(initializer)
            RETURN
              elementId(declaration) AS declarationNodeId,
              declaration.name AS declarationName,
              elementId(initializer) AS initializerNodeId,
              declRel.index AS declarationIndex
            ORDER BY declarationIndex, declarationNodeId
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
            OPTIONAL MATCH (statement)-[:STATEMENT]->(body)
            RETURN
              elementId(body) AS bodyNodeId,
              labels(body) AS bodyLabels
            """;
    private static final String FOR_DETAILS_QUERY = """
            MATCH (statement)
            WHERE elementId(statement) = $statementNodeId
            OPTIONAL MATCH (statement)-[:INITIALIZER_STATEMENT]->(initializer)
            OPTIONAL MATCH (statement)-[:STATEMENT]->(body)
            OPTIONAL MATCH (statement)-[:ITERATION_STATEMENT]->(iteration)
            RETURN
              elementId(initializer) AS initializerNodeId,
              labels(initializer) AS initializerLabels,
              elementId(body) AS bodyNodeId,
              labels(body) AS bodyLabels,
              elementId(iteration) AS iterationNodeId,
              labels(iteration) AS iterationLabels
            """;
    private static final String NODE_QUERY = """
            MATCH (node)
            WHERE elementId(node) = $nodeId
            RETURN
              elementId(node) AS nodeId,
              labels(node) AS labels,
              node.name AS name,
              node.code AS code,
              node.value AS value,
              node.operatorCode AS operatorCode
            """;
    private static final String AST_CHILDREN_QUERY = """
            MATCH (node)-[astRel:AST]->(child)
            WHERE elementId(node) = $nodeId
            RETURN
              astRel.index AS childIndex,
              elementId(child) AS childNodeId
            ORDER BY childIndex, childNodeId
            """;
    private static final String SUBSCRIPT_BASE_QUERY = """
            MATCH (node)
            WHERE elementId(node) = $nodeId
            OPTIONAL MATCH (node)-[:BASE]->(base)
            WITH node, base
            OPTIONAL MATCH (node)-[:ARRAY_EXPRESSION]->(arrayExpr)
            RETURN coalesce(elementId(base), elementId(arrayExpr)) AS baseNodeId
            """;
    private static final String MARK_INFLUENCE_QUERY = """
            MATCH (node)
            WHERE elementId(node) = $nodeId
            SET node:INFLUENCES_RETURN
            """;

    public AnalysisSummary analyze(Session session) {
        return session.executeWrite(tx -> {
            tx.run(CLEAR_INFLUENCE_QUERY).consume();
            tx.run(CLEAR_DEAD_CODE_QUERY).consume();

            Map<String, NodeInfo> nodeCache = new HashMap<>();
            Map<String, List<String>> astChildrenCache = new HashMap<>();
            Map<String, AssignmentSides> assignmentSidesCache = new HashMap<>();
            Map<String, String> subscriptBaseCache = new HashMap<>();

            List<Record> functionRecords = tx.run(FUNCTION_QUERY).list();
            List<FunctionInfluence> functions = new ArrayList<>();
            int totalMarked = 0;

            for (Record functionRecord : functionRecords) {
                String functionNodeId = functionRecord.get("functionNodeId").asString();
                String functionName = functionRecord.get("functionName").asString();
                String bodyNodeId = functionRecord.get("bodyNodeId").asString();
                List<String> returnNodeIds = functionRecord.get("returnNodeIds").asList(Value::asString);

                if (returnNodeIds.size() != 1) {
                    functions.add(new FunctionInfluence(functionName, functionNodeId, 0, List.of(), false));
                    continue;
                }

                String returnNodeId = returnNodeIds.get(0);
                Record returnValueRecord = tx.run(
                        RETURN_VALUE_QUERY,
                        Values.parameters("returnNodeId", returnNodeId)
                ).single();
                String returnValueNodeId = getNullableString(returnValueRecord, "returnValueNodeId");
                if (returnValueNodeId == null) {
                    functions.add(new FunctionInfluence(functionName, functionNodeId, 0, List.of(), false));
                    continue;
                }

                Set<String> neededEntities = collectReadEntities(
                        tx,
                        returnValueNodeId,
                        nodeCache,
                        astChildrenCache,
                        subscriptBaseCache,
                        new LinkedHashSet<>()
                );

                if (neededEntities.isEmpty()) {
                    functions.add(new FunctionInfluence(
                            functionName,
                            functionNodeId,
                            0,
                            List.of(),
                            true
                    ));
                    continue;
                }

                LinkedHashSet<String> markedNodeIds = new LinkedHashSet<>();
                analyzeBlock(
                        tx,
                        bodyNodeId,
                        neededEntities,
                        markedNodeIds,
                        nodeCache,
                        astChildrenCache,
                        assignmentSidesCache,
                        subscriptBaseCache
                );

                List<String> markedCodes = new ArrayList<>();
                List<String> markOrder = new ArrayList<>(markedNodeIds);
                Collections.reverse(markOrder);
                for (String markedNodeId : markOrder) {
                    tx.run(MARK_INFLUENCE_QUERY, Values.parameters("nodeId", markedNodeId)).consume();
                    NodeInfo nodeInfo = loadNodeInfo(tx, markedNodeId, nodeCache);
                    if (nodeInfo != null) {
                        String code = firstNonBlank(nodeInfo.code(), nodeInfo.name(), nodeInfo.value());
                        if (code != null && !code.isBlank()) {
                            markedCodes.add(code);
                        }
                    }
                }

                totalMarked += markedNodeIds.size();
                functions.add(new FunctionInfluence(
                        functionName,
                        functionNodeId,
                        markedNodeIds.size(),
                        markedCodes,
                        true
                ));
            }

            return new AnalysisSummary(totalMarked, functions);
        });
    }

    private Set<String> analyzeBlock(
            TransactionContext tx,
            String blockNodeId,
            Set<String> neededAfter,
            LinkedHashSet<String> markedNodeIds,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, AssignmentSides> assignmentSidesCache,
            Map<String, String> subscriptBaseCache
    ) {
        List<Record> statementRecords = tx.run(
                BLOCK_STATEMENTS_QUERY,
                Values.parameters("blockNodeId", blockNodeId)
        ).list();

        Set<String> needed = new LinkedHashSet<>(neededAfter);
        for (int index = statementRecords.size() - 1; index >= 0; index--) {
            Record statementRecord = statementRecords.get(index);
            needed = analyzeStatement(
                    tx,
                    statementRecord.get("statementNodeId").asString(),
                    statementRecord.get("labels").asList(Value::asString),
                    needed,
                    markedNodeIds,
                    nodeCache,
                    astChildrenCache,
                    assignmentSidesCache,
                    subscriptBaseCache
            );
        }

        return needed;
    }

    private Set<String> analyzeStatement(
            TransactionContext tx,
            String statementNodeId,
            List<String> labels,
            Set<String> neededAfter,
            LinkedHashSet<String> markedNodeIds,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, AssignmentSides> assignmentSidesCache,
            Map<String, String> subscriptBaseCache
    ) {
        if (labels.contains("Block")) {
            return analyzeBlock(
                    tx,
                    statementNodeId,
                    neededAfter,
                    markedNodeIds,
                    nodeCache,
                    astChildrenCache,
                    assignmentSidesCache,
                    subscriptBaseCache
            );
        }

        if (labels.contains("DeclarationStatement")) {
            return analyzeDeclarationStatement(
                    tx,
                    statementNodeId,
                    neededAfter,
                    markedNodeIds,
                    nodeCache,
                    astChildrenCache,
                    subscriptBaseCache
            );
        }

        if (labels.contains("AssignExpression")) {
            return analyzeAssignment(
                    tx,
                    statementNodeId,
                    neededAfter,
                    markedNodeIds,
                    nodeCache,
                    astChildrenCache,
                    assignmentSidesCache,
                    subscriptBaseCache
            );
        }

        if (labels.contains("UnaryOperator")) {
            return analyzeUnaryStatement(
                    tx,
                    statementNodeId,
                    neededAfter,
                    markedNodeIds,
                    nodeCache,
                    astChildrenCache,
                    subscriptBaseCache
            );
        }

        if (labels.contains("IfStatement")) {
            return analyzeIfStatement(
                    tx,
                    statementNodeId,
                    neededAfter,
                    markedNodeIds,
                    nodeCache,
                    astChildrenCache,
                    assignmentSidesCache,
                    subscriptBaseCache
            );
        }

        if (labels.contains("ForStatement")) {
            return analyzeForStatement(
                    tx,
                    statementNodeId,
                    neededAfter,
                    markedNodeIds,
                    nodeCache,
                    astChildrenCache,
                    assignmentSidesCache,
                    subscriptBaseCache
            );
        }

        if (labels.contains("DoStatement") || labels.contains("DoWhileStatement")) {
            return analyzeDoWhileStatement(
                    tx,
                    statementNodeId,
                    neededAfter,
                    markedNodeIds,
                    nodeCache,
                    astChildrenCache,
                    assignmentSidesCache,
                    subscriptBaseCache
            );
        }

        if (labels.contains("WhileStatement")) {
            return analyzeWhileStatement(
                    tx,
                    statementNodeId,
                    neededAfter,
                    markedNodeIds,
                    nodeCache,
                    astChildrenCache,
                    assignmentSidesCache,
                    subscriptBaseCache
            );
        }

        return new LinkedHashSet<>(neededAfter);
    }

    private Set<String> analyzeDeclarationStatement(
            TransactionContext tx,
            String statementNodeId,
            Set<String> neededAfter,
            LinkedHashSet<String> markedNodeIds,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, String> subscriptBaseCache
    ) {
        List<Record> declarationRecords = tx.run(
                DECLARATIONS_QUERY,
                Values.parameters("statementNodeId", statementNodeId)
        ).list();

        Set<String> needed = new LinkedHashSet<>(neededAfter);
        for (int index = declarationRecords.size() - 1; index >= 0; index--) {
            Record declarationRecord = declarationRecords.get(index);
            String declarationNodeId = declarationRecord.get("declarationNodeId").asString();
            String declarationName = getNullableString(declarationRecord, "declarationName");
            String initializerNodeId = getNullableString(declarationRecord, "initializerNodeId");
            if (declarationName == null || declarationName.isBlank() || initializerNodeId == null) {
                continue;
            }

            Set<String> defs = Set.of(declarationName);
            Set<String> uses = collectReadEntities(
                    tx,
                    initializerNodeId,
                    nodeCache,
                    astChildrenCache,
                    subscriptBaseCache,
                    new LinkedHashSet<>()
            );

            if (intersects(needed, defs)) {
                markedNodeIds.add(declarationNodeId);
                needed = transfer(needed, defs, uses);
            }
        }

        return needed;
    }

    private Set<String> analyzeAssignment(
            TransactionContext tx,
            String statementNodeId,
            Set<String> neededAfter,
            LinkedHashSet<String> markedNodeIds,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, AssignmentSides> assignmentSidesCache,
            Map<String, String> subscriptBaseCache
    ) {
        AssignmentSides sides = loadAssignmentSides(tx, statementNodeId, assignmentSidesCache);
        if (sides == null || sides.lhsNodeId() == null || sides.rhsNodeId() == null) {
            return new LinkedHashSet<>(neededAfter);
        }

        NodeInfo assignmentNode = loadNodeInfo(tx, statementNodeId, nodeCache);
        String operator = assignmentNode == null ? "=" : normalizedOperator(assignmentNode.operatorCode());

        Set<String> defs = collectWrittenEntities(
                tx,
                sides.lhsNodeId(),
                nodeCache,
                astChildrenCache,
                subscriptBaseCache,
                new LinkedHashSet<>()
        );
        Set<String> uses = collectReadEntities(
                tx,
                sides.rhsNodeId(),
                nodeCache,
                astChildrenCache,
                subscriptBaseCache,
                new LinkedHashSet<>()
        );

        if (!"=".equals(operator)) {
            uses.addAll(collectReadEntities(
                    tx,
                    sides.lhsNodeId(),
                    nodeCache,
                    astChildrenCache,
                    subscriptBaseCache,
                    new LinkedHashSet<>()
            ));
        }

        if (intersects(neededAfter, defs)) {
            markedNodeIds.add(statementNodeId);
            return transfer(neededAfter, defs, uses);
        }

        return new LinkedHashSet<>(neededAfter);
    }

    private Set<String> analyzeUnaryStatement(
            TransactionContext tx,
            String statementNodeId,
            Set<String> neededAfter,
            LinkedHashSet<String> markedNodeIds,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, String> subscriptBaseCache
    ) {
        NodeInfo nodeInfo = loadNodeInfo(tx, statementNodeId, nodeCache);
        if (nodeInfo == null) {
            return new LinkedHashSet<>(neededAfter);
        }

        String operator = normalizedOperator(nodeInfo.operatorCode());
        if (!"++".equals(operator) && !"--".equals(operator)) {
            return new LinkedHashSet<>(neededAfter);
        }

        List<String> children = loadAstChildren(tx, statementNodeId, astChildrenCache);
        if (children.isEmpty()) {
            return new LinkedHashSet<>(neededAfter);
        }

        Set<String> defs = collectWrittenEntities(
                tx,
                children.get(0),
                nodeCache,
                astChildrenCache,
                subscriptBaseCache,
                new LinkedHashSet<>()
        );
        Set<String> uses = collectReadEntities(
                tx,
                children.get(0),
                nodeCache,
                astChildrenCache,
                subscriptBaseCache,
                new LinkedHashSet<>()
        );

        if (intersects(neededAfter, defs)) {
            markedNodeIds.add(statementNodeId);
            return transfer(neededAfter, defs, uses);
        }

        return new LinkedHashSet<>(neededAfter);
    }

    private Set<String> analyzeIfStatement(
            TransactionContext tx,
            String statementNodeId,
            Set<String> neededAfter,
            LinkedHashSet<String> markedNodeIds,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, AssignmentSides> assignmentSidesCache,
            Map<String, String> subscriptBaseCache
    ) {
        Record record = tx.run(
                IF_DETAILS_QUERY,
                Values.parameters("statementNodeId", statementNodeId)
        ).single();

        Set<String> thenNeeded = analyzeOptionalStatement(
                tx,
                getNullableString(record, "thenNodeId"),
                record.get("thenLabels"),
                neededAfter,
                markedNodeIds,
                nodeCache,
                astChildrenCache,
                assignmentSidesCache,
                subscriptBaseCache
        );

        Set<String> elseNeeded = record.get("elseNodeId").isNull()
                ? new LinkedHashSet<>(neededAfter)
                : analyzeOptionalStatement(
                        tx,
                        getNullableString(record, "elseNodeId"),
                        record.get("elseLabels"),
                        neededAfter,
                        markedNodeIds,
                        nodeCache,
                        astChildrenCache,
                        assignmentSidesCache,
                        subscriptBaseCache
                );

        Set<String> result = new LinkedHashSet<>(thenNeeded);
        result.addAll(elseNeeded);
        return result;
    }

    private Set<String> analyzeWhileStatement(
            TransactionContext tx,
            String statementNodeId,
            Set<String> neededAfter,
            LinkedHashSet<String> markedNodeIds,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, AssignmentSides> assignmentSidesCache,
            Map<String, String> subscriptBaseCache
    ) {
        Record record = tx.run(
                WHILE_BODY_QUERY,
                Values.parameters("statementNodeId", statementNodeId)
        ).single();

        Set<String> bodyNeeded = analyzeOptionalStatement(
                tx,
                getNullableString(record, "bodyNodeId"),
                record.get("bodyLabels"),
                neededAfter,
                markedNodeIds,
                nodeCache,
                astChildrenCache,
                assignmentSidesCache,
                subscriptBaseCache
        );

        Set<String> result = new LinkedHashSet<>(neededAfter);
        result.addAll(bodyNeeded);
        return result;
    }

    private Set<String> analyzeDoWhileStatement(
            TransactionContext tx,
            String statementNodeId,
            Set<String> neededAfter,
            LinkedHashSet<String> markedNodeIds,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, AssignmentSides> assignmentSidesCache,
            Map<String, String> subscriptBaseCache
    ) {
        Record record = tx.run(
                WHILE_BODY_QUERY,
                Values.parameters("statementNodeId", statementNodeId)
        ).single();

        return analyzeOptionalStatement(
                tx,
                getNullableString(record, "bodyNodeId"),
                record.get("bodyLabels"),
                neededAfter,
                markedNodeIds,
                nodeCache,
                astChildrenCache,
                assignmentSidesCache,
                subscriptBaseCache
        );
    }

    private Set<String> analyzeForStatement(
            TransactionContext tx,
            String statementNodeId,
            Set<String> neededAfter,
            LinkedHashSet<String> markedNodeIds,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, AssignmentSides> assignmentSidesCache,
            Map<String, String> subscriptBaseCache
    ) {
        Record record = tx.run(
                FOR_DETAILS_QUERY,
                Values.parameters("statementNodeId", statementNodeId)
        ).single();

        Set<String> afterIteration = analyzeOptionalStatement(
                tx,
                getNullableString(record, "iterationNodeId"),
                record.get("iterationLabels"),
                neededAfter,
                markedNodeIds,
                nodeCache,
                astChildrenCache,
                assignmentSidesCache,
                subscriptBaseCache
        );

        Set<String> beforeBody = analyzeOptionalStatement(
                tx,
                getNullableString(record, "bodyNodeId"),
                record.get("bodyLabels"),
                afterIteration,
                markedNodeIds,
                nodeCache,
                astChildrenCache,
                assignmentSidesCache,
                subscriptBaseCache
        );

        Set<String> beforeLoop = new LinkedHashSet<>(neededAfter);
        beforeLoop.addAll(beforeBody);

        return analyzeOptionalStatement(
                tx,
                getNullableString(record, "initializerNodeId"),
                record.get("initializerLabels"),
                beforeLoop,
                markedNodeIds,
                nodeCache,
                astChildrenCache,
                assignmentSidesCache,
                subscriptBaseCache
        );
    }

    private Set<String> analyzeOptionalStatement(
            TransactionContext tx,
            String statementNodeId,
            Value labelsValue,
            Set<String> neededAfter,
            LinkedHashSet<String> markedNodeIds,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, AssignmentSides> assignmentSidesCache,
            Map<String, String> subscriptBaseCache
    ) {
        if (statementNodeId == null) {
            return new LinkedHashSet<>(neededAfter);
        }

        List<String> labels = labelsValue == null || labelsValue.isNull()
                ? List.of()
                : labelsValue.asList(Value::asString);

        return analyzeStatement(
                tx,
                statementNodeId,
                labels,
                neededAfter,
                markedNodeIds,
                nodeCache,
                astChildrenCache,
                assignmentSidesCache,
                subscriptBaseCache
        );
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

    private Set<String> collectWrittenEntities(
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
                    entities.addAll(collectWrittenEntities(
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
                entities.addAll(collectWrittenEntities(
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
                getNullableString(record, "value"),
                getNullableString(record, "operatorCode")
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

    private AssignmentSides loadAssignmentSides(
            TransactionContext tx,
            String assignmentNodeId,
            Map<String, AssignmentSides> assignmentSidesCache
    ) {
        if (assignmentNodeId == null) {
            return null;
        }
        if (assignmentSidesCache.containsKey(assignmentNodeId)) {
            return assignmentSidesCache.get(assignmentNodeId);
        }

        List<Record> records = tx.run(
                ASSIGNMENT_SIDES_QUERY,
                Values.parameters("assignmentNodeId", assignmentNodeId)
        ).list();
        if (records.isEmpty()) {
            assignmentSidesCache.put(assignmentNodeId, null);
            return null;
        }

        Record record = records.get(0);
        AssignmentSides sides = new AssignmentSides(
                getNullableString(record, "lhsNodeId"),
                getNullableString(record, "rhsNodeId")
        );
        assignmentSidesCache.put(assignmentNodeId, sides);
        return sides;
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

    private Set<String> transfer(Set<String> neededAfter, Set<String> defs, Set<String> uses) {
        Set<String> neededBefore = new LinkedHashSet<>(neededAfter);
        neededBefore.removeAll(defs);
        neededBefore.addAll(uses);
        return neededBefore;
    }

    private boolean intersects(Set<String> left, Set<String> right) {
        for (String value : right) {
            if (left.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSubscriptExpression(List<String> labels) {
        return labels.contains("SubscriptExpression")
                || labels.contains("ArraySubscriptionExpression")
                || labels.contains("ArrayRef");
    }

    private String normalizedOperator(String operatorCode) {
        return operatorCode == null || operatorCode.isBlank() ? "=" : operatorCode;
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
            String value,
            String operatorCode
    ) {
    }

    private record AssignmentSides(
            String lhsNodeId,
            String rhsNodeId
    ) {
    }

    public record FunctionInfluence(
            String functionName,
            String functionNodeId,
            int markedNodes,
            List<String> markedCodes,
            boolean analyzed
    ) {
    }

    public record AnalysisSummary(
            int totalMarked,
            List<FunctionInfluence> functions
    ) {
    }
}

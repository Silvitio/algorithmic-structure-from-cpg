package analysis;

import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RepeatedAssignmentAnalyzer {
    private static final String ALL_BLOCKS_QUERY = """
            MATCH (block:Block)
            RETURN elementId(block) AS blockNodeId
            ORDER BY blockNodeId
            """;
    private static final String BLOCK_STATEMENTS_QUERY = """
            MATCH (block)-[stmtRel:STATEMENTS]->(statement)
            WHERE elementId(block) = $blockNodeId
              AND NOT statement:DEAD_CODE
            RETURN
              elementId(statement) AS statementNodeId,
              labels(statement) AS labels,
              stmtRel.index AS statementIndex
            ORDER BY statementIndex, statementNodeId
            """;
    private static final String DECLARATIONS_QUERY = """
            MATCH (statement)-[declRel:DECLARATIONS]->(declaration:ValueDeclaration)
            WHERE elementId(statement) = $statementNodeId
              AND NOT declaration:DEAD_CODE
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
            OPTIONAL MATCH (statement)-[:CONDITION]->(condition)
            OPTIONAL MATCH (statement)-[:THEN_STATEMENT]->(thenBranch)
            OPTIONAL MATCH (statement)-[:ELSE_STATEMENT]->(elseBranch)
            RETURN
              elementId(condition) AS conditionNodeId,
              elementId(thenBranch) AS thenNodeId,
              labels(thenBranch) AS thenLabels,
              elementId(elseBranch) AS elseNodeId,
              labels(elseBranch) AS elseLabels
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
              elementId(child) AS childNodeId,
              labels(child) AS childLabels
            ORDER BY childIndex, childNodeId
            """;
    private static final String MARK_DEAD_CODE_QUERY = """
            MATCH (node)
            WHERE elementId(node) = $nodeId
            SET node:DEAD_CODE
            """;

    public int markDeadCode(Session session) {
        return session.executeWrite(tx -> {
            List<String> blockNodeIds = tx.run(ALL_BLOCKS_QUERY).list(record ->
                    record.get("blockNodeId").asString()
            );

            Map<String, NodeInfo> nodeCache = new HashMap<>();
            Map<String, List<String>> astChildrenCache = new HashMap<>();
            Map<String, AssignmentSides> assignmentSidesCache = new HashMap<>();
            int markedCount = 0;

            for (String blockNodeId : blockNodeIds) {
                markedCount += analyzeBlock(tx, blockNodeId, nodeCache, astChildrenCache, assignmentSidesCache);
            }

            return markedCount;
        });
    }

    private int analyzeBlock(
            TransactionContext tx,
            String blockNodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, AssignmentSides> assignmentSidesCache
    ) {
        List<Record> statementRecords = tx.run(
                BLOCK_STATEMENTS_QUERY,
                Values.parameters("blockNodeId", blockNodeId)
        ).list();

        Map<String, Integer> variableVersions = new HashMap<>();
        Map<String, TrackedWrite> trackedWrites = new HashMap<>();
        int markedCount = 0;

        for (Record statementRecord : statementRecords) {
            String statementNodeId = statementRecord.get("statementNodeId").asString();
            List<String> labels = statementRecord.get("labels").asList(Value::asString);

            List<AssignmentCandidate> candidates = extractAssignmentCandidates(
                    tx,
                    statementNodeId,
                    labels,
                    nodeCache,
                    astChildrenCache,
                    assignmentSidesCache
            );

            if (!candidates.isEmpty()) {
                for (AssignmentCandidate candidate : candidates) {
                    markVariablesAsUsed(trackedWrites, candidate.dependencies());

                    boolean redundant = isRedundant(candidate, trackedWrites, variableVersions);
                    if (redundant) {
                        tx.run(MARK_DEAD_CODE_QUERY, Values.parameters("nodeId", candidate.sourceNodeId())).consume();
                        markedCount++;
                        continue;
                    }

                    markedCount += markOverwrittenWrites(
                            tx,
                            trackedWrites,
                            candidate.targetVariable(),
                            candidate.sideEffectWrites()
                    );

                    applyWrites(variableVersions, candidate.targetVariable(), candidate.sideEffectWrites());

                    if (candidate.sideEffectWrites().isEmpty()) {
                        trackedWrites.put(candidate.targetVariable(), trackedWrite(candidate, variableVersions));
                    }
                }
                continue;
            }

            if (labels.contains("UnaryOperator")) {
                String writtenVariable = extractUnaryMutationTarget(
                        tx,
                        statementNodeId,
                        nodeCache,
                        astChildrenCache
                );
                if (writtenVariable != null) {
                    trackedWrites.remove(writtenVariable);
                    incrementVersion(variableVersions, writtenVariable);
                    continue;
                }
            }

            if (labels.contains("IfStatement")) {
                StatementEffects effects = collectIfEffects(
                        tx,
                        statementNodeId,
                        nodeCache,
                        astChildrenCache,
                        assignmentSidesCache
                );
                markVariablesAsUsed(trackedWrites, effects.readVariables());
                markedCount += markOverwrittenWrites(tx, trackedWrites, effects.writtenVariables(), Set.of());
                applyWrites(variableVersions, effects.writtenVariables());
                continue;
            }

            if (labels.contains("WhileStatement")
                    || labels.contains("ForStatement")
                    || labels.contains("DoStatement")
                    || labels.contains("DoWhileStatement")
                    || labels.contains("SwitchStatement")
                    || labels.contains("CaseStatement")
                    || labels.contains("DefaultStatement")) {
                variableVersions.clear();
                trackedWrites.clear();
            }
        }

        return markedCount;
    }

    private List<AssignmentCandidate> extractAssignmentCandidates(
            TransactionContext tx,
            String statementNodeId,
            List<String> labels,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, AssignmentSides> assignmentSidesCache
    ) {
        if (labels.contains("DeclarationStatement")) {
            List<Record> declarationRecords = tx.run(
                    DECLARATIONS_QUERY,
                    Values.parameters("statementNodeId", statementNodeId)
            ).list();

            List<AssignmentCandidate> candidates = new ArrayList<>();
            for (Record declarationRecord : declarationRecords) {
                String declarationName = getNullableString(declarationRecord, "declarationName");
                String initializerNodeId = getNullableString(declarationRecord, "initializerNodeId");
                if (declarationName == null || declarationName.isBlank() || initializerNodeId == null) {
                    continue;
                }

                Set<String> dependencies = collectDependencies(tx, initializerNodeId, nodeCache, astChildrenCache, new HashSet<>());
                Set<String> sideEffectWrites = collectMutations(
                        tx,
                        initializerNodeId,
                        nodeCache,
                        astChildrenCache,
                        assignmentSidesCache,
                        new HashSet<>()
                );
                String expressionKey = buildExpressionKey(tx, initializerNodeId, nodeCache, astChildrenCache, new HashSet<>());
                if (expressionKey == null || expressionKey.isBlank()) {
                    continue;
                }

                candidates.add(new AssignmentCandidate(
                        declarationRecord.get("declarationNodeId").asString(),
                        declarationName,
                        expressionKey,
                        dependencies,
                        sideEffectWrites
                ));
            }
            return candidates;
        }

        if (!labels.contains("AssignExpression")) {
            return List.of();
        }

        NodeInfo assignmentNode = loadNodeInfo(tx, statementNodeId, nodeCache);
        if (assignmentNode == null || !"=".equals(normalizedOperator(assignmentNode.operatorCode()))) {
            return List.of();
        }

        AssignmentSides sides = loadAssignmentSides(tx, statementNodeId, assignmentSidesCache);
        if (sides == null || sides.lhsNodeId() == null || sides.rhsNodeId() == null) {
            return List.of();
        }

        String targetVariable = extractSimpleVariable(tx, sides.lhsNodeId(), nodeCache);
        if (targetVariable == null) {
            return List.of();
        }

        String expressionKey = buildExpressionKey(tx, sides.rhsNodeId(), nodeCache, astChildrenCache, new HashSet<>());
        if (expressionKey == null || expressionKey.isBlank()) {
            return List.of();
        }

        Set<String> dependencies = collectDependencies(tx, sides.rhsNodeId(), nodeCache, astChildrenCache, new HashSet<>());
        Set<String> sideEffectWrites = collectMutations(
                tx,
                sides.rhsNodeId(),
                nodeCache,
                astChildrenCache,
                assignmentSidesCache,
                new HashSet<>()
        );
        return List.of(new AssignmentCandidate(
                statementNodeId,
                targetVariable,
                expressionKey,
                dependencies,
                sideEffectWrites
        ));
    }

    private boolean isRedundant(
            AssignmentCandidate candidate,
            Map<String, TrackedWrite> trackedWrites,
            Map<String, Integer> variableVersions
    ) {
        if (!candidate.sideEffectWrites().isEmpty()) {
            return false;
        }

        TrackedWrite previous = trackedWrites.get(candidate.targetVariable());
        if (previous == null) {
            return false;
        }
        if (!previous.expressionKey().equals(candidate.expressionKey())) {
            return false;
        }

        Set<String> relevantVariables = new HashSet<>(candidate.dependencies());
        relevantVariables.add(candidate.targetVariable());

        for (String variable : relevantVariables) {
            int currentVersion = variableVersions.getOrDefault(variable, 0);
            int expectedVersion = previous.variableVersions().getOrDefault(variable, 0);
            if (currentVersion != expectedVersion) {
                return false;
            }
        }

        return true;
    }

    private TrackedWrite trackedWrite(
            AssignmentCandidate candidate,
            Map<String, Integer> variableVersions
    ) {
        Set<String> relevantVariables = new HashSet<>(candidate.dependencies());
        relevantVariables.add(candidate.targetVariable());

        Map<String, Integer> snapshotVersions = new HashMap<>();
        for (String variable : relevantVariables) {
            snapshotVersions.put(variable, variableVersions.getOrDefault(variable, 0));
        }

        return new TrackedWrite(
                candidate.sourceNodeId(),
                candidate.targetVariable(),
                candidate.expressionKey(),
                snapshotVersions
        );
    }

    private void applyWrites(
            Map<String, Integer> variableVersions,
            String targetVariable,
            Set<String> sideEffectWrites
    ) {
        incrementVersion(variableVersions, targetVariable);
        for (String sideEffectWrite : sideEffectWrites) {
            incrementVersion(variableVersions, sideEffectWrite);
        }
    }

    private void applyWrites(
            Map<String, Integer> variableVersions,
            Set<String> writtenVariables
    ) {
        for (String writtenVariable : writtenVariables) {
            incrementVersion(variableVersions, writtenVariable);
        }
    }

    private void markVariablesAsUsed(
            Map<String, TrackedWrite> trackedWrites,
            Set<String> variables
    ) {
        for (String variable : variables) {
            trackedWrites.remove(variable);
        }
    }

    private int markOverwrittenWrites(
            TransactionContext tx,
            Map<String, TrackedWrite> trackedWrites,
            String targetVariable,
            Set<String> sideEffectWrites
    ) {
        int markedCount = 0;
        markedCount += markTrackedWriteDead(tx, trackedWrites, targetVariable);
        for (String sideEffectWrite : sideEffectWrites) {
            markedCount += markTrackedWriteDead(tx, trackedWrites, sideEffectWrite);
        }
        return markedCount;
    }

    private int markOverwrittenWrites(
            TransactionContext tx,
            Map<String, TrackedWrite> trackedWrites,
            Set<String> targetVariables,
            Set<String> sideEffectWrites
    ) {
        int markedCount = 0;
        for (String targetVariable : targetVariables) {
            markedCount += markTrackedWriteDead(tx, trackedWrites, targetVariable);
        }
        for (String sideEffectWrite : sideEffectWrites) {
            markedCount += markTrackedWriteDead(tx, trackedWrites, sideEffectWrite);
        }
        return markedCount;
    }

    private int markTrackedWriteDead(
            TransactionContext tx,
            Map<String, TrackedWrite> trackedWrites,
            String variable
    ) {
        TrackedWrite trackedWrite = trackedWrites.remove(variable);
        if (trackedWrite == null) {
            return 0;
        }

        tx.run(MARK_DEAD_CODE_QUERY, Values.parameters("nodeId", trackedWrite.sourceNodeId())).consume();
        return 1;
    }

    private String extractUnaryMutationTarget(
            TransactionContext tx,
            String statementNodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache
    ) {
        NodeInfo nodeInfo = loadNodeInfo(tx, statementNodeId, nodeCache);
        if (nodeInfo == null) {
            return null;
        }

        String operator = normalizedOperator(nodeInfo.operatorCode());
        if (!"++".equals(operator) && !"--".equals(operator)) {
            return null;
        }

        List<String> children = loadAstChildren(tx, statementNodeId, astChildrenCache);
        if (children.isEmpty()) {
            return null;
        }

        return extractSimpleVariable(tx, children.get(0), nodeCache);
    }

    private String extractSimpleVariable(
            TransactionContext tx,
            String nodeId,
            Map<String, NodeInfo> nodeCache
    ) {
        NodeInfo nodeInfo = loadNodeInfo(tx, nodeId, nodeCache);
        if (nodeInfo == null) {
            return null;
        }
        if (nodeInfo.labels().contains("Reference")) {
            return firstNonBlank(nodeInfo.name(), nodeInfo.code());
        }
        return null;
    }

    private StatementEffects collectIfEffects(
            TransactionContext tx,
            String statementNodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, AssignmentSides> assignmentSidesCache
    ) {
        Record record = tx.run(
                IF_DETAILS_QUERY,
                Values.parameters("statementNodeId", statementNodeId)
        ).single();

        Set<String> reads = new HashSet<>();
        Set<String> writes = new HashSet<>();

        String conditionNodeId = getNullableString(record, "conditionNodeId");
        if (conditionNodeId != null) {
            reads.addAll(collectDependencies(tx, conditionNodeId, nodeCache, astChildrenCache, new HashSet<>()));
            writes.addAll(collectMutations(
                    tx,
                    conditionNodeId,
                    nodeCache,
                    astChildrenCache,
                    assignmentSidesCache,
                    new HashSet<>()
            ));
        }

        collectOptionalStatementEffects(
                tx,
                getNullableString(record, "thenNodeId"),
                record.get("thenLabels"),
                nodeCache,
                astChildrenCache,
                assignmentSidesCache,
                reads,
                writes
        );
        collectOptionalStatementEffects(
                tx,
                getNullableString(record, "elseNodeId"),
                record.get("elseLabels"),
                nodeCache,
                astChildrenCache,
                assignmentSidesCache,
                reads,
                writes
        );

        return new StatementEffects(reads, writes);
    }

    private void collectOptionalStatementEffects(
            TransactionContext tx,
            String statementNodeId,
            Value labelsValue,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, AssignmentSides> assignmentSidesCache,
            Set<String> reads,
            Set<String> writes
    ) {
        if (statementNodeId == null) {
            return;
        }

        StatementEffects effects = collectStatementEffects(
                tx,
                statementNodeId,
                labelsValue == null || labelsValue.isNull() ? List.of() : labelsValue.asList(Value::asString),
                nodeCache,
                astChildrenCache,
                assignmentSidesCache
        );
        reads.addAll(effects.readVariables());
        writes.addAll(effects.writtenVariables());
    }

    private StatementEffects collectStatementEffects(
            TransactionContext tx,
            String statementNodeId,
            List<String> labels,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, AssignmentSides> assignmentSidesCache
    ) {
        if (labels.contains("Block")) {
            Set<String> reads = new HashSet<>();
            Set<String> writes = new HashSet<>();

            List<Record> statementRecords = tx.run(
                    BLOCK_STATEMENTS_QUERY,
                    Values.parameters("blockNodeId", statementNodeId)
            ).list();
            for (Record statementRecord : statementRecords) {
                StatementEffects nested = collectStatementEffects(
                        tx,
                        statementRecord.get("statementNodeId").asString(),
                        statementRecord.get("labels").asList(Value::asString),
                        nodeCache,
                        astChildrenCache,
                        assignmentSidesCache
                );
                reads.addAll(nested.readVariables());
                writes.addAll(nested.writtenVariables());
            }

            return new StatementEffects(reads, writes);
        }

        if (labels.contains("DeclarationStatement")) {
            Set<String> reads = new HashSet<>();
            Set<String> writes = new HashSet<>();

            List<Record> declarationRecords = tx.run(
                    DECLARATIONS_QUERY,
                    Values.parameters("statementNodeId", statementNodeId)
            ).list();

            for (Record declarationRecord : declarationRecords) {
                String declarationName = getNullableString(declarationRecord, "declarationName");
                String initializerNodeId = getNullableString(declarationRecord, "initializerNodeId");
                if (declarationName == null || declarationName.isBlank()) {
                    continue;
                }
                if (initializerNodeId != null) {
                    reads.addAll(collectDependencies(tx, initializerNodeId, nodeCache, astChildrenCache, new HashSet<>()));
                    writes.addAll(collectMutations(
                            tx,
                            initializerNodeId,
                            nodeCache,
                            astChildrenCache,
                            assignmentSidesCache,
                            new HashSet<>()
                    ));
                    writes.add(declarationName);
                }
            }

            return new StatementEffects(reads, writes);
        }

        if (labels.contains("AssignExpression")) {
            AssignmentSides sides = loadAssignmentSides(tx, statementNodeId, assignmentSidesCache);
            if (sides == null) {
                return StatementEffects.empty();
            }

            Set<String> reads = new HashSet<>();
            Set<String> writes = new HashSet<>();

            if (sides.rhsNodeId() != null) {
                reads.addAll(collectDependencies(tx, sides.rhsNodeId(), nodeCache, astChildrenCache, new HashSet<>()));
                writes.addAll(collectMutations(
                        tx,
                        sides.rhsNodeId(),
                        nodeCache,
                        astChildrenCache,
                        assignmentSidesCache,
                        new HashSet<>()
                ));
            }

            String targetVariable = sides.lhsNodeId() == null ? null : extractSimpleVariable(tx, sides.lhsNodeId(), nodeCache);
            if (targetVariable != null) {
                writes.add(targetVariable);
            } else if (sides.lhsNodeId() != null) {
                reads.addAll(collectDependencies(tx, sides.lhsNodeId(), nodeCache, astChildrenCache, new HashSet<>()));
                writes.addAll(collectMutations(
                        tx,
                        sides.lhsNodeId(),
                        nodeCache,
                        astChildrenCache,
                        assignmentSidesCache,
                        new HashSet<>()
                ));
            }

            return new StatementEffects(reads, writes);
        }

        if (labels.contains("UnaryOperator")) {
            Set<String> reads = collectDependencies(tx, statementNodeId, nodeCache, astChildrenCache, new HashSet<>());
            Set<String> writes = collectMutations(
                    tx,
                    statementNodeId,
                    nodeCache,
                    astChildrenCache,
                    assignmentSidesCache,
                    new HashSet<>()
            );
            return new StatementEffects(reads, writes);
        }

        if (labels.contains("IfStatement")) {
            return collectIfEffects(tx, statementNodeId, nodeCache, astChildrenCache, assignmentSidesCache);
        }

        Set<String> reads = collectDependencies(tx, statementNodeId, nodeCache, astChildrenCache, new HashSet<>());
        Set<String> writes = collectMutations(
                tx,
                statementNodeId,
                nodeCache,
                astChildrenCache,
                assignmentSidesCache,
                new HashSet<>()
        );
        return new StatementEffects(reads, writes);
    }

    private String buildExpressionKey(
            TransactionContext tx,
            String nodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Set<String> visiting
    ) {
        if (nodeId == null || !visiting.add(nodeId)) {
            return null;
        }

        try {
            NodeInfo nodeInfo = loadNodeInfo(tx, nodeId, nodeCache);
            if (nodeInfo == null) {
                return null;
            }

            if (nodeInfo.labels().contains("Literal") || nodeInfo.labels().contains("Constant")) {
                return firstNonBlank(nodeInfo.value(), nodeInfo.code(), nodeInfo.name());
            }
            if (nodeInfo.labels().contains("Reference")) {
                return firstNonBlank(nodeInfo.name(), nodeInfo.code());
            }
            if (nodeInfo.labels().contains("UnaryOperator")) {
                List<String> children = loadAstChildren(tx, nodeId, astChildrenCache);
                if (children.size() != 1) {
                    return null;
                }
                String childKey = buildExpressionKey(tx, children.get(0), nodeCache, astChildrenCache, visiting);
                if (childKey == null) {
                    return null;
                }
                return normalizedOperator(nodeInfo.operatorCode()) + "(" + childKey + ")";
            }
            if (nodeInfo.labels().contains("BinaryOperator")
                    || nodeInfo.labels().contains("ArraySubscriptionExpression")
                    || nodeInfo.labels().contains("ArrayRef")
                    || nodeInfo.labels().contains("SubscriptExpression")) {
                List<String> children = loadAstChildren(tx, nodeId, astChildrenCache);
                if (children.size() < 2) {
                    return null;
                }
                String leftKey = buildExpressionKey(tx, children.get(0), nodeCache, astChildrenCache, visiting);
                String rightKey = buildExpressionKey(tx, children.get(1), nodeCache, astChildrenCache, visiting);
                if (leftKey == null || rightKey == null) {
                    return null;
                }

                String operator = nodeInfo.labels().contains("SubscriptExpression")
                        || nodeInfo.labels().contains("ArraySubscriptionExpression")
                        || nodeInfo.labels().contains("ArrayRef")
                        ? "[]"
                        : normalizedOperator(nodeInfo.operatorCode());
                return "(" + leftKey + operator + rightKey + ")";
            }
            if (nodeInfo.labels().contains("ConditionalExpression") || nodeInfo.labels().contains("TernaryOp")) {
                List<String> children = loadAstChildren(tx, nodeId, astChildrenCache);
                if (children.size() < 3) {
                    return null;
                }
                String condKey = buildExpressionKey(tx, children.get(0), nodeCache, astChildrenCache, visiting);
                String thenKey = buildExpressionKey(tx, children.get(1), nodeCache, astChildrenCache, visiting);
                String elseKey = buildExpressionKey(tx, children.get(2), nodeCache, astChildrenCache, visiting);
                if (condKey == null || thenKey == null || elseKey == null) {
                    return null;
                }
                return "(" + condKey + "?" + thenKey + ":" + elseKey + ")";
            }

            return null;
        } finally {
            visiting.remove(nodeId);
        }
    }

    private Set<String> collectDependencies(
            TransactionContext tx,
            String nodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Set<String> visiting
    ) {
        Set<String> dependencies = new HashSet<>();
        if (nodeId == null || !visiting.add(nodeId)) {
            return dependencies;
        }

        try {
            NodeInfo nodeInfo = loadNodeInfo(tx, nodeId, nodeCache);
            if (nodeInfo == null) {
                return dependencies;
            }
            if (nodeInfo.labels().contains("Reference")) {
                String variableName = firstNonBlank(nodeInfo.name(), nodeInfo.code());
                if (variableName != null && !variableName.isBlank()) {
                    dependencies.add(variableName);
                }
                return dependencies;
            }

            for (String childNodeId : loadAstChildren(tx, nodeId, astChildrenCache)) {
                dependencies.addAll(collectDependencies(tx, childNodeId, nodeCache, astChildrenCache, visiting));
            }
            return dependencies;
        } finally {
            visiting.remove(nodeId);
        }
    }

    private Set<String> collectMutations(
            TransactionContext tx,
            String nodeId,
            Map<String, NodeInfo> nodeCache,
            Map<String, List<String>> astChildrenCache,
            Map<String, AssignmentSides> assignmentSidesCache,
            Set<String> visiting
    ) {
        Set<String> mutations = new HashSet<>();
        if (nodeId == null || !visiting.add(nodeId)) {
            return mutations;
        }

        try {
            NodeInfo nodeInfo = loadNodeInfo(tx, nodeId, nodeCache);
            if (nodeInfo == null) {
                return mutations;
            }

            if (nodeInfo.labels().contains("UnaryOperator")) {
                String operator = normalizedOperator(nodeInfo.operatorCode());
                if ("++".equals(operator) || "--".equals(operator)) {
                    List<String> children = loadAstChildren(tx, nodeId, astChildrenCache);
                    if (!children.isEmpty()) {
                        String variable = extractSimpleVariable(tx, children.get(0), nodeCache);
                        if (variable != null) {
                            mutations.add(variable);
                        }
                    }
                }
            }

            if (nodeInfo.labels().contains("AssignExpression")) {
                AssignmentSides sides = loadAssignmentSides(tx, nodeId, assignmentSidesCache);
                if (sides != null && sides.lhsNodeId() != null) {
                    String variable = extractSimpleVariable(tx, sides.lhsNodeId(), nodeCache);
                    if (variable != null) {
                        mutations.add(variable);
                    }
                }
            }

            for (String childNodeId : loadAstChildren(tx, nodeId, astChildrenCache)) {
                mutations.addAll(collectMutations(
                        tx,
                        childNodeId,
                        nodeCache,
                        astChildrenCache,
                        assignmentSidesCache,
                        visiting
                ));
            }
            return mutations;
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
        AssignmentSides assignmentSides = new AssignmentSides(
                getNullableString(record, "lhsNodeId"),
                getNullableString(record, "rhsNodeId")
        );
        assignmentSidesCache.put(assignmentNodeId, assignmentSides);
        return assignmentSides;
    }

    private void incrementVersion(Map<String, Integer> variableVersions, String variable) {
        variableVersions.put(variable, variableVersions.getOrDefault(variable, 0) + 1);
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

    private record AssignmentCandidate(
            String sourceNodeId,
            String targetVariable,
            String expressionKey,
            Set<String> dependencies,
            Set<String> sideEffectWrites
    ) {
    }

    private record TrackedWrite(
            String sourceNodeId,
            String targetVariable,
            String expressionKey,
            Map<String, Integer> variableVersions
    ) {
    }

    private record StatementEffects(
            Set<String> readVariables,
            Set<String> writtenVariables
    ) {
        private static StatementEffects empty() {
            return new StatementEffects(Set.of(), Set.of());
        }
    }
}

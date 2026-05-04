package cpg;

import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.List;

public final class AlgoGraphBuilder {
    private static final String CLEAR_ALGO_LAYER_QUERY = """
            MATCH (algo:AlgoNode)
            DETACH DELETE algo
            """;
    private static final String FUNCTION_QUERY = """
            MATCH (f:FunctionDeclaration)-[:BODY]->(body:Block)
            RETURN
              elementId(f) AS functionNodeId,
              coalesce(f.name, f.code, "<unnamed-function>") AS functionName,
              elementId(body) AS bodyNodeId
            ORDER BY functionName, functionNodeId
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
    private static final String LOOP_BODY_QUERY = """
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
    private static final String SWITCH_DETAILS_QUERY = """
            MATCH (statement)
            WHERE elementId(statement) = $statementNodeId
            OPTIONAL MATCH (statement)-[:STATEMENT]->(body)
            RETURN
              elementId(body) AS bodyNodeId,
              labels(body) AS bodyLabels
            """;
    private static final String LIVE_DECLARATIONS_QUERY = """
            MATCH (statement)-[declRel:DECLARATIONS]->(declaration:ValueDeclaration)
            WHERE elementId(statement) = $statementNodeId
              AND NOT declaration:DEAD_CODE
            RETURN elementId(declaration) AS declarationNodeId
            ORDER BY declRel.index, declarationNodeId
            """;
    private static final String CREATE_CODE_ACTION_QUERY = """
            CREATE (algo:AlgoNode:AlgoCodeAction {
              sourceNodeId: $sourceNodeId,
              name: 'AlgoAction'
            })
            RETURN elementId(algo) AS algoNodeId
            """;
    private static final String CREATE_SEQUENCE_QUERY = """
            CREATE (algo:AlgoNode:AlgoSequence {name: 'AlgoSequence'})
            RETURN elementId(algo) AS algoNodeId
            """;
    private static final String CREATE_LOOP_QUERY = """
            CREATE (algo:AlgoNode:AlgoLoop {
              sourceNodeId: $sourceNodeId,
              name: 'AlgoLoop'
            })
            RETURN elementId(algo) AS algoNodeId
            """;
    private static final String CREATE_BRANCH_QUERY = """
            CREATE (algo:AlgoNode:AlgoBranch {
              sourceNodeId: $sourceNodeId,
              name: 'AlgoBranch'
            })
            RETURN elementId(algo) AS algoNodeId
            """;
    private static final String CREATE_ALGO_REFERENCE_QUERY = """
            MATCH (algo), (source)
            WHERE elementId(algo) = $algoNodeId AND elementId(source) = $sourceNodeId
            CREATE (algo)-[:ALGO_REFERENCE]->(source)
            """;
    private static final String CREATE_FUNCTION_REFERENCE_QUERY = """
            MATCH (from), (to)
            WHERE elementId(from) = $fromNodeId AND elementId(to) = $toNodeId
            CREATE (from)-[:FUNC_REFERENCE]->(to)
            """;
    private static final String CREATE_ALGO_BODY_QUERY = """
            MATCH (from), (to)
            WHERE elementId(from) = $fromNodeId AND elementId(to) = $toNodeId
            CREATE (from)-[:ALGO_BODY]->(to)
            """;
    private static final String CREATE_ALGO_ACTION_QUERY = """
            MATCH (sequence), (child)
            WHERE elementId(sequence) = $sequenceNodeId AND elementId(child) = $childNodeId
            CREATE (sequence)-[:ALGO_ACTIONS {action_index: $actionIndex}]->(child)
            """;
    private static final String CREATE_ALGO_THEN_QUERY = """
            MATCH (branch), (child)
            WHERE elementId(branch) = $branchNodeId AND elementId(child) = $childNodeId
            CREATE (branch)-[:ALGO_THEN]->(child)
            """;
    private static final String CREATE_ALGO_ELSE_QUERY = """
            MATCH (branch), (child)
            WHERE elementId(branch) = $branchNodeId AND elementId(child) = $childNodeId
            CREATE (branch)-[:ALGO_ELSE]->(child)
            """;
    private static final String BUILD_SUMMARY_QUERY = """
            MATCH (algo:AlgoNode)
            RETURN
              count(algo) AS algoNodes,
              count(CASE WHEN algo:AlgoCodeAction THEN 1 END) AS codeActions,
              count(CASE WHEN algo:AlgoSequence THEN 1 END) AS sequences,
              count(CASE WHEN algo:AlgoLoop THEN 1 END) AS loops,
              count(CASE WHEN algo:AlgoBranch THEN 1 END) AS branches
            """;

    public BuildSummary rebuild(Session session) {
        return session.executeWrite(this::rebuild);
    }

    public BuildSummary rebuild(TransactionContext tx) {
        tx.run(CLEAR_ALGO_LAYER_QUERY).consume();

        List<Record> functionRecords = tx.run(FUNCTION_QUERY).list();
        List<FunctionProjection> functions = new ArrayList<>();

        for (Record functionRecord : functionRecords) {
            String functionNodeId = functionRecord.get("functionNodeId").asString();
            String functionName = functionRecord.get("functionName").asString();
            String bodyNodeId = functionRecord.get("bodyNodeId").asString();

            String rootAlgoNodeId = buildBlockNode(tx, bodyNodeId);
            if (rootAlgoNodeId != null) {
                createFunctionReference(tx, functionNodeId, rootAlgoNodeId);
            }

            functions.add(new FunctionProjection(functionName, functionNodeId, rootAlgoNodeId));
        }

        Record summaryRecord = tx.run(BUILD_SUMMARY_QUERY).single();
        return new BuildSummary(
                functions,
                summaryRecord.get("algoNodes").asInt(),
                summaryRecord.get("codeActions").asInt(),
                summaryRecord.get("sequences").asInt(),
                summaryRecord.get("loops").asInt(),
                summaryRecord.get("branches").asInt()
        );
    }

    private String buildBlockNode(TransactionContext tx, String blockNodeId) {
        List<String> childAlgoNodeIds = buildBlockChildren(tx, blockNodeId);
        return wrapSequenceIfNeeded(tx, childAlgoNodeIds);
    }

    private List<String> buildBlockChildren(TransactionContext tx, String blockNodeId) {
        List<Record> statementRecords = tx.run(
                BLOCK_STATEMENTS_QUERY,
                Values.parameters("blockNodeId", blockNodeId)
        ).list();

        List<String> childAlgoNodeIds = new ArrayList<>();
        for (Record statementRecord : statementRecords) {
            childAlgoNodeIds.addAll(buildStatementNodes(
                    tx,
                    statementRecord.get("statementNodeId").asString(),
                    statementRecord.get("labels").asList(Value::asString)
            ));
        }

        return childAlgoNodeIds;
    }

    private List<String> buildStatementNodes(TransactionContext tx, String statementNodeId, List<String> labels) {
        if (labels.contains("DEAD_CODE")) {
            return List.of();
        }
        if (labels.contains("Block")) {
            String blockAlgoNodeId = buildBlockNode(tx, statementNodeId);
            return blockAlgoNodeId == null ? List.of() : List.of(blockAlgoNodeId);
        }
        if (labels.contains("DeclarationStatement")) {
            return buildDeclarationActionNodes(tx, statementNodeId);
        }
        if (labels.contains("IfStatement")) {
            return List.of(buildBranchNode(tx, statementNodeId));
        }
        if (labels.contains("SwitchStatement")) {
            return List.of(buildSwitchNode(tx, statementNodeId));
        }
        if (labels.contains("ForStatement")) {
            return buildForStatementNodes(tx, statementNodeId);
        }
        if (labels.contains("WhileStatement") || labels.contains("DoStatement")) {
            return List.of(buildLoopNode(tx, statementNodeId));
        }
        if (labels.contains("Statement")) {
            return List.of(buildCodeActionNode(tx, statementNodeId));
        }

        return List.of();
    }

    private List<String> buildDeclarationActionNodes(TransactionContext tx, String statementNodeId) {
        List<Record> declarationRecords = tx.run(
                LIVE_DECLARATIONS_QUERY,
                Values.parameters("statementNodeId", statementNodeId)
        ).list();

        List<String> actionNodeIds = new ArrayList<>();
        for (Record declarationRecord : declarationRecords) {
            actionNodeIds.add(buildCodeActionNode(tx, declarationRecord.get("declarationNodeId").asString()));
        }

        return actionNodeIds;
    }

    private List<String> buildForStatementNodes(TransactionContext tx, String statementNodeId) {
        Record forDetailsRecord = tx.run(
                FOR_DETAILS_QUERY,
                Values.parameters("statementNodeId", statementNodeId)
        ).single();

        List<String> algoNodeIds = new ArrayList<>();
        algoNodeIds.addAll(buildOptionalNodes(
                tx,
                forDetailsRecord.get("initializerNodeId"),
                forDetailsRecord.get("initializerLabels")
        ));

        String loopNodeId = tx.run(
                CREATE_LOOP_QUERY,
                Values.parameters("sourceNodeId", statementNodeId)
        ).single().get("algoNodeId").asString();
        createAlgoReference(tx, loopNodeId, statementNodeId);

        List<String> bodyNodeIds = new ArrayList<>();
        if (toStringList(forDetailsRecord.get("bodyLabels")).contains("Block")) {
            bodyNodeIds.addAll(buildBlockChildren(tx, forDetailsRecord.get("bodyNodeId").asString()));
        } else {
            bodyNodeIds.addAll(buildOptionalNodes(
                    tx,
                    forDetailsRecord.get("bodyNodeId"),
                    forDetailsRecord.get("bodyLabels")
            ));
        }
        bodyNodeIds.addAll(buildOptionalNodes(
                tx,
                forDetailsRecord.get("iterationNodeId"),
                forDetailsRecord.get("iterationLabels")
        ));

        String loopBodyNodeId = wrapSequenceIfNeeded(tx, bodyNodeIds);
        if (loopBodyNodeId != null) {
            createAlgoBody(tx, loopNodeId, loopBodyNodeId);
        }

        algoNodeIds.add(loopNodeId);
        return algoNodeIds;
    }

    private String buildCodeActionNode(TransactionContext tx, String statementNodeId) {
        String algoNodeId = tx.run(
                CREATE_CODE_ACTION_QUERY,
                Values.parameters("sourceNodeId", statementNodeId)
        ).single().get("algoNodeId").asString();

        createAlgoReference(tx, algoNodeId, statementNodeId);
        return algoNodeId;
    }

    private String buildLoopNode(TransactionContext tx, String statementNodeId) {
        String algoNodeId = tx.run(
                CREATE_LOOP_QUERY,
                Values.parameters("sourceNodeId", statementNodeId)
        ).single().get("algoNodeId").asString();

        createAlgoReference(tx, algoNodeId, statementNodeId);

        Record bodyRecord = tx.run(
                LOOP_BODY_QUERY,
                Values.parameters("statementNodeId", statementNodeId)
        ).single();

        String bodyAlgoNodeId = buildOptionalNode(
                tx,
                bodyRecord.get("bodyNodeId"),
                bodyRecord.get("bodyLabels")
        );
        if (bodyAlgoNodeId != null) {
            createAlgoBody(tx, algoNodeId, bodyAlgoNodeId);
        }

        return algoNodeId;
    }

    private String buildBranchNode(TransactionContext tx, String statementNodeId) {
        String algoNodeId = tx.run(
                CREATE_BRANCH_QUERY,
                Values.parameters("sourceNodeId", statementNodeId)
        ).single().get("algoNodeId").asString();

        createAlgoReference(tx, algoNodeId, statementNodeId);

        Record branchRecord = tx.run(
                IF_DETAILS_QUERY,
                Values.parameters("statementNodeId", statementNodeId)
        ).single();

        String thenAlgoNodeId = buildOptionalNode(
                tx,
                branchRecord.get("thenNodeId"),
                branchRecord.get("thenLabels")
        );
        if (thenAlgoNodeId != null) {
            createAlgoThen(tx, algoNodeId, thenAlgoNodeId);
        }

        String elseAlgoNodeId = buildOptionalNode(
                tx,
                branchRecord.get("elseNodeId"),
                branchRecord.get("elseLabels")
        );
        if (elseAlgoNodeId != null) {
            createAlgoElse(tx, algoNodeId, elseAlgoNodeId);
        }

        return algoNodeId;
    }

    private String buildSwitchNode(TransactionContext tx, String statementNodeId) {
        String algoNodeId = tx.run(
                CREATE_BRANCH_QUERY,
                Values.parameters("sourceNodeId", statementNodeId)
        ).single().get("algoNodeId").asString();

        createAlgoReference(tx, algoNodeId, statementNodeId);

        Record switchRecord = tx.run(
                SWITCH_DETAILS_QUERY,
                Values.parameters("statementNodeId", statementNodeId)
        ).single();

        String bodyNodeId = nullableString(switchRecord, "bodyNodeId");
        List<String> bodyLabels = toStringList(switchRecord.get("bodyLabels"));
        if (bodyNodeId == null || !bodyLabels.contains("Block")) {
            return algoNodeId;
        }

        List<Record> statementRecords = tx.run(
                BLOCK_STATEMENTS_QUERY,
                Values.parameters("blockNodeId", bodyNodeId)
        ).list();

        String currentMarkerNodeId = null;
        boolean currentDefault = false;
        List<String> currentChildAlgoNodeIds = new ArrayList<>();

        for (Record statementRecord : statementRecords) {
            String childStatementNodeId = statementRecord.get("statementNodeId").asString();
            List<String> childLabels = statementRecord.get("labels").asList(Value::asString);

            if (isSwitchMarker(childLabels)) {
                attachSwitchArm(tx, algoNodeId, currentMarkerNodeId, currentDefault, currentChildAlgoNodeIds);
                currentMarkerNodeId = childStatementNodeId;
                currentDefault = childLabels.contains("DefaultStatement");
                currentChildAlgoNodeIds = new ArrayList<>();
                continue;
            }

            currentChildAlgoNodeIds.addAll(buildStatementNodes(tx, childStatementNodeId, childLabels));
        }

        attachSwitchArm(tx, algoNodeId, currentMarkerNodeId, currentDefault, currentChildAlgoNodeIds);
        return algoNodeId;
    }

    private void attachSwitchArm(
            TransactionContext tx,
            String branchAlgoNodeId,
            String markerNodeId,
            boolean isDefault,
            List<String> armChildAlgoNodeIds
    ) {
        if (markerNodeId == null) {
            return;
        }

        List<String> childAlgoNodeIds = new ArrayList<>();
        childAlgoNodeIds.add(buildCodeActionNode(tx, markerNodeId));
        childAlgoNodeIds.addAll(armChildAlgoNodeIds);

        String armAlgoNodeId = wrapSequenceIfNeeded(tx, childAlgoNodeIds);
        if (armAlgoNodeId == null) {
            return;
        }

        if (isDefault) {
            createAlgoElse(tx, branchAlgoNodeId, armAlgoNodeId);
        } else {
            createAlgoThen(tx, branchAlgoNodeId, armAlgoNodeId);
        }
    }

    private String buildOptionalNode(TransactionContext tx, Value nodeIdValue, Value labelsValue) {
        List<String> childNodeIds = buildOptionalNodes(tx, nodeIdValue, labelsValue);
        return wrapSequenceIfNeeded(tx, childNodeIds);
    }

    private List<String> buildOptionalNodes(TransactionContext tx, Value nodeIdValue, Value labelsValue) {
        if (nodeIdValue == null || nodeIdValue.isNull()) {
            return List.of();
        }

        return buildStatementNodes(tx, nodeIdValue.asString(), toStringList(labelsValue));
    }

    private String wrapSequenceIfNeeded(TransactionContext tx, List<String> childAlgoNodeIds) {
        if (childAlgoNodeIds.isEmpty()) {
            return null;
        }
        if (childAlgoNodeIds.size() == 1) {
            return childAlgoNodeIds.get(0);
        }

        String sequenceNodeId = tx.run(CREATE_SEQUENCE_QUERY).single().get("algoNodeId").asString();
        for (int index = 0; index < childAlgoNodeIds.size(); index++) {
            tx.run(
                    CREATE_ALGO_ACTION_QUERY,
                    Values.parameters(
                            "sequenceNodeId", sequenceNodeId,
                            "childNodeId", childAlgoNodeIds.get(index),
                            "actionIndex", index
                    )
            ).consume();
        }

        return sequenceNodeId;
    }

    private void createAlgoReference(TransactionContext tx, String algoNodeId, String sourceNodeId) {
        tx.run(
                CREATE_ALGO_REFERENCE_QUERY,
                Values.parameters("algoNodeId", algoNodeId, "sourceNodeId", sourceNodeId)
        ).consume();
    }

    private void createFunctionReference(TransactionContext tx, String fromNodeId, String toNodeId) {
        tx.run(
                CREATE_FUNCTION_REFERENCE_QUERY,
                Values.parameters("fromNodeId", fromNodeId, "toNodeId", toNodeId)
        ).consume();
    }

    private void createAlgoBody(TransactionContext tx, String fromNodeId, String toNodeId) {
        tx.run(
                CREATE_ALGO_BODY_QUERY,
                Values.parameters("fromNodeId", fromNodeId, "toNodeId", toNodeId)
        ).consume();
    }

    private void createAlgoThen(TransactionContext tx, String branchNodeId, String childNodeId) {
        tx.run(
                CREATE_ALGO_THEN_QUERY,
                Values.parameters("branchNodeId", branchNodeId, "childNodeId", childNodeId)
        ).consume();
    }

    private void createAlgoElse(TransactionContext tx, String branchNodeId, String childNodeId) {
        tx.run(
                CREATE_ALGO_ELSE_QUERY,
                Values.parameters("branchNodeId", branchNodeId, "childNodeId", childNodeId)
        ).consume();
    }

    private List<String> toStringList(Value value) {
        return value == null || value.isNull() ? List.of() : value.asList(Value::asString);
    }

    private String nullableString(Record record, String key) {
        Value value = record.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private boolean isSwitchMarker(List<String> labels) {
        return labels.contains("CaseStatement") || labels.contains("DefaultStatement");
    }

    public record FunctionProjection(
            String functionName,
            String functionNodeId,
            String rootAlgoNodeId
    ) {
    }

    public record BuildSummary(
            List<FunctionProjection> functions,
            int algoNodes,
            int codeActions,
            int sequences,
            int loops,
            int branches
    ) {
    }
}

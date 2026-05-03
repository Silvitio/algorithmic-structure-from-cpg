package cpg;

import analysismodel.Entity;
import analysismodel.FunctionModel;
import analysismodel.IfStructure;
import analysismodel.LoopStructure;
import analysismodel.NodeKind;
import analysismodel.ProgramNode;
import analysismodel.Region;
import analysismodel.SwitchStructure;
import analysismodel.BranchArm;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FunctionModelBuilder {
    private static final String FUNCTION_QUERY = """
            MATCH (f:FunctionDeclaration)-[:BODY]->(body:Block)
            RETURN
              elementId(f) AS functionNodeId,
              coalesce(f.name, f.code, "<unnamed-function>") AS functionName,
              elementId(body) AS bodyNodeId
            ORDER BY functionName, functionNodeId
            """;
    private static final String MODEL_NODE_QUERY = """
            MATCH (body)-[:AST*0..]->(node)
            WHERE elementId(body) = $bodyNodeId
              AND (
                node:ValueDeclaration
                OR node:AssignExpression
                OR node:CallExpression
                OR node:BreakStatement
                OR node:ReturnStatement
                OR node:UnaryOperator
                OR node:UnaryOp
                OR node:IfStatement
                OR node:WhileStatement
                OR node:ForStatement
                OR node:DoStatement
                OR node:SwitchStatement
              )
            RETURN DISTINCT
              elementId(node) AS nodeId,
              labels(node) AS labels,
              coalesce(node.code, node.name, "<no-code>") AS code,
              node.startLine AS startLine,
              node.operatorCode AS operatorCode
            ORDER BY startLine, nodeId
            """;
    private static final String DECLARATIONS_QUERY = """
            MATCH (statement)-[declRel:DECLARATIONS]->(declaration:ValueDeclaration)
            WHERE elementId(statement) = $statementNodeId
            OPTIONAL MATCH (declaration)-[:INITIALIZER]->(initializer)
            RETURN
              elementId(declaration) AS declarationNodeId,
              declaration.name AS declarationName,
              declaration.code AS declarationCode,
              declaration.startLine AS declarationStartLine,
              elementId(initializer) AS initializerNodeId,
              declRel.index AS declarationIndex
            ORDER BY declarationIndex, declarationNodeId
            """;
    private static final String VALUE_DECLARATION_QUERY = """
            MATCH (declaration:ValueDeclaration)
            WHERE elementId(declaration) = $declarationNodeId
            OPTIONAL MATCH (declaration)-[:INITIALIZER]->(initializer)
            RETURN
              declaration.name AS declarationName,
              elementId(initializer) AS initializerNodeId
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
    private static final String RETURN_VALUE_QUERY = """
            MATCH (ret)
            WHERE elementId(ret) = $returnNodeId
            OPTIONAL MATCH (ret)-[:RETURN_VALUE]->(expr)
            WITH ret, expr
            OPTIONAL MATCH (ret)-[:RETURN_VALUES]->(exprAlt)
            RETURN coalesce(elementId(expr), elementId(exprAlt)) AS returnValueNodeId
            """;
    private static final String CALL_INFO_QUERY = """
            MATCH (call)
            WHERE elementId(call) = $callNodeId
            OPTIONAL MATCH (call)-[:OPERATOR_BASE]->(callee)
            WITH call, callee
            OPTIONAL MATCH (call)-[argRel:ARGUMENTS]->(argument)
            WITH callee, argRel, argument
            ORDER BY argRel.index, elementId(argument)
            RETURN
              coalesce(callee.name, callee.code) AS callName,
              [nodeId IN collect(elementId(argument)) WHERE nodeId IS NOT NULL | nodeId] AS argumentNodeIds
            """;
    private static final String IF_DETAILS_QUERY = """
            MATCH (statement)
            WHERE elementId(statement) = $statementNodeId
            OPTIONAL MATCH (statement)-[:CONDITION]->(condition)
            OPTIONAL MATCH (statement)-[:THEN_STATEMENT]->(thenBranch)
            OPTIONAL MATCH (statement)-[:ELSE_STATEMENT]->(elseBranch)
            RETURN elementId(condition) AS conditionNodeId
                 , elementId(thenBranch) AS thenNodeId
                 , labels(thenBranch) AS thenLabels
                 , elementId(elseBranch) AS elseNodeId
                 , labels(elseBranch) AS elseLabels
            """;
    private static final String WHILE_DETAILS_QUERY = """
            MATCH (statement)
            WHERE elementId(statement) = $statementNodeId
            OPTIONAL MATCH (statement)-[:CONDITION]->(condition)
            OPTIONAL MATCH (statement)-[:STATEMENT]->(body)
            RETURN elementId(condition) AS conditionNodeId
                 , elementId(body) AS bodyNodeId
                 , labels(body) AS bodyLabels
            """;
    private static final String FOR_DETAILS_QUERY = """
            MATCH (statement)
            WHERE elementId(statement) = $statementNodeId
            OPTIONAL MATCH (statement)-[:INITIALIZER_STATEMENT]->(initializer)
            OPTIONAL MATCH (statement)-[:CONDITION]->(condition)
            OPTIONAL MATCH (statement)-[:STATEMENT]->(body)
            OPTIONAL MATCH (statement)-[:ITERATION_STATEMENT]->(iteration)
            RETURN elementId(condition) AS conditionNodeId
                 , elementId(initializer) AS initializerNodeId
                 , labels(initializer) AS initializerLabels
                 , elementId(body) AS bodyNodeId
                 , labels(body) AS bodyLabels
                 , elementId(iteration) AS iterationNodeId
                 , labels(iteration) AS iterationLabels
            """;
    private static final String SWITCH_DETAILS_QUERY = """
            MATCH (statement)
            WHERE elementId(statement) = $statementNodeId
            OPTIONAL MATCH (statement)-[:SELECTOR]->(selector)
            OPTIONAL MATCH (statement)-[:STATEMENT]->(body)
            RETURN elementId(selector) AS selectorNodeId
                 , elementId(body) AS bodyNodeId
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

    private final DefsUsesExtractor defsUsesExtractor = new DefsUsesExtractor();
    private final CfgBuilder cfgBuilder = new CfgBuilder();

    public List<FunctionModel> buildAll(Session session) {
        return session.executeRead(this::buildAll);
    }

    public List<FunctionModel> buildAll(TransactionContext tx) {
        List<FunctionModel> models = new ArrayList<>();
        List<Record> functions = tx.run(FUNCTION_QUERY).list();

        for (Record function : functions) {
            models.add(buildFunction(tx, function));
        }

        return models;
    }

    private FunctionModel buildFunction(TransactionContext tx, Record functionRecord) {
        String functionNodeId = functionRecord.get("functionNodeId").asString();
        String functionName = functionRecord.get("functionName").asString();
        String bodyNodeId = functionRecord.get("bodyNodeId").asString();

        DefsUsesExtractor.ExtractionState state = defsUsesExtractor.newState();
        List<ProgramNode> nodes = new ArrayList<>();
        Map<String, String> declarationStatementByValueDeclarationId = new LinkedHashMap<>();

        ProgramNode entry = new ProgramNode(
                functionNodeId + ":ENTRY",
                NodeKind.ENTRY,
                "<ENTRY " + functionName + ">",
                null,
                Set.of(),
                Set.of()
        );
        nodes.add(entry);

        List<Record> modelNodeRecords = tx.run(MODEL_NODE_QUERY, Values.parameters("bodyNodeId", bodyNodeId)).list();
        Map<String, List<String>> labelsByNodeId = new LinkedHashMap<>();
        for (Record nodeRecord : modelNodeRecords) {
            labelsByNodeId.put(nodeRecord.get("nodeId").asString(), nodeRecord.get("labels").asList(Value::asString));
            ProgramNode node = buildProgramNode(tx, nodeRecord, state, declarationStatementByValueDeclarationId);
            if (node != null) {
                nodes.add(node);
            }
        }

        ProgramNode exit = new ProgramNode(
                functionNodeId + ":EXIT",
                NodeKind.EXIT,
                "<EXIT " + functionName + ">",
                null,
                Set.of(),
                Set.of()
        );
        nodes.add(exit);

        Set<String> modelNodeIds = new LinkedHashSet<>();
        for (ProgramNode node : nodes) {
            if (node.kind() != NodeKind.ENTRY && node.kind() != NodeKind.EXIT) {
                modelNodeIds.add(node.cpgNodeId());
            }
        }

        Region bodyRegion = new Region(bodyNodeId, collectRegionNodeIdsFromBlock(tx, bodyNodeId, modelNodeIds));
        Map<String, IfStructure> ifStructures = buildIfStructures(tx, labelsByNodeId, modelNodeIds);
        Map<String, LoopStructure> loopStructures = buildLoopStructures(tx, labelsByNodeId, modelNodeIds);
        Map<String, SwitchStructure> switchStructures = buildSwitchStructures(tx, labelsByNodeId, modelNodeIds);

        FunctionModel model = new FunctionModel(
                functionNodeId,
                functionName,
                entry,
                exit,
                nodes,
                bodyRegion,
                ifStructures,
                loopStructures,
                switchStructures,
                declarationStatementByValueDeclarationId
        );
        cfgBuilder.build(model);
        return model;
    }

    private ProgramNode buildProgramNode(
            TransactionContext tx,
            Record nodeRecord,
            DefsUsesExtractor.ExtractionState state,
            Map<String, String> declarationStatementByValueDeclarationId
    ) {
        String nodeId = nodeRecord.get("nodeId").asString();
        List<String> labels = nodeRecord.get("labels").asList(Value::asString);
        String code = nodeRecord.get("code").asString();
        Integer startLine = nodeRecord.get("startLine").isNull() ? null : nodeRecord.get("startLine").asInt();
        String operatorCode = nodeRecord.get("operatorCode").isNull() ? null : nodeRecord.get("operatorCode").asString();

        if (labels.contains("ValueDeclaration")) {
            return buildDeclarationNode(tx, nodeId, code, startLine, state, declarationStatementByValueDeclarationId);
        }
        if (labels.contains("AssignExpression")) {
            return buildAssignmentNode(tx, nodeId, code, startLine, state);
        }
        if (labels.contains("BreakStatement")) {
            return buildBreakNode(nodeId, code, startLine);
        }
        if (labels.contains("ReturnStatement")) {
            return buildReturnNode(tx, nodeId, code, startLine, state);
        }
        if (labels.contains("CallExpression")) {
            return buildCallNode(tx, nodeId, code, startLine, state);
        }
        if ((labels.contains("UnaryOperator") || labels.contains("UnaryOp")) && isIncrementOrDecrement(operatorCode)) {
            return buildUpdateNode(tx, nodeId, code, startLine, state);
        }
        if (labels.contains("IfStatement")) {
            return buildConditionNode(tx, nodeId, code, startLine, state, NodeKind.BRANCH, IF_DETAILS_QUERY);
        }
        if (labels.contains("SwitchStatement")) {
            return buildConditionNode(tx, nodeId, code, startLine, state, NodeKind.BRANCH, SWITCH_DETAILS_QUERY);
        }
        if (labels.contains("WhileStatement")) {
            return buildConditionNode(tx, nodeId, code, startLine, state, NodeKind.LOOP, WHILE_DETAILS_QUERY);
        }
        if (labels.contains("ForStatement")) {
            return buildConditionNode(tx, nodeId, code, startLine, state, NodeKind.LOOP, FOR_DETAILS_QUERY);
        }
        if (labels.contains("DoStatement")) {
            return buildConditionNode(tx, nodeId, code, startLine, state, NodeKind.LOOP, WHILE_DETAILS_QUERY);
        }

        return null;
    }

    private ProgramNode buildDeclarationNode(
            TransactionContext tx,
            String nodeId,
            String code,
            Integer startLine,
            DefsUsesExtractor.ExtractionState state,
            Map<String, String> declarationStatementByValueDeclarationId
    ) {
        Record record = tx.run(VALUE_DECLARATION_QUERY, Values.parameters("declarationNodeId", nodeId)).single();
        String declarationName = getNullableString(record, "declarationName");
        String initializerNodeId = getNullableString(record, "initializerNodeId");

        Set<Entity> defs = defsUsesExtractor.declarationEntities(tx, nodeId, declarationName, state);
        Set<Entity> uses = defsUsesExtractor.collectReadEntities(tx, initializerNodeId, state);
        declarationStatementByValueDeclarationId.put(nodeId, loadDeclarationStatementNodeId(tx, nodeId));

        return new ProgramNode(nodeId, NodeKind.DECLARATION, code, startLine, defs, uses);
    }

    private ProgramNode buildAssignmentNode(
            TransactionContext tx,
            String nodeId,
            String code,
            Integer startLine,
            DefsUsesExtractor.ExtractionState state
    ) {
        Record record = tx.run(ASSIGNMENT_SIDES_QUERY, Values.parameters("assignmentNodeId", nodeId)).single();
        String lhsNodeId = getNullableString(record, "lhsNodeId");
        String rhsNodeId = getNullableString(record, "rhsNodeId");

        Set<Entity> defs = defsUsesExtractor.collectWrittenEntities(tx, lhsNodeId, state);
        Set<Entity> uses = new LinkedHashSet<>(defsUsesExtractor.collectReadEntities(tx, rhsNodeId, state));
        uses.addAll(defsUsesExtractor.collectWriteTargetUses(tx, lhsNodeId, state));

        return new ProgramNode(nodeId, NodeKind.ACTION, code, startLine, defs, uses);
    }

    private ProgramNode buildBreakNode(
            String nodeId,
            String code,
            Integer startLine
    ) {
        return new ProgramNode(nodeId, NodeKind.ACTION, code, startLine, Set.of(), Set.of());
    }

    private ProgramNode buildReturnNode(
            TransactionContext tx,
            String nodeId,
            String code,
            Integer startLine,
            DefsUsesExtractor.ExtractionState state
    ) {
        Record record = tx.run(RETURN_VALUE_QUERY, Values.parameters("returnNodeId", nodeId)).single();
        String returnValueNodeId = getNullableString(record, "returnValueNodeId");

        Set<Entity> defs = Set.of(Entity.returnSlot());
        Set<Entity> uses = defsUsesExtractor.collectReadEntities(tx, returnValueNodeId, state);
        return new ProgramNode(nodeId, NodeKind.TRANSFER, code, startLine, defs, uses);
    }

    private ProgramNode buildCallNode(
            TransactionContext tx,
            String nodeId,
            String code,
            Integer startLine,
            DefsUsesExtractor.ExtractionState state
    ) {
        Record record = tx.run(CALL_INFO_QUERY, Values.parameters("callNodeId", nodeId)).single();
        String callName = getNullableString(record, "callName");
        List<String> argumentNodeIds = record.get("argumentNodeIds").asList(Value::asString);

        Set<Entity> defs = new LinkedHashSet<>();
        Set<Entity> uses = new LinkedHashSet<>();

        if ("printf".equals(callName)) {
            for (String argumentNodeId : argumentNodeIds) {
                uses.addAll(defsUsesExtractor.collectReadEntities(tx, argumentNodeId, state));
            }
            return new ProgramNode(nodeId, NodeKind.ACTION, code, startLine, defs, uses);
        }

        if ("scanf".equals(callName)) {
            for (String argumentNodeId : argumentNodeIds) {
                defs.addAll(defsUsesExtractor.collectWrittenEntities(tx, argumentNodeId, state));
                uses.addAll(defsUsesExtractor.collectScanfAddressingEntities(tx, argumentNodeId, state));
            }
            return new ProgramNode(nodeId, NodeKind.ACTION, code, startLine, defs, uses);
        }

        for (String argumentNodeId : argumentNodeIds) {
            uses.addAll(defsUsesExtractor.collectReadEntities(tx, argumentNodeId, state));
        }
        return new ProgramNode(nodeId, NodeKind.ACTION, code, startLine, defs, uses);
    }

    private ProgramNode buildUpdateNode(
            TransactionContext tx,
            String nodeId,
            String code,
            Integer startLine,
            DefsUsesExtractor.ExtractionState state
    ) {
        Set<Entity> defs = defsUsesExtractor.collectWrittenEntities(tx, nodeId, state);
        Set<Entity> uses = defsUsesExtractor.collectReadEntities(tx, nodeId, state);
        return new ProgramNode(nodeId, NodeKind.ACTION, code, startLine, defs, uses);
    }

    private ProgramNode buildConditionNode(
            TransactionContext tx,
            String nodeId,
            String code,
            Integer startLine,
            DefsUsesExtractor.ExtractionState state,
            NodeKind kind,
            String query
    ) {
        Record record = tx.run(query, Values.parameters("statementNodeId", nodeId)).single();
        String conditionNodeId = getNullableString(record, "conditionNodeId");
        if (conditionNodeId == null) {
            conditionNodeId = getNullableString(record, "selectorNodeId");
        }

        Set<Entity> uses = defsUsesExtractor.collectReadEntities(tx, conditionNodeId, state);
        return new ProgramNode(nodeId, kind, code, startLine, Set.of(), uses);
    }

    private boolean isIncrementOrDecrement(String operatorCode) {
        if (operatorCode == null) {
            return false;
        }
        return "++".equals(operatorCode)
                || "--".equals(operatorCode)
                || "post++".equals(operatorCode)
                || "post--".equals(operatorCode)
                || "pre++".equals(operatorCode)
                || "pre--".equals(operatorCode);
    }

    private String getNullableString(Record record, String key) {
        Value value = record.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private Map<String, IfStructure> buildIfStructures(
            TransactionContext tx,
            Map<String, List<String>> labelsByNodeId,
            Set<String> modelNodeIds
    ) {
        Map<String, IfStructure> structures = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : labelsByNodeId.entrySet()) {
            if (!entry.getValue().contains("IfStatement")) {
                continue;
            }

            Record record = tx.run(IF_DETAILS_QUERY, Values.parameters("statementNodeId", entry.getKey())).single();
            String conditionNodeId = getNullableString(record, "conditionNodeId");
            String thenNodeId = getNullableString(record, "thenNodeId");
            List<String> thenLabels = getLabels(record, "thenLabels");
            String elseNodeId = getNullableString(record, "elseNodeId");
            List<String> elseLabels = getLabels(record, "elseLabels");

            Region thenRegion = collectRegionFromRoot(tx, thenNodeId, thenLabels, modelNodeIds);
            Region elseRegion = collectRegionFromRoot(tx, elseNodeId, elseLabels, modelNodeIds);

            structures.put(entry.getKey(), new IfStructure(conditionNodeId, thenRegion, elseRegion));
        }

        return structures;
    }

    private Map<String, LoopStructure> buildLoopStructures(
            TransactionContext tx,
            Map<String, List<String>> labelsByNodeId,
            Set<String> modelNodeIds
    ) {
        Map<String, LoopStructure> structures = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : labelsByNodeId.entrySet()) {
            String nodeId = entry.getKey();
            List<String> labels = entry.getValue();

            if (labels.contains("WhileStatement") || labels.contains("DoStatement")) {
                Record record = tx.run(WHILE_DETAILS_QUERY, Values.parameters("statementNodeId", nodeId)).single();
                String conditionNodeId = getNullableString(record, "conditionNodeId");
                String bodyNodeId = getNullableString(record, "bodyNodeId");
                List<String> bodyLabels = getLabels(record, "bodyLabels");
                Region bodyRegion = collectRegionFromRoot(tx, bodyNodeId, bodyLabels, modelNodeIds);
                boolean conditionAfterBody = labels.contains("DoStatement");

                structures.put(
                        nodeId,
                        new LoopStructure(conditionNodeId, bodyRegion, Region.empty(), Region.empty(), conditionAfterBody)
                );
                continue;
            }

            if (labels.contains("ForStatement")) {
                Record record = tx.run(FOR_DETAILS_QUERY, Values.parameters("statementNodeId", nodeId)).single();
                String conditionNodeId = getNullableString(record, "conditionNodeId");
                String initializerNodeId = getNullableString(record, "initializerNodeId");
                List<String> initializerLabels = getLabels(record, "initializerLabels");
                String bodyNodeId = getNullableString(record, "bodyNodeId");
                List<String> bodyLabels = getLabels(record, "bodyLabels");
                String iterationNodeId = getNullableString(record, "iterationNodeId");
                List<String> iterationLabels = getLabels(record, "iterationLabels");

                Region initializerRegion = collectRegionFromRoot(tx, initializerNodeId, initializerLabels, modelNodeIds);
                Region bodyRegion = collectRegionFromRoot(tx, bodyNodeId, bodyLabels, modelNodeIds);
                Region iterationRegion = collectRegionFromRoot(tx, iterationNodeId, iterationLabels, modelNodeIds);

                structures.put(
                        nodeId,
                        new LoopStructure(conditionNodeId, bodyRegion, initializerRegion, iterationRegion, false)
                );
            }
        }

        return structures;
    }

    private Map<String, SwitchStructure> buildSwitchStructures(
            TransactionContext tx,
            Map<String, List<String>> labelsByNodeId,
            Set<String> modelNodeIds
    ) {
        Map<String, SwitchStructure> structures = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : labelsByNodeId.entrySet()) {
            if (!entry.getValue().contains("SwitchStatement")) {
                continue;
            }

            Record record = tx.run(SWITCH_DETAILS_QUERY, Values.parameters("statementNodeId", entry.getKey())).single();
            String selectorNodeId = getNullableString(record, "selectorNodeId");
            String bodyNodeId = getNullableString(record, "bodyNodeId");

            Region bodyRegion = new Region(
                    bodyNodeId,
                    bodyNodeId == null ? List.of() : collectRegionNodeIdsFromBlock(tx, bodyNodeId, modelNodeIds)
            );
            List<BranchArm> arms = collectSwitchArms(tx, bodyNodeId, modelNodeIds);
            structures.put(entry.getKey(), new SwitchStructure(selectorNodeId, bodyRegion, arms));
        }

        return structures;
    }

    private List<BranchArm> collectSwitchArms(
            TransactionContext tx,
            String bodyNodeId,
            Set<String> modelNodeIds
    ) {
        if (bodyNodeId == null) {
            return List.of();
        }

        List<Record> statements = tx.run(BLOCK_STATEMENTS_QUERY, Values.parameters("blockNodeId", bodyNodeId)).list();
        List<BranchArm> arms = new ArrayList<>();

        String currentMarkerNodeId = null;
        boolean currentDefault = false;
        List<String> currentBodyNodeIds = new ArrayList<>();

        for (Record statement : statements) {
            String statementNodeId = statement.get("statementNodeId").asString();
            List<String> labels = statement.get("labels").asList(Value::asString);

            if (isSwitchMarker(labels)) {
                if (currentMarkerNodeId != null) {
                    arms.add(new BranchArm(
                            currentMarkerNodeId,
                            new Region(detectArmBlockNodeId(tx, currentBodyNodeIds, bodyNodeId), currentBodyNodeIds),
                            currentDefault
                    ));
                }
                currentMarkerNodeId = statementNodeId;
                currentDefault = labels.contains("DefaultStatement");
                currentBodyNodeIds = new ArrayList<>();
                continue;
            }

            currentBodyNodeIds.addAll(collectRegionNodeIdsFromRoot(tx, statementNodeId, labels, modelNodeIds));
        }

        if (currentMarkerNodeId != null) {
            arms.add(new BranchArm(
                    currentMarkerNodeId,
                    new Region(detectArmBlockNodeId(tx, currentBodyNodeIds, bodyNodeId), currentBodyNodeIds),
                    currentDefault
            ));
        }

        return arms;
    }

    private Region collectRegionFromRoot(
            TransactionContext tx,
            String rootNodeId,
            List<String> rootLabels,
            Set<String> modelNodeIds
    ) {
        if (rootNodeId == null) {
            return Region.empty();
        }
        return new Region(
                rootLabels.contains("Block") ? rootNodeId : null,
                collectRegionNodeIdsFromRoot(tx, rootNodeId, rootLabels, modelNodeIds)
        );
    }

    private List<String> collectRegionNodeIdsFromRoot(
            TransactionContext tx,
            String rootNodeId,
            List<String> rootLabels,
            Set<String> modelNodeIds
    ) {
        if (rootNodeId == null) {
            return List.of();
        }
        if (rootLabels.contains("Block")) {
            return collectRegionNodeIdsFromBlock(tx, rootNodeId, modelNodeIds);
        }
        if (rootLabels.contains("DeclarationStatement")) {
            return collectDeclarationNodeIds(tx, rootNodeId, modelNodeIds);
        }
        if (modelNodeIds.contains(rootNodeId)) {
            return List.of(rootNodeId);
        }
        return List.of();
    }

    private List<String> collectDeclarationNodeIds(
            TransactionContext tx,
            String statementNodeId,
            Set<String> modelNodeIds
    ) {
        List<String> declarationNodeIds = new ArrayList<>();
        List<Record> declarations = tx.run(DECLARATIONS_QUERY, Values.parameters("statementNodeId", statementNodeId)).list();
        for (Record declaration : declarations) {
            String declarationNodeId = getNullableString(declaration, "declarationNodeId");
            if (declarationNodeId != null && modelNodeIds.contains(declarationNodeId)) {
                declarationNodeIds.add(declarationNodeId);
            }
        }
        return declarationNodeIds;
    }

    private List<String> collectRegionNodeIdsFromBlock(
            TransactionContext tx,
            String blockNodeId,
            Set<String> modelNodeIds
    ) {
        List<String> regionNodeIds = new ArrayList<>();
        List<Record> statements = tx.run(BLOCK_STATEMENTS_QUERY, Values.parameters("blockNodeId", blockNodeId)).list();
        for (Record statement : statements) {
            String statementNodeId = statement.get("statementNodeId").asString();
            List<String> labels = statement.get("labels").asList(Value::asString);
            regionNodeIds.addAll(collectRegionNodeIdsFromRoot(tx, statementNodeId, labels, modelNodeIds));
        }
        return regionNodeIds;
    }

    private List<String> getLabels(Record record, String key) {
        Value value = record.get(key);
        if (value == null || value.isNull()) {
            return List.of();
        }
        return value.asList(Value::asString);
    }

    private boolean isSwitchMarker(List<String> labels) {
        return labels.contains("CaseStatement") || labels.contains("DefaultStatement");
    }

    private String detectArmBlockNodeId(
            TransactionContext tx,
            List<String> armNodeIds,
            String switchBodyNodeId
    ) {
        if (armNodeIds.isEmpty() || switchBodyNodeId == null) {
            return null;
        }

        List<Record> statements = tx.run(BLOCK_STATEMENTS_QUERY, Values.parameters("blockNodeId", switchBodyNodeId)).list();
        for (Record statement : statements) {
            String statementNodeId = statement.get("statementNodeId").asString();
            List<String> labels = statement.get("labels").asList(Value::asString);
            if (labels.contains("Block")) {
                List<String> blockNodeIds = collectRegionNodeIdsFromBlock(tx, statementNodeId, new LinkedHashSet<>(armNodeIds));
                if (blockNodeIds.equals(armNodeIds)) {
                    return statementNodeId;
                }
            }
        }
        return null;
    }

    private String loadDeclarationStatementNodeId(TransactionContext tx, String declarationNodeId) {
        List<Record> records = tx.run("""
                MATCH (statement:DeclarationStatement)-[:DECLARATIONS]->(declaration:ValueDeclaration)
                WHERE elementId(declaration) = $declarationNodeId
                RETURN elementId(statement) AS statementNodeId
                """, Values.parameters("declarationNodeId", declarationNodeId)).list();
        if (records.isEmpty()) {
            return null;
        }
        return getNullableString(records.get(0), "statementNodeId");
    }
}

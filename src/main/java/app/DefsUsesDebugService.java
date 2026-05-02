package app;

import analysismodel.Entity;
import cpg.DefsUsesExtractor;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DefsUsesDebugService {
    private static final String FUNCTION_QUERY = """
            MATCH (f:FunctionDeclaration)-[:BODY]->(body:Block)
            RETURN
              elementId(f) AS functionNodeId,
              coalesce(f.name, f.code, "<unnamed-function>") AS functionName,
              elementId(body) AS bodyNodeId
            ORDER BY functionName, functionNodeId
            """;
    private static final String DEBUG_NODE_QUERY = """
            MATCH (body)-[:AST*0..]->(node)
            WHERE elementId(body) = $bodyNodeId
              AND (
                node:DeclarationStatement
                OR node:AssignExpression
                OR node:CallExpression
                OR node:ReturnStatement
                OR node:UnaryOperator
                OR node:UnaryOp
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

    private final DefsUsesExtractor defsUsesExtractor = new DefsUsesExtractor();

    public List<String> collectDebugLines() {
        try (Driver driver = GraphDatabase.driver(
                AnalysisService.URI,
                AuthTokens.basic(AnalysisService.USER, AnalysisService.PASSWORD));
             Session session = driver.session(SessionConfig.forDatabase(AnalysisService.DATABASE))) {
            return collectDebugLines(session);
        }
    }

    public List<String> collectDebugLines(Session session) {
        return session.executeRead(tx -> {
            DefsUsesExtractor.ExtractionState state = defsUsesExtractor.newState();
            List<String> lines = new ArrayList<>();

            List<Record> functions = tx.run(FUNCTION_QUERY).list();
            for (Record function : functions) {
                String functionName = function.get("functionName").asString();
                String bodyNodeId = function.get("bodyNodeId").asString();
                lines.add("Function: " + functionName);

                List<Record> nodes = tx.run(DEBUG_NODE_QUERY, Values.parameters("bodyNodeId", bodyNodeId)).list();
                for (Record node : nodes) {
                    DebugEntry entry = buildEntry(tx, node, state);
                    if (entry == null) {
                        continue;
                    }
                    lines.add(formatEntry(entry));
                }

                lines.add("");
            }

            return lines;
        });
    }

    private DebugEntry buildEntry(
            TransactionContext tx,
            Record nodeRecord,
            DefsUsesExtractor.ExtractionState state
    ) {
        String nodeId = nodeRecord.get("nodeId").asString();
        List<String> labels = nodeRecord.get("labels").asList(Value::asString);
        String code = nodeRecord.get("code").asString();
        Integer startLine = nodeRecord.get("startLine").isNull() ? null : nodeRecord.get("startLine").asInt();
        String operatorCode = nodeRecord.get("operatorCode").isNull() ? null : nodeRecord.get("operatorCode").asString();

        if (labels.contains("DeclarationStatement")) {
            return buildDeclarationEntry(tx, nodeId, code, startLine, state);
        }
        if (labels.contains("AssignExpression")) {
            return buildAssignmentEntry(tx, nodeId, code, startLine, state);
        }
        if (labels.contains("ReturnStatement")) {
            return buildReturnEntry(tx, nodeId, code, startLine, state);
        }
        if (labels.contains("CallExpression")) {
            return buildCallEntry(tx, nodeId, code, startLine, state);
        }
        if ((labels.contains("UnaryOperator") || labels.contains("UnaryOp")) && isIncrementOrDecrement(operatorCode)) {
            return buildUpdateEntry(tx, nodeId, code, startLine, state);
        }
        return null;
    }

    private DebugEntry buildDeclarationEntry(
            TransactionContext tx,
            String nodeId,
            String code,
            Integer startLine,
            DefsUsesExtractor.ExtractionState state
    ) {
        Set<Entity> defs = new LinkedHashSet<>();
        Set<Entity> uses = new LinkedHashSet<>();

        List<Record> declarations = tx.run(DECLARATIONS_QUERY, Values.parameters("statementNodeId", nodeId)).list();
        for (Record declaration : declarations) {
            String declarationNodeId = getNullableString(declaration, "declarationNodeId");
            String declarationName = getNullableString(declaration, "declarationName");
            defs.addAll(defsUsesExtractor.declarationEntities(tx, declarationNodeId, declarationName, state));

            String initializerNodeId = getNullableString(declaration, "initializerNodeId");
            uses.addAll(defsUsesExtractor.collectReadEntities(tx, initializerNodeId, state));
        }

        return new DebugEntry("DECLARATION", startLine, code, defs, uses);
    }

    private DebugEntry buildAssignmentEntry(
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
        return new DebugEntry("ASSIGNMENT", startLine, code, defs, uses);
    }

    private DebugEntry buildReturnEntry(
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
        return new DebugEntry("RETURN", startLine, code, defs, uses);
    }

    private DebugEntry buildCallEntry(
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
            return new DebugEntry("CALL printf", startLine, code, defs, uses);
        }

        if ("scanf".equals(callName)) {
            for (String argumentNodeId : argumentNodeIds) {
                defs.addAll(defsUsesExtractor.collectWrittenEntities(tx, argumentNodeId, state));
                uses.addAll(defsUsesExtractor.collectScanfAddressingEntities(tx, argumentNodeId, state));
            }
            return new DebugEntry("CALL scanf", startLine, code, defs, uses);
        }

        for (String argumentNodeId : argumentNodeIds) {
            uses.addAll(defsUsesExtractor.collectReadEntities(tx, argumentNodeId, state));
        }
        return new DebugEntry("CALL", startLine, code, defs, uses);
    }

    private DebugEntry buildUpdateEntry(
            TransactionContext tx,
            String nodeId,
            String code,
            Integer startLine,
            DefsUsesExtractor.ExtractionState state
    ) {
        Set<Entity> defs = defsUsesExtractor.collectWrittenEntities(tx, nodeId, state);
        Set<Entity> uses = defsUsesExtractor.collectReadEntities(tx, nodeId, state);
        return new DebugEntry("UPDATE", startLine, code, defs, uses);
    }

    private String formatEntry(DebugEntry entry) {
        return "[" + entry.kind() + "]"
                + " line=" + (entry.startLine() == null ? "?" : entry.startLine())
                + " code=" + entry.code()
                + " | defs=" + formatEntities(entry.defs())
                + " | uses=" + formatEntities(entry.uses());
    }

    private String formatEntities(Set<Entity> entities) {
        if (entities.isEmpty()) {
            return "[]";
        }

        List<String> names = new ArrayList<>();
        for (Entity entity : entities) {
            names.add(entity.name());
        }
        return names.toString();
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

    private record DebugEntry(
            String kind,
            Integer startLine,
            String code,
            Set<Entity> defs,
            Set<Entity> uses
    ) {
    }
}

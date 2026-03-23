package cpg;

import model.ExpressionNode;
import model.StatementNode;
import model.TypeNode;
import model.expression.ArrayAccessExpressionNode;
import model.expression.BinaryExpressionNode;
import model.expression.ConstantExpressionNode;
import model.expression.FunctionCallExpressionNode;
import model.expression.IdentifierExpressionNode;
import model.expression.TernaryExpressionNode;
import model.expression.UnaryExpressionNode;
import model.function.FunctionNode;
import model.function.ParameterNode;
import model.statement.AssignmentNode;
import model.statement.BlockNode;
import model.statement.ExpressionStatementNode;
import model.statement.IfNode;
import model.statement.ReturnNode;
import model.statement.WhileNode;
import model.type.ArrayTypeNode;
import model.type.PointerTypeNode;
import model.type.PrimitiveTypeNode;

import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

import java.util.ArrayList;
import java.util.List;

public final class AstTreeBuilder {
    private static final String FUNCTION_QUERY = """
            MATCH (f:FunctionDeclaration)-[:BODY]->(body:Block)
            RETURN
              elementId(f) AS sourceNodeId,
              coalesce(f.name, f.code, "<unnamed-function>") AS name,
              elementId(body) AS bodySourceNodeId
            ORDER BY name, sourceNodeId
            """;
    private static final String BLOCK_STATEMENTS_QUERY = """
            MATCH (block)-[stmtRel:STATEMENTS]->(statement)
            WHERE elementId(block) = $blockNodeId
            RETURN
              elementId(statement) AS sourceNodeId,
              labels(statement) AS labels,
              statement.code AS code,
              stmtRel.index AS statementIndex
            ORDER BY statementIndex, sourceNodeId
            """;
    private static final String NODE_QUERY = """
            MATCH (node)
            WHERE elementId(node) = $nodeId
            RETURN
              elementId(node) AS sourceNodeId,
              labels(node) AS labels,
              node.name AS name,
              node.code AS code,
              node.fullName AS fullName,
              node.typeName AS typeName,
              node.pointerOrigin AS pointerOrigin,
              node.bitWidth AS bitWidth,
              node.value AS value,
              node.operatorCode AS operatorCode
            """;
    private static final String AST_CHILDREN_QUERY = """
            MATCH (node)-[astRel:AST]->(child)
            WHERE elementId(node) = $nodeId
            RETURN
              astRel.index AS childIndex,
              elementId(child) AS sourceNodeId,
              labels(child) AS labels,
              child.name AS name,
              child.code AS code,
              child.value AS value,
              child.operatorCode AS operatorCode
            ORDER BY childIndex, sourceNodeId
            """;
    private static final String DECLARATIONS_QUERY = """
            MATCH (statement)-[declRel:DECLARATIONS]->(declaration:ValueDeclaration)
            WHERE elementId(statement) = $statementNodeId
            OPTIONAL MATCH (declaration)-[:TYPE]->(type)
            OPTIONAL MATCH (declaration)-[:INITIALIZER]->(initializer)
            WITH declRel, declaration, initializer, type,
              CASE
                WHEN type IS NULL THEN 1000
                WHEN coalesce(type.fullName, type.typeName, type.name) = 'bool' THEN 100
                WHEN 'IntegerType' IN labels(type) THEN 0
                WHEN 'FloatingPointType' IN labels(type) THEN 1
                WHEN 'NumericType' IN labels(type) THEN 2
                WHEN 'PointerType' IN labels(type) THEN 3
                WHEN 'ListType' IN labels(type) OR 'ArrayType' IN labels(type) THEN 4
                WHEN 'BooleanType' IN labels(type) THEN 50
                ELSE 10
              END AS typeRank
            ORDER BY declRel.index, typeRank, coalesce(type.bitWidth, 0) DESC, elementId(type)
            WITH declRel, declaration, initializer, collect(type)[0] AS bestType
            RETURN
              elementId(declaration) AS sourceNodeId,
              declaration.name AS name,
              elementId(bestType) AS typeNodeId,
              elementId(initializer) AS initializerNodeId
            ORDER BY declRel.index, sourceNodeId
            """;
    private static final String RETURN_EXPRESSION_QUERY = """
            MATCH (statement)-[astRel:AST]->(expression)
            WHERE elementId(statement) = $statementNodeId
            RETURN elementId(expression) AS expressionNodeId
            ORDER BY astRel.index, expressionNodeId
            """;
    private static final String ASSIGNMENT_DETAILS_QUERY = """
            MATCH (statement)
            WHERE elementId(statement) = $statementNodeId
            OPTIONAL MATCH (statement)-[:LHS]->(lhs)
            OPTIONAL MATCH (statement)-[:RHS]->(rhs)
            RETURN
              elementId(lhs) AS lhsNodeId,
              elementId(rhs) AS rhsNodeId
            """;
    private static final String INITIALIZER_ELEMENTS_QUERY = """
            MATCH (initializer)-[initRel:INITIALIZERS]->(child)
            WHERE elementId(initializer) = $initializerNodeId
            RETURN
              initRel.index AS initializerIndex,
              elementId(child) AS sourceNodeId,
              labels(child) AS labels,
              child.name AS name,
              child.code AS code,
              child.value AS value,
              child.operatorCode AS operatorCode
            ORDER BY initializerIndex, sourceNodeId
            """;
    private static final String FUNCTION_PARAMETERS_QUERY = """
            MATCH (function)-[parameterRel:PARAMETERS]->(parameter:ParameterDeclaration)
            WHERE elementId(function) = $functionNodeId
            OPTIONAL MATCH (parameter)-[:TYPE]->(type)
            WITH parameterRel, parameter, type,
              CASE
                WHEN type IS NULL THEN 1000
                WHEN coalesce(type.fullName, type.typeName, type.name) = 'bool' THEN 100
                WHEN 'IntegerType' IN labels(type) THEN 0
                WHEN 'FloatingPointType' IN labels(type) THEN 1
                WHEN 'NumericType' IN labels(type) THEN 2
                WHEN 'PointerType' IN labels(type) THEN 3
                WHEN 'ListType' IN labels(type) OR 'ArrayType' IN labels(type) THEN 4
                WHEN 'BooleanType' IN labels(type) THEN 50
                ELSE 10
              END AS typeRank
            ORDER BY parameter.argumentIndex, typeRank, coalesce(type.bitWidth, 0) DESC, elementId(type)
            WITH parameter, collect(type)[0] AS bestType
            RETURN
              elementId(parameter) AS sourceNodeId,
              parameter.name AS name,
              elementId(bestType) AS typeNodeId,
              parameter.argumentIndex AS argumentIndex
            ORDER BY argumentIndex, sourceNodeId
            """;
    private static final String FUNCTION_RETURN_TYPE_QUERY = """
            MATCH (function)-[:RETURN_TYPES]->(type)
            WHERE elementId(function) = $functionNodeId
            WITH type,
              CASE
                WHEN type IS NULL THEN 1000
                WHEN coalesce(type.fullName, type.typeName, type.name) = 'bool' THEN 100
                WHEN 'IntegerType' IN labels(type) THEN 0
                WHEN 'FloatingPointType' IN labels(type) THEN 1
                WHEN 'NumericType' IN labels(type) THEN 2
                WHEN 'PointerType' IN labels(type) THEN 3
                WHEN 'ListType' IN labels(type) OR 'ArrayType' IN labels(type) THEN 4
                WHEN 'BooleanType' IN labels(type) THEN 50
                ELSE 10
              END AS typeRank
            ORDER BY typeRank, coalesce(type.bitWidth, 0) DESC, elementId(type)
            WITH collect(type)[0] AS bestType
            RETURN elementId(bestType) AS typeNodeId
            LIMIT 1
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
    private static final String WHILE_DETAILS_QUERY = """
            MATCH (statement)
            WHERE elementId(statement) = $statementNodeId
            OPTIONAL MATCH (statement)-[:CONDITION]->(condition)
            OPTIONAL MATCH (statement)-[:STATEMENT]->(body)
            RETURN
              elementId(condition) AS conditionNodeId,
              elementId(body) AS bodyNodeId,
              labels(body) AS bodyLabels
            """;
    private static final String TYPE_ELEMENT_QUERY = """
            MATCH (type)-[:ELEMENT_TYPE]->(elementType)
            WHERE elementId(type) = $typeNodeId
            WITH elementType,
              CASE
                WHEN elementType IS NULL THEN 1000
                WHEN coalesce(elementType.fullName, elementType.typeName, elementType.name) = 'bool' THEN 100
                WHEN 'IntegerType' IN labels(elementType) THEN 0
                WHEN 'FloatingPointType' IN labels(elementType) THEN 1
                WHEN 'NumericType' IN labels(elementType) THEN 2
                WHEN 'PointerType' IN labels(elementType) THEN 3
                WHEN 'ListType' IN labels(elementType) OR 'ArrayType' IN labels(elementType) THEN 4
                WHEN 'BooleanType' IN labels(elementType) THEN 50
                ELSE 10
              END AS typeRank
            ORDER BY typeRank, coalesce(elementType.bitWidth, 0) DESC, elementId(elementType)
            WITH collect(elementType)[0] AS bestType
            RETURN elementId(bestType) AS typeNodeId
            LIMIT 1
            """;

    public List<FunctionNode> buildFunctions(Session session) {
        List<Record> functionRecords = session.executeRead(tx -> tx.run(FUNCTION_QUERY).list());

        return functionRecords.stream()
                .map(functionRecord -> buildFunctionNode(session, functionRecord))
                .toList();
    }

    private FunctionNode buildFunctionNode(Session session, Record functionRecord) {
        String sourceNodeId = functionRecord.get("sourceNodeId").asString();
        String name = functionRecord.get("name").asString();
        String bodySourceNodeId = functionRecord.get("bodySourceNodeId").asString();

        return new FunctionNode(
                sourceNodeId,
                name,
                buildFunctionReturnType(session, sourceNodeId),
                buildFunctionParameters(session, sourceNodeId),
                buildBlockNode(session, bodySourceNodeId)
        );
    }

    private BlockNode buildBlockNode(Session session, String blockNodeId) {
        List<Record> statementRecords = session.executeRead(tx ->
                tx.run(BLOCK_STATEMENTS_QUERY, Values.parameters("blockNodeId", blockNodeId)).list()
        );

        List<StatementNode> statements = new ArrayList<>();
        for (Record statementRecord : statementRecords) {
            statements.addAll(buildStatementNodes(
                    session,
                    statementRecord.get("sourceNodeId").asString(),
                    statementRecord.get("labels").asList(Value::asString)
            ));
        }

        return new BlockNode(blockNodeId, statements);
    }

    private List<StatementNode> buildStatementNodes(Session session, String statementNodeId, List<String> labels) {
        if (labels.contains("Block")) {
            return List.of(buildBlockNode(session, statementNodeId));
        }
        if (labels.contains("DeclarationStatement")) {
            return buildDeclarationAssignments(session, statementNodeId);
        }
        if (labels.contains("AssignExpression")) {
            return List.of(buildAssignmentNode(session, statementNodeId));
        }
        if (labels.contains("ReturnStatement")) {
            return List.of(buildReturnNode(session, statementNodeId));
        }
        if (labels.contains("IfStatement")) {
            return List.of(buildIfNode(session, statementNodeId));
        }
        if (labels.contains("WhileStatement")) {
            return List.of(buildWhileNode(session, statementNodeId));
        }
        if (isExpressionStatement(labels)) {
            return List.of(new ExpressionStatementNode(statementNodeId, buildExpressionNode(session, statementNodeId)));
        }

        return List.of();
    }

    private List<StatementNode> buildDeclarationAssignments(Session session, String statementNodeId) {
        List<Record> declarationRecords = session.executeRead(tx ->
                tx.run(DECLARATIONS_QUERY, Values.parameters("statementNodeId", statementNodeId)).list()
        );

        List<StatementNode> assignments = new ArrayList<>();
        for (Record declarationRecord : declarationRecords) {
            Value initializerNodeId = declarationRecord.get("initializerNodeId");
            if (initializerNodeId == null || initializerNodeId.isNull()) {
                continue;
            }

            String declarationSourceNodeId = declarationRecord.get("sourceNodeId").asString();
            String variableName = getNullableString(declarationRecord, "name");
            Record initializerNodeRecord = loadNodeRecord(session, initializerNodeId.asString());
            List<String> initializerLabels = initializerNodeRecord.get("labels").asList(Value::asString);

            if (initializerLabels.contains("InitializerListExpression")) {
                assignments.addAll(buildInitializerListAssignments(
                        session,
                        declarationSourceNodeId,
                        variableName,
                        initializerNodeId.asString()
                ));
                continue;
            }

            ExpressionNode initializer = buildExpressionNode(session, initializerNodeRecord);
            if (initializer == null) {
                continue;
            }

            assignments.add(buildSimpleAssignment(declarationSourceNodeId, variableName, initializer));
        }

        return assignments;
    }

    private List<StatementNode> buildInitializerListAssignments(
            Session session,
            String declarationSourceNodeId,
            String variableName,
            String initializerNodeId
    ) {
        List<Record> initializerRecords = session.executeRead(tx ->
                tx.run(INITIALIZER_ELEMENTS_QUERY, Values.parameters("initializerNodeId", initializerNodeId)).list()
        );

        List<StatementNode> assignments = new ArrayList<>();
        for (Record initializerRecord : initializerRecords) {
            ExpressionNode value = buildExpressionNode(session, initializerRecord);
            if (value == null) {
                continue;
            }

            int initializerIndex = initializerRecord.get("initializerIndex").asInt();
            String assignmentSourceNodeId = declarationSourceNodeId + "[" + initializerIndex + "]";
            assignments.add(new AssignmentNode(
                    assignmentSourceNodeId,
                    "=",
                    new ArrayAccessExpressionNode(
                            assignmentSourceNodeId,
                            new IdentifierExpressionNode(declarationSourceNodeId, variableName),
                            new ConstantExpressionNode(assignmentSourceNodeId + ":index", Integer.toString(initializerIndex))
                    ),
                    value
            ));
        }

        return assignments;
    }

    private AssignmentNode buildSimpleAssignment(String sourceNodeId, String variableName, ExpressionNode value) {
        return new AssignmentNode(
                sourceNodeId,
                "=",
                new IdentifierExpressionNode(sourceNodeId, variableName),
                value
        );
    }

    private AssignmentNode buildAssignmentNode(Session session, String statementNodeId) {
        Record assignmentNodeRecord = loadNodeRecord(session, statementNodeId);
        Record assignmentDetailsRecord = session.executeRead(tx ->
                tx.run(ASSIGNMENT_DETAILS_QUERY, Values.parameters("statementNodeId", statementNodeId)).single()
        );

        ExpressionNode target = buildOptionalExpressionNode(session, assignmentDetailsRecord.get("lhsNodeId"));
        ExpressionNode value = buildOptionalExpressionNode(session, assignmentDetailsRecord.get("rhsNodeId"));

        if (target == null || value == null) {
            List<Record> children = loadAstChildren(session, statementNodeId);
            if (target == null) {
                target = children.isEmpty() ? null : buildExpressionNode(session, children.get(0));
            }
            if (value == null) {
                value = children.size() < 2 ? null : buildExpressionNode(session, children.get(1));
            }
        }

        String operator = firstNonBlank(
                getNullableString(assignmentNodeRecord, "operatorCode"),
                inferAssignmentOperator(getNullableString(assignmentNodeRecord, "code"))
        );

        return new AssignmentNode(statementNodeId, operator, target, value);
    }

    private ReturnNode buildReturnNode(Session session, String statementNodeId) {
        List<Record> expressionRecords = session.executeRead(tx ->
                tx.run(RETURN_EXPRESSION_QUERY, Values.parameters("statementNodeId", statementNodeId)).list()
        );

        ExpressionNode expression = expressionRecords.isEmpty()
                ? null
                : buildExpressionNode(session, expressionRecords.get(0).get("expressionNodeId").asString());

        return new ReturnNode(statementNodeId, expression);
    }

    private IfNode buildIfNode(Session session, String statementNodeId) {
        Record ifDetailsRecord = session.executeRead(tx ->
                tx.run(IF_DETAILS_QUERY, Values.parameters("statementNodeId", statementNodeId)).single()
        );

        ExpressionNode condition = buildOptionalExpressionNode(session, ifDetailsRecord.get("conditionNodeId"));
        StatementNode thenBranch = buildOptionalStatementNode(
                session,
                ifDetailsRecord.get("thenNodeId"),
                ifDetailsRecord.get("thenLabels")
        );
        StatementNode elseBranch = buildOptionalStatementNode(
                session,
                ifDetailsRecord.get("elseNodeId"),
                ifDetailsRecord.get("elseLabels")
        );

        return new IfNode(statementNodeId, condition, thenBranch, elseBranch);
    }

    private WhileNode buildWhileNode(Session session, String statementNodeId) {
        Record whileDetailsRecord = session.executeRead(tx ->
                tx.run(WHILE_DETAILS_QUERY, Values.parameters("statementNodeId", statementNodeId)).single()
        );

        ExpressionNode condition = buildOptionalExpressionNode(session, whileDetailsRecord.get("conditionNodeId"));
        StatementNode body = buildOptionalStatementNode(
                session,
                whileDetailsRecord.get("bodyNodeId"),
                whileDetailsRecord.get("bodyLabels")
        );

        return new WhileNode(statementNodeId, condition, body);
    }

    private StatementNode buildOptionalStatementNode(Session session, Value nodeIdValue, Value labelsValue) {
        if (nodeIdValue == null || nodeIdValue.isNull()) {
            return null;
        }

        List<StatementNode> statements = buildStatementNodes(session, nodeIdValue.asString(), toStringList(labelsValue));
        if (statements.isEmpty()) {
            return null;
        }
        if (statements.size() == 1) {
            return statements.get(0);
        }

        return new BlockNode(nodeIdValue.asString(), statements);
    }

    private ExpressionNode buildOptionalExpressionNode(Session session, Value nodeIdValue) {
        if (nodeIdValue == null || nodeIdValue.isNull()) {
            return null;
        }

        return buildExpressionNode(session, nodeIdValue.asString());
    }

    private TypeNode buildOptionalTypeNode(Session session, Value nodeIdValue) {
        if (nodeIdValue == null || nodeIdValue.isNull()) {
            return null;
        }

        return buildTypeNode(session, nodeIdValue.asString());
    }

    private ExpressionNode buildExpressionNode(Session session, String expressionNodeId) {
        return buildExpressionNode(session, loadNodeRecord(session, expressionNodeId));
    }

    private ExpressionNode buildExpressionNode(Session session, Record expressionNodeRecord) {
        String sourceNodeId = expressionNodeRecord.get("sourceNodeId").asString();
        List<String> labels = expressionNodeRecord.get("labels").asList(Value::asString);
        String name = getNullableString(expressionNodeRecord, "name");
        String code = getNullableString(expressionNodeRecord, "code");
        String value = getNullableString(expressionNodeRecord, "value");
        String operatorCode = getNullableString(expressionNodeRecord, "operatorCode");

        if (labels.contains("Reference")) {
            return new IdentifierExpressionNode(sourceNodeId, firstNonBlank(name, code));
        }
        if (labels.contains("Literal") || labels.contains("Constant")) {
            return new ConstantExpressionNode(sourceNodeId, firstNonBlank(value, code));
        }
        if (labels.contains("UnaryOperator") || labels.contains("UnaryOp")) {
            List<Record> children = loadAstChildren(session, sourceNodeId);
            ExpressionNode operand = children.isEmpty() ? null : buildExpressionNode(session, children.get(0));
            return new UnaryExpressionNode(sourceNodeId, firstNonBlank(operatorCode, inferUnaryOperator(code)), operand);
        }
        if (labels.contains("BinaryOperator") || labels.contains("BinaryOp")) {
            List<Record> children = loadAstChildren(session, sourceNodeId);
            ExpressionNode left = children.isEmpty() ? null : buildExpressionNode(session, children.get(0));
            ExpressionNode right = children.size() < 2 ? null : buildExpressionNode(session, children.get(1));
            return new BinaryExpressionNode(sourceNodeId, firstNonBlank(operatorCode, inferBinaryOperator(code)), left, right);
        }
        if (labels.contains("CallExpression") || labels.contains("FuncCall")) {
            List<Record> children = loadAstChildren(session, sourceNodeId);
            ExpressionNode function = children.isEmpty() ? null : buildExpressionNode(session, children.get(0));
            List<ExpressionNode> arguments = new ArrayList<>();
            for (int index = 1; index < children.size(); index++) {
                arguments.add(buildExpressionNode(session, children.get(index)));
            }
            return new FunctionCallExpressionNode(sourceNodeId, function, arguments);
        }
        if (labels.contains("ArraySubscriptionExpression")
                || labels.contains("ArrayRef")
                || labels.contains("SubscriptExpression")) {
            List<Record> children = loadAstChildren(session, sourceNodeId);
            ExpressionNode array = children.isEmpty() ? null : buildExpressionNode(session, children.get(0));
            ExpressionNode index = children.size() < 2 ? null : buildExpressionNode(session, children.get(1));
            return new ArrayAccessExpressionNode(sourceNodeId, array, index);
        }
        if (labels.contains("ConditionalExpression") || labels.contains("TernaryOp")) {
            List<Record> children = loadAstChildren(session, sourceNodeId);
            ExpressionNode condition = children.isEmpty() ? null : buildExpressionNode(session, children.get(0));
            ExpressionNode thenExpression = children.size() < 2 ? null : buildExpressionNode(session, children.get(1));
            ExpressionNode elseExpression = children.size() < 3 ? null : buildExpressionNode(session, children.get(2));
            return new TernaryExpressionNode(sourceNodeId, condition, thenExpression, elseExpression);
        }

        return null;
    }

    private Record loadNodeRecord(Session session, String nodeId) {
        return session.executeRead(tx ->
                tx.run(NODE_QUERY, Values.parameters("nodeId", nodeId)).single()
        );
    }

    private List<Record> loadAstChildren(Session session, String nodeId) {
        return session.executeRead(tx ->
                tx.run(AST_CHILDREN_QUERY, Values.parameters("nodeId", nodeId)).list()
        );
    }

    private List<ParameterNode> buildFunctionParameters(Session session, String functionNodeId) {
        List<Record> parameterRecords = session.executeRead(tx ->
                tx.run(FUNCTION_PARAMETERS_QUERY, Values.parameters("functionNodeId", functionNodeId)).list()
        );

        return parameterRecords.stream()
                .map(parameterRecord -> new ParameterNode(
                        parameterRecord.get("sourceNodeId").asString(),
                        getNullableString(parameterRecord, "name"),
                        buildOptionalTypeNode(session, parameterRecord.get("typeNodeId"))
                ))
                .toList();
    }

    private TypeNode buildFunctionReturnType(Session session, String functionNodeId) {
        List<Record> typeRecords = session.executeRead(tx ->
                tx.run(FUNCTION_RETURN_TYPE_QUERY, Values.parameters("functionNodeId", functionNodeId)).list()
        );

        if (typeRecords.isEmpty()) {
            return null;
        }

        return buildOptionalTypeNode(session, typeRecords.get(0).get("typeNodeId"));
    }

    private TypeNode buildTypeNode(Session session, String typeNodeId) {
        Record typeRecord = loadNodeRecord(session, typeNodeId);
        List<String> labels = typeRecord.get("labels").asList(Value::asString);
        String name = firstNonBlank(
                getNullableString(typeRecord, "fullName"),
                getNullableString(typeRecord, "typeName"),
                getNullableString(typeRecord, "name"),
                getNullableString(typeRecord, "code")
        );
        String pointerOrigin = getNullableString(typeRecord, "pointerOrigin");

        if (labels.contains("PointerType") && "ARRAY".equals(pointerOrigin)) {
            return new ArrayTypeNode(loadElementType(session, typeNodeId), null);
        }
        if (labels.contains("PointerType")) {
            return new PointerTypeNode(loadElementType(session, typeNodeId));
        }
        if (labels.contains("ListType")) {
            return new ArrayTypeNode(loadElementType(session, typeNodeId), null);
        }
        if (labels.contains("IntegerType")
                || labels.contains("FloatingPointType")
                || labels.contains("NumericType")
                || labels.contains("BooleanType")
                || labels.contains("StringType")
                || labels.contains("ObjectType")
                || labels.contains("UnknownType")
                || labels.contains("IncompleteType")) {
            return new PrimitiveTypeNode(name);
        }

        return new PrimitiveTypeNode(name == null ? String.join("/", labels) : name);
    }

    private TypeNode loadElementType(Session session, String typeNodeId) {
        List<Record> elementRecords = session.executeRead(tx ->
                tx.run(TYPE_ELEMENT_QUERY, Values.parameters("typeNodeId", typeNodeId)).list()
        );

        if (elementRecords.isEmpty()) {
            return null;
        }

        return buildOptionalTypeNode(session, elementRecords.get(0).get("typeNodeId"));
    }

    private boolean isExpressionStatement(List<String> labels) {
        return labels.contains("UnaryOperator")
                || labels.contains("BinaryOperator")
                || labels.contains("CallExpression")
                || labels.contains("FuncCall");
    }

    private List<String> toStringList(Value value) {
        return value == null || value.isNull() ? List.of() : value.asList(Value::asString);
    }

    private String getNullableString(Record record, String key) {
        Value value = record.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private String inferAssignmentOperator(String code) {
        if (code == null || code.isBlank()) {
            return "=";
        }

        String[] operators = {"<<=", ">>=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "="};
        for (String operator : operators) {
            if (code.contains(operator)) {
                return operator;
            }
        }
        return "=";
    }

    private String inferUnaryOperator(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim();

        if (trimmed.startsWith("++") || trimmed.endsWith("++") || trimmed.contains("++")) {
            return "++";
        }
        if (trimmed.startsWith("--") || trimmed.endsWith("--") || trimmed.contains("--")) {
            return "--";
        }
        if (trimmed.startsWith("!")) {
            return "!";
        }
        if (trimmed.startsWith("~")) {
            return "~";
        }
        if (trimmed.startsWith("&")) {
            return "&";
        }
        if (trimmed.startsWith("*")) {
            return "*";
        }
        if (trimmed.startsWith("-")) {
            return "-";
        }
        if (trimmed.startsWith("+")) {
            return "+";
        }

        return null;
    }

    private String inferBinaryOperator(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }

        String[] operators = {"<=", ">=", "==", "!=", "&&", "||", "<<", ">>", "<", ">", "+", "-", "*", "/", "%", "&", "|", "^"};
        for (String operator : operators) {
            if (code.contains(operator)) {
                return operator;
            }
        }

        return null;
    }
}

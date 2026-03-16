import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;

import java.util.List;
import java.util.Map;

public class Main {
    private static final String STMT_QUERY = """
            MATCH (:FunctionDeclaration)-[:BODY]->(:Block)-[stmtRel:STATEMENTS]->(s)
            WHERE any(label IN labels(s) WHERE label IN [
              'AssignExpression',
              'UnaryOperator',
              'DeclarationStatement',
              'ReturnStatement',
              'ForStatement',
              'WhileStatement',
              'DoStatement'
            ])
            RETURN
              elementId(s) AS nodeId,
              labels(s) AS nodeLabels,
              s.code AS code,
              stmtRel.index AS statementIndex
            ORDER BY statementIndex, nodeId
            """;
    private static final String BLOCK_STMT_QUERY = """
            MATCH (block)-[stmtRel:STATEMENTS]->(s)
            WHERE elementId(block) = $blockNodeId
              AND any(label IN labels(s) WHERE label IN [
                'AssignExpression',
                'UnaryOperator',
                'DeclarationStatement',
                'ReturnStatement',
                'ForStatement',
                'WhileStatement',
                'DoStatement'
              ])
            RETURN
              elementId(s) AS nodeId,
              labels(s) AS nodeLabels,
              s.code AS code,
              stmtRel.index AS statementIndex
            ORDER BY statementIndex, nodeId
            """;
    private static final String VALUE_DECLARATION_QUERY = """
            MATCH (ds)
            WHERE elementId(ds) = $statementNodeId
            MATCH (ds)-[declRel:DECLARATIONS]->(vd:ValueDeclaration)
            RETURN
              elementId(vd) AS nodeId,
              labels(vd) AS nodeLabels,
              vd.code AS code,
              declRel.index AS declarationIndex
            ORDER BY declarationIndex, nodeId
            """;
    private static final String LOOP_DETAILS_QUERY = """
            MATCH (loop)
            WHERE elementId(loop) = $loopNodeId
            OPTIONAL MATCH (loop)-[:CONDITION]->(condition)
            OPTIONAL MATCH (loop)-[:STATEMENT]->(body:Block)
            OPTIONAL MATCH (loop)-[:INITIALIZER_STATEMENT]->(initializer)
            OPTIONAL MATCH (loop)-[:ITERATION_STATEMENT]->(iteration)
            RETURN
              condition.code AS conditionCode,
              elementId(body) AS bodyBlockId,
              elementId(initializer) AS initializerNodeId,
              labels(initializer) AS initializerLabels,
              initializer.code AS initializerCode,
              elementId(iteration) AS iterationNodeId,
              labels(iteration) AS iterationLabels,
              iteration.code AS iterationCode
            """;

    public static void main(String[] args) {
        String uri = "bolt://localhost:7687";
        String user = "neo4j";
        String password = "strongPasswordHere";

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
             Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            List<Record> statements = session.executeRead(tx -> tx.run(STMT_QUERY).list());
            SequenceNode programSequence = buildSequence(session, statements);

            System.out.println("Found stmt nodes: " + programSequence.size());
            printSequence(programSequence);
        }
    }

    private static SequenceNode buildSequence(Session session, List<Record> statements) {
        SequenceNode sequence = new SequenceNode();

        for (Record statement : statements) {
            for (CodeNode node : mapStatementToNodes(
                    session,
                    statement.get("nodeId").asString(),
                    readLabels(statement, "nodeLabels"),
                    readNullableString(statement, "code"),
                    readStatementIndex(statement, "statementIndex")
            )) {
                sequence.addChild(node);
            }
        }

        return sequence;
    }

    private static List<CodeNode> mapStatementToNodes(
            Session session,
            String nodeId,
            List<String> nodeLabels,
            String code,
            int statementIndex
    ) {
        if (nodeLabels.contains("DeclarationStatement")) {
            return List.copyOf(extractValueDeclarations(session, nodeId, statementIndex));
        }
        if (isLoopStatement(nodeLabels)) {
            return List.of(buildLoopNode(session, nodeId, nodeLabels, code, statementIndex));
        }

        return List.of(buildRegularAction(nodeId, nodeLabels, code, statementIndex));
    }

    private static ActionNode buildRegularAction(
            String nodeId,
            List<String> nodeLabels,
            String code,
            int statementIndex
    ) {
        return new ActionNode(nodeId, detectActionType(nodeLabels), code, statementIndex, null);
    }

    private static List<ActionNode> extractValueDeclarations(Session session, String statementNodeId, int statementIndex) {
        List<Record> valueDeclarations = session.executeRead(tx -> tx.run(
                VALUE_DECLARATION_QUERY,
                Map.of("statementNodeId", statementNodeId)
        ).list());

        if (valueDeclarations.isEmpty()) {
            return List.of(new ActionNode(statementNodeId, ActionType.UNKNOWN, "<no code>", statementIndex, null));
        }

        return valueDeclarations.stream()
                .map(valueDeclaration -> new ActionNode(
                        valueDeclaration.get("nodeId").asString(),
                        ActionType.VALUE_DECLARATION,
                        valueDeclaration.get("code").isNull() ? "<no code>" : valueDeclaration.get("code").asString(),
                        statementIndex,
                        valueDeclaration.get("declarationIndex").isNull()
                                ? null
                                : valueDeclaration.get("declarationIndex").asInt()
                ))
                .toList();
    }

    private static LoopNode buildLoopNode(
            Session session,
            String nodeId,
            List<String> nodeLabels,
            String code,
            int statementIndex
    ) {
        Record loopDetails = session.executeRead(tx -> tx.run(
                LOOP_DETAILS_QUERY,
                Map.of("loopNodeId", nodeId)
        ).single());

        SequenceNode body = loopDetails.get("bodyBlockId").isNull()
                ? new SequenceNode()
                : buildSequence(session, fetchBlockStatements(session, loopDetails.get("bodyBlockId").asString()));
        SequenceNode initializer = buildSingleStatementSequence(
                session,
                readNullableString(loopDetails, "initializerNodeId"),
                readLabels(loopDetails, "initializerLabels"),
                readNullableString(loopDetails, "initializerCode")
        );
        SequenceNode iteration = buildSingleStatementSequence(
                session,
                readNullableString(loopDetails, "iterationNodeId"),
                readLabels(loopDetails, "iterationLabels"),
                readNullableString(loopDetails, "iterationCode")
        );

        return new LoopNode(
                nodeId,
                detectLoopType(nodeLabels),
                code,
                readNullableString(loopDetails, "conditionCode"),
                statementIndex,
                initializer,
                body,
                iteration
        );
    }

    private static SequenceNode buildSingleStatementSequence(
            Session session,
            String nodeId,
            List<String> nodeLabels,
            String code
    ) {
        if (nodeId == null) {
            return null;
        }

        SequenceNode sequence = new SequenceNode();
        for (CodeNode node : mapStatementToNodes(session, nodeId, nodeLabels, code, 0)) {
            sequence.addChild(node);
        }
        return sequence;
    }

    private static List<Record> fetchBlockStatements(Session session, String blockNodeId) {
        return session.executeRead(tx -> tx.run(
                BLOCK_STMT_QUERY,
                Map.of("blockNodeId", blockNodeId)
        ).list());
    }

    private static ActionType detectActionType(List<String> nodeLabels) {
        if (nodeLabels.contains("AssignExpression")) {
            return ActionType.ASSIGNMENT;
        }
        if (nodeLabels.contains("UnaryOperator")) {
            return ActionType.UNARY_OPERATION;
        }
        if (nodeLabels.contains("ReturnStatement")) {
            return ActionType.RETURN;
        }

        return ActionType.UNKNOWN;
    }

    private static LoopType detectLoopType(List<String> nodeLabels) {
        if (nodeLabels.contains("ForStatement")) {
            return LoopType.FOR;
        }
        if (nodeLabels.contains("WhileStatement")) {
            return LoopType.WHILE;
        }
        if (nodeLabels.contains("DoStatement")) {
            return LoopType.DO_WHILE;
        }

        throw new IllegalArgumentException("Unsupported loop labels: " + nodeLabels);
    }

    private static boolean isLoopStatement(List<String> nodeLabels) {
        return nodeLabels.contains("ForStatement")
                || nodeLabels.contains("WhileStatement")
                || nodeLabels.contains("DoStatement");
    }

    private static List<String> readLabels(Record record, String key) {
        return record.get(key).isNull() ? List.of() : record.get(key).asList(Value::asString);
    }

    private static String readNullableString(Record record, String key) {
        return record.get(key).isNull() ? null : record.get(key).asString();
    }

    private static int readStatementIndex(Record record, String key) {
        return record.get(key).isNull() ? -1 : record.get(key).asInt();
    }

    private static void printSequence(SequenceNode sequence) {
        printSequence(sequence, "");
    }

    private static void printSequence(SequenceNode sequence, String indent) {
        for (CodeNode child : sequence.children()) {
            printNode(child, indent);
        }
    }

    private static void printNode(CodeNode node, String indent) {
        if (node instanceof ActionNode action) {
            String declarationLine = action.declarationIndex() == null
                    ? ""
                    : indent + "declarationIndex=" + action.declarationIndex() + System.lineSeparator();

            System.out.printf(
                    """
                    %stype=%s
                    %snodeId=%s
                    %sstatementIndex=%d
                    %s%s------------------------------------------------------------
                    %s%s
                    %s------------------------------------------------------------
                    
                    
                    """,
                    indent,
                    action.actionType(),
                    indent,
                    action.cpgNodeId(),
                    indent,
                    action.statementIndex(),
                    declarationLine,
                    indent,
                    indent,
                    action.code(),
                    indent
            );
            return;
        }

        if (node instanceof LoopNode loop) {
            System.out.printf(
                    """
                    %sloopType=%s
                    %snodeId=%s
                    %sstatementIndex=%d
                    %scondition=%s
                    %scode=%s
                    %sinitializer:
                    """,
                    indent,
                    loop.loopType(),
                    indent,
                    loop.cpgNodeId(),
                    indent,
                    loop.statementIndex(),
                    indent,
                    loop.conditionCode() == null ? "<no condition>" : loop.conditionCode(),
                    indent,
                    loop.code(),
                    indent
            );
            printOptionalSequence(loop.initializer(), indent + "  ");
            System.out.println(indent + "body:");
            printSequence(loop.body(), indent + "  ");
            System.out.println(indent + "iteration:");
            printOptionalSequence(loop.iteration(), indent + "  ");
            System.out.println();
        }
    }

    private static void printOptionalSequence(SequenceNode sequence, String indent) {
        if (sequence == null || sequence.children().isEmpty()) {
            System.out.println(indent + "<empty>");
            return;
        }
        printSequence(sequence, indent);
    }
}

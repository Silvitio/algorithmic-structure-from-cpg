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
              'ReturnStatement'
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
            List<String> nodeLabels = statement.get("nodeLabels").asList(Value::asString);
            if (nodeLabels.contains("DeclarationStatement")) {
                for (ActionNode action : extractValueDeclarations(session, statement)) {
                    sequence.addChild(action);
                }
                continue;
            }

            sequence.addChild(buildRegularAction(statement));
        }

        return sequence;
    }

    private static ActionNode buildRegularAction(Record statement) {
        String nodeId = statement.get("nodeId").asString();
        List<String> nodeLabels = statement.get("nodeLabels").asList(Value::asString);
        String code = statement.get("code").isNull() ? "<no code>" : statement.get("code").asString();
        int statementIndex = statement.get("statementIndex").isNull()
                ? -1
                : statement.get("statementIndex").asInt();

        return new ActionNode(nodeId, detectActionType(nodeLabels), code, statementIndex, null);
    }

    private static List<ActionNode> extractValueDeclarations(Session session, Record declarationStatement) {
        String statementNodeId = declarationStatement.get("nodeId").asString();
        int statementIndex = declarationStatement.get("statementIndex").isNull()
                ? -1
                : declarationStatement.get("statementIndex").asInt();
        List<Record> valueDeclarations = session.executeRead(tx -> tx.run(
                VALUE_DECLARATION_QUERY,
                Map.of("statementNodeId", statementNodeId)
        ).list());

        if (valueDeclarations.isEmpty()) {
            return List.of(buildRegularAction(declarationStatement));
        }

        return valueDeclarations.stream()
                .map(valueDeclaration -> new ActionNode(
                        valueDeclaration.get("nodeId").asString(),
                        "VALUE_DECLARATION",
                        valueDeclaration.get("code").isNull() ? "<no code>" : valueDeclaration.get("code").asString(),
                        statementIndex,
                        valueDeclaration.get("declarationIndex").isNull()
                                ? null
                                : valueDeclaration.get("declarationIndex").asInt()
                ))
                .toList();
    }

    private static String detectActionType(List<String> nodeLabels) {
        if (nodeLabels.contains("AssignExpression")) {
            return "ASSIGNMENT";
        }
        if (nodeLabels.contains("UnaryOperator")) {
            return "UNARY_OPERATION";
        }
        if (nodeLabels.contains("ReturnStatement")) {
            return "RETURN";
        }

        return "UNKNOWN";
    }

    private static void printSequence(SequenceNode sequence) {
        for (CodeNode child : sequence.children()) {
            if (child instanceof ActionNode action) {
                String declarationLine = action.declarationIndex() == null
                        ? ""
                        : "declarationIndex=" + action.declarationIndex() + System.lineSeparator();

                System.out.printf(
                        """
                        type=%s
                        nodeId=%s
                        statementIndex=%d
                        %s------------------------------------------------------------
                        %s
                        ------------------------------------------------------------
                        
                        
                        """,
                        action.actionType(),
                        action.cpgNodeId(),
                        action.statementIndex(),
                        declarationLine,
                        action.code()
                );
            }
        }
    }
}

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;

import java.util.List;

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

    public static void main(String[] args) {
        String uri = "bolt://localhost:7687";
        String user = "neo4j";
        String password = "strongPasswordHere";

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
             Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            List<Record> statements = session.executeRead(tx -> tx.run(STMT_QUERY).list());
            SequenceNode programSequence = buildSequence(statements);

            System.out.println("Found stmt nodes: " + programSequence.size());
            printSequence(programSequence);
        }
    }

    private static SequenceNode buildSequence(List<Record> statements) {
        SequenceNode sequence = new SequenceNode();

        for (Record statement : statements) {
            String nodeId = statement.get("nodeId").asString();
            List<String> nodeLabels = statement.get("nodeLabels").asList(Value::asString);
            String code = statement.get("code").isNull() ? "<no code>" : statement.get("code").asString();
            int statementIndex = statement.get("statementIndex").isNull()
                    ? -1
                    : statement.get("statementIndex").asInt();

            sequence.addChild(new ActionNode(nodeId, detectActionType(nodeLabels), code, statementIndex));
        }

        return sequence;
    }

    private static String detectActionType(List<String> nodeLabels) {
        if (nodeLabels.contains("DeclarationStatement")) {
            return "DECLARATION";
        }
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
                System.out.printf(
                        """
                        type=%s
                        nodeId=%s
                        statementIndex=%d
                        ------------------------------------------------------------
                        %s
                        ------------------------------------------------------------
                        
                        
                        """,
                        action.actionType(),
                        action.cpgNodeId(),
                        action.statementIndex(),
                        action.code()
                );
            }
        }
    }
}

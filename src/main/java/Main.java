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
            MATCH (:FunctionDeclaration)-[:BODY]->(:Block)-[:STATEMENTS]->(s)
            WHERE any(label IN labels(s) WHERE label IN [
              'AssignExpression',
              'UnaryOperator',
              'DeclarationStatement',
              'ReturnStatement'
            ])
            RETURN
              elementId(s) AS nodeId,
              labels(s) AS nodeLabels,
              s.code AS code
            ORDER BY nodeId
            """;

    public static void main(String[] args) {
        String uri = "bolt://localhost:7687";
        String user = "neo4j";
        String password = "strongPasswordHere";

        try (Driver driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
             Session session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            List<Record> statements = session.executeRead(tx -> tx.run(STMT_QUERY).list());

            System.out.println("Found stmt nodes: " + statements.size());
            for (Record statement : statements) {
                String nodeId = statement.get("nodeId").asString();
                List<String> nodeLabels = statement.get("nodeLabels").asList(Value::asString);
                String code = statement.get("code").isNull() ? "<no code>" : statement.get("code").asString();

                System.out.printf(
                        """
                        nodeId=%s
                        labels=%s
                        ------------------------------------------------------------
                        %s
                        ------------------------------------------------------------
                        
                        
                        """,
                        nodeId,
                        nodeLabels,
                        code
                );
            }
        }
    }
}

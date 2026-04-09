package analysis;

import org.neo4j.driver.Record;
import org.neo4j.driver.Session;

public final class SelfAssignmentAnalyzer {
    private static final String CLEAR_MARKS_QUERY = """
            MATCH (assignment:AssignExpression:DEAD_CODE)
            REMOVE assignment:DEAD_CODE
            """;
    private static final String MARK_SELF_ASSIGNMENTS_QUERY = """
            MATCH (assignment:AssignExpression)-[:LHS]->(lhs)
            MATCH (assignment)-[:RHS]->(rhs)
            WHERE coalesce(assignment.operatorCode, '=') = '='
              AND trim(coalesce(lhs.code, lhs.name, '')) <> ''
              AND trim(coalesce(lhs.code, lhs.name, '')) = trim(coalesce(rhs.code, rhs.name, ''))
            SET assignment:DEAD_CODE
            RETURN count(DISTINCT assignment) AS markedCount
            """;

    public int markDeadCode(Session session) {
        return session.executeWrite(tx -> {
            tx.run(CLEAR_MARKS_QUERY).consume();
            Record record = tx.run(MARK_SELF_ASSIGNMENTS_QUERY).single();
            return record.get("markedCount").asInt();
        });
    }
}

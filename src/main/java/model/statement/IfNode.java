package model.statement;

import model.ExpressionNode;
import model.StatementNode;

public record IfNode(
        String sourceNodeId,
        ExpressionNode condition,
        StatementNode thenBranch,
        StatementNode elseBranch
) implements StatementNode {
}

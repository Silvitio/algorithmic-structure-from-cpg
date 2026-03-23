package model.statement;

import model.ExpressionNode;
import model.StatementNode;

public record ExpressionStatementNode(
        String sourceNodeId,
        ExpressionNode expression
) implements StatementNode {
}

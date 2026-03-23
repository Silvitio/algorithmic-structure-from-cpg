package model.statement;

import model.ExpressionNode;
import model.StatementNode;

public record WhileNode(
        String sourceNodeId,
        ExpressionNode condition,
        StatementNode body
) implements StatementNode {
}

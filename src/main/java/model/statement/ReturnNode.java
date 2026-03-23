package model.statement;

import model.ExpressionNode;
import model.StatementNode;

public record ReturnNode(
        String sourceNodeId,
        ExpressionNode expression
) implements StatementNode {
}

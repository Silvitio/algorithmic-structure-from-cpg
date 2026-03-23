package model.statement;

import model.ExpressionNode;
import model.StatementNode;

public record AssignmentNode(
        String sourceNodeId,
        String operator,
        ExpressionNode target,
        ExpressionNode value
) implements StatementNode {
}

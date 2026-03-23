package model.expression;

import model.ExpressionNode;

public record UnaryExpressionNode(
        String sourceNodeId,
        String operator,
        ExpressionNode operand
) implements ExpressionNode {
}

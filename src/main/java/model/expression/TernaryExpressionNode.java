package model.expression;

import model.ExpressionNode;

public record TernaryExpressionNode(
        String sourceNodeId,
        ExpressionNode condition,
        ExpressionNode thenExpression,
        ExpressionNode elseExpression
) implements ExpressionNode {
}

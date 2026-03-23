package model.expression;

import model.ExpressionNode;

public record ArrayAccessExpressionNode(
        String sourceNodeId,
        ExpressionNode array,
        ExpressionNode index
) implements ExpressionNode {
}

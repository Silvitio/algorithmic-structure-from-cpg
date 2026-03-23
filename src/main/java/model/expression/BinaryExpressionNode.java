package model.expression;

import model.ExpressionNode;

public record BinaryExpressionNode(
        String sourceNodeId,
        String operator,
        ExpressionNode left,
        ExpressionNode right
) implements ExpressionNode {
}

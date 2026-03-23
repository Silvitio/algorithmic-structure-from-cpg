package model.expression;

import model.ExpressionNode;

public record ConstantExpressionNode(
        String sourceNodeId,
        String value
) implements ExpressionNode {
}

package model.expression;

import model.ExpressionNode;

public record IdentifierExpressionNode(
        String sourceNodeId,
        String name
) implements ExpressionNode {
}

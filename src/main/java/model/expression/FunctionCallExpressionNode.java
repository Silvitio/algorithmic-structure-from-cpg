package model.expression;

import model.ExpressionNode;

import java.util.List;

public record FunctionCallExpressionNode(
        String sourceNodeId,
        ExpressionNode function,
        List<ExpressionNode> arguments
) implements ExpressionNode {
    public FunctionCallExpressionNode {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }
}

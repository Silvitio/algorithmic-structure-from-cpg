package model.type;

import model.ExpressionNode;
import model.TypeNode;

public record ArrayTypeNode(
        TypeNode elementType,
        ExpressionNode size
) implements TypeNode {
}

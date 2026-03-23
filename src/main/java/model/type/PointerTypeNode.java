package model.type;

import model.TypeNode;

public record PointerTypeNode(
        TypeNode targetType
) implements TypeNode {
}
